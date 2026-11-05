package com.waterlogistics.dispatch.weather;

import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 气象熔断触发器（M2 · 卢肄文 · 10-20 任务 B2 的「注入闸门」一半）。
 *
 * <p>职责边界（与张睿哲 B3 的分工，一句话说清）：
 * <ul>
 *   <li><b>本类</b>：风况超严重阈值 → 调 {@link DispatchGateService#trip(GateSource)}
 *       置 <b>{@link GateSource#AUTO}</b>；回落到阈值下并连续
 *       {@code weatherClearDebounce} 条 → 调 {@link DispatchGateService#clear(GateSource)}；</li>
 *   <li><b>B3</b>：在组批入口（{@code TaskQueueService.startDispatch}）读
 *       {@link DispatchGateService#isOpen()}，熔断时新单转「已受理待唤醒」而<b>不拒单</b>。</li>
 * </ul>
 * <p>两边不重叠：本类只「置位／复位闸门」，不碰订单状态；B3 只「读闸门」，不判气象。
 *
 * <h3>为什么走 {@link GateSource#AUTO} 而不是新建枚举值</h3>
 * <p>{@code contract.rest.GateSource} 只有 {@code MANUAL}（人工冻结）与
 * {@code AUTO}（自动熔断）两个值，气象熔断属自动熔断——<b>不需要改契约</b>。
 * 且 {@code DispatchGateService.clear()} 只移除指定来源：人工冻结仍在时闸门保持熔断，
 * 符合 {@code 22} §3.2「人力冻结不覆盖自动熔断」。
 *
 * <h3>三条保守性纪律（写反了就会在坏天气里派单）</h3>
 * <ol>
 *   <li><b>无新鲜风况不动闸门</b>：{@code worstWindMs} 为空时直接返回，不置位也不解除
 *       ——缺数据不等于安全。</li>
 *   <li><b>解除要防抖</b>：连续 {@code weatherClearDebounce} 条回落才解除，避免阵风边缘
 *       反复通断（抖动会让 B3 的等待队列反复唤醒）。</li>
 *   <li><b>熔断只由本类置的 {@code AUTO} 来源解除</b>：本类只 {@code clear(AUTO)}，
 *       绝不 {@code reset()}（那会把人工冻结一并清掉）。</li>
 * </ol>
 *
 * <p>线程安全：{@link #evaluate(long)} 加 {@code synchronized}。MQ 消费是单容器线程，
 * 但定时评估与人工复位可能并发，加锁成本可忽略。
 */
@Component
public class WeatherGateTrigger {

    private static final Logger log = LoggerFactory.getLogger(WeatherGateTrigger.class);

    private final WeatherSnapshotStore store;
    private final DispatchGateService gateService;
    private final WeatherSettings settings;

    /** 是否处于「因气象熔断」状态（只由本类维护）。 */
    private boolean trippedByWeather;

    /** 熔断期间连续低于阈值的样本数。 */
    private int consecutiveBelow;

    public WeatherGateTrigger(WeatherSnapshotStore store, DispatchGateService gateService,
            WeatherSettings settings) {
        this.store = store;
        this.gateService = gateService;
        this.settings = settings;
    }

    /**
     * 评估一次风况并视情况置位／解除气象熔断。
     *
     * <p>在每收到一条 {@code data.weather} 后调用（见 {@link WeatherIngestListener}）；
     * 也可由定时任务按同一节拍调用——本方法幂等，重复调用不会重复置位。
     *
     * @param nowMs 当前时刻，Unix 毫秒
     * @return 本次是否改变了闸门状态（{@code true} 表示发生了置位或解除）
     */
    public synchronized boolean evaluate(long nowMs) {
        OptionalDouble worst = store.worstWindMs(nowMs);
        if (worst.isEmpty()) {
            // 纪律 1：没有新鲜风况就不动闸门（既不置位也不解除）
            return false;
        }
        double wind = worst.getAsDouble();
        // HIGH.36：本地标记可能与闸门实际来源脱节（人工复位、bean 重建后闸门残留 AUTO），
        // 每次评估先与 gateService.summary() 对账，以闸门侧为准
        boolean autoRegistered = gateService.summary().gateSources().contains(GateSource.AUTO);

        // MEDIUM.67：边界统一为「达到即熔断」（>=），与运维口径一致
        if (wind >= settings.criticalWindMs()) {
            consecutiveBelow = 0;
            if (!trippedByWeather || !autoRegistered) {
                // MEDIUM.68：先调闸门成功后再改本地标记，避免调用抛异常时标记悬空
                gateService.trip(GateSource.AUTO);
                trippedByWeather = true;
                log.warn("气象熔断：最不利风速 {} m/s 达到严重阈值 {} m/s，闸门已置 AUTO 熔断",
                        wind, settings.criticalWindMs());
                return true;
            }
            return false;
        }

        // 到达此处必有 wind < critical（达到阈值已在上面分支熔断），计入「低于阈值」样本
        if (!trippedByWeather && !autoRegistered) {
            consecutiveBelow = 0;
            return false;
        }

        // 纪律 2：连续 N 条回落才解除
        consecutiveBelow++;
        if (consecutiveBelow >= settings.clearDebounceCount()) {
            // MEDIUM.68：clear 分支同样先调用成功后再改本地标记
            gateService.clear(GateSource.AUTO);
            trippedByWeather = false;
            consecutiveBelow = 0;
            log.info("气象熔断解除：最不利风速 {} m/s 已连续 {} 条低于严重阈值 {} m/s",
                    wind, settings.clearDebounceCount(), settings.criticalWindMs());
            return true;
        }
        return false;
    }

    /** 当前是否因气象处于熔断状态。 */
    public synchronized boolean isTrippedByWeather() {
        return trippedByWeather;
    }

    /** 熔断期间已连续回落的样本数（诊断用）。 */
    public synchronized int consecutiveBelow() {
        return consecutiveBelow;
    }
}
