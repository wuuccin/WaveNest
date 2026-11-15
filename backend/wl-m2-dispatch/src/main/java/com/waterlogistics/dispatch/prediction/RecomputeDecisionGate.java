package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.storage.param.SysParamLoader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 6a 重调度判据闸门（《1112-功能做实批次｜接口约定》v1.2 §5）。
 *
 * <p>八键全部从 {@code sys_param} 读表，取值与 {@code deploy/sql/002_sys_param.sql} 一致；
 * 不新增键、不臆造键名、不改表内取值。缺省值只在键缺失或坏值时兜底，且取 002 中的登记值。
 *
 * <p><b>为什么集中到本类</b>：《约定》§5 要求「迟滞由 M2 侧编排实现」，
 * {@code DeviationTracker} 自述不做迟滞与滤波，故不碰 M3；本类位于 M2 常驻层，
 * 由 {@code wl-app} 的 {@code RendezvousRecomputeRequester} 在每帧重算链上调用，
 * 即「接循环体」，而不是散落在预测服务与重算请求方两处各判一半。
 *
 * <p>八项用途：
 * <ul>
 *   <li>{@code thetaThDeg}：超阈判据线，{@code deltaDeg >= thetaThDeg} 才发重算；</li>
 *   <li>{@code deadEnterRatio}／{@code deadExitRatio}：死区，幅值降到退出线以下即清除计量，
 *       重新升到进入线以上才算一次新跳变；</li>
 *   <li>{@code hystEnterRatio}／{@code hystExitRatio}：迟滞，进入变向态后须降回退出线以下
 *       才解锁，抑制同一段变向反复触发；</li>
 *   <li>{@code minTriggerIntervalSec}：同一订单两次重算的最小间隔；</li>
 *   <li>{@code cooldownSec}：重调度完成后的冷却期，冷却内不重算；</li>
 *   <li>{@code rendezvousWindowMarginSec}：决策截止，距会面时刻不大于该值时
 *       <b>停止重算与下发，只上报 M2</b>（与 {@code RendezvousCoordinator} 中同名键的
 *       「会面求解窗口余量」语义不同，两条读取互不替代）。</li>
 * </ul>
 *
 * <p>状态按 {@code orderId} 分桶，纯内存、线程安全；进程重启后状态归零属可接受口径
 * （不影响订单与航迹的持久化事实）。
 */
@Component
public class RecomputeDecisionGate {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String THETA_TH_DEG_KEY = "thetaThDeg";
    private static final String MIN_TRIGGER_INTERVAL_SEC_KEY = "minTriggerIntervalSec";
    private static final String COOLDOWN_SEC_KEY = "cooldownSec";
    private static final String HYST_ENTER_RATIO_KEY = "hystEnterRatio";
    private static final String HYST_EXIT_RATIO_KEY = "hystExitRatio";
    private static final String DEAD_ENTER_RATIO_KEY = "deadEnterRatio";
    private static final String DEAD_EXIT_RATIO_KEY = "deadExitRatio";
    private static final String DECISION_DEADLINE_SEC_KEY = "rendezvousWindowMarginSec";

    private static final double DEFAULT_THETA_TH_DEG = 20.0;
    private static final int DEFAULT_MIN_TRIGGER_INTERVAL_SEC = 60;
    private static final int DEFAULT_COOLDOWN_SEC = 120;
    private static final double DEFAULT_HYST_ENTER_RATIO = 1.0;
    private static final double DEFAULT_HYST_EXIT_RATIO = 0.6;
    private static final double DEFAULT_DEAD_ENTER_RATIO = 1.0;
    private static final double DEFAULT_DEAD_EXIT_RATIO = 0.6;
    private static final int DEFAULT_DECISION_DEADLINE_SEC = 120;

    /** 判定结论：只有 {@link #ALLOW} 才允许发起 6a 重算与下发。 */
    public enum Decision {
        ALLOW,
        BELOW_THRESHOLD,
        DEAD_ZONE,
        HYSTERESIS_LOCK,
        MIN_INTERVAL,
        COOLDOWN,
        DECISION_DEADLINE
    }

    /** 本次判定所用八键快照（可观测、便于核对读表结果）。 */
    public record Params(double thetaThDeg, int minTriggerIntervalSec, int cooldownSec,
            double hystEnterRatio, double hystExitRatio,
            double deadEnterRatio, double deadExitRatio, int decisionDeadlineSec) {
    }

    private final SysParamLoader sysParamLoader;
    private final Map<String, Long> lastTriggerMs = new ConcurrentHashMap<>();
    private final Map<String, Long> completedMs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hystActive = new ConcurrentHashMap<>();
    private final AtomicLong deadlineReports = new AtomicLong();

    public RecomputeDecisionGate(SysParamLoader sysParamLoader) {
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 判定某订单本帧是否允许 6a 重算。
     *
     * @param orderId  订单号（状态分桶键）
     * @param deltaDeg 本帧航向跳变最短弧差，度，非负
     * @param meetTime 订单当前会面时刻；{@code null} 表示尚无可比时刻，跳过决策截止
     * @param nowMs    当前时刻，Unix 毫秒
     * @return 判定结论；{@link Decision#DECISION_DEADLINE} 由调用方只上报 M2、不重算不下发
     */
    public Decision evaluate(String orderId, double deltaDeg, LocalDateTime meetTime, long nowMs) {
        Params p = params();
        if (orderId == null || orderId.isBlank()) {
            return Decision.BELOW_THRESHOLD;
        }
        double delta = Double.isFinite(deltaDeg) && deltaDeg >= 0.0 ? deltaDeg : 0.0;

        // 决策截止优先：已进会面窗口就停止重算与下发，只上报 M2。
        if (meetTime != null) {
            long meetMs = meetTime.atZone(DB_ZONE).toInstant().toEpochMilli();
            if (meetMs - nowMs <= (long) p.decisionDeadlineSec() * 1000L) {
                return Decision.DECISION_DEADLINE;
            }
        }

        Long lastCompleted = completedMs.get(orderId);
        if (lastCompleted != null && nowMs - lastCompleted < (long) p.cooldownSec() * 1000L) {
            return Decision.COOLDOWN;
        }
        Long lastTrigger = lastTriggerMs.get(orderId);
        if (lastTrigger != null && nowMs - lastTrigger < (long) p.minTriggerIntervalSec() * 1000L) {
            return Decision.MIN_INTERVAL;
        }

        double deadExitDeg = p.thetaThDeg() * p.deadExitRatio();
        if (delta <= deadExitDeg) {
            // 落入死区：清除变向态与计量，下一帧须重新升到进入线以上才算新跳变。
            hystActive.remove(orderId);
            return Decision.DEAD_ZONE;
        }
        double deadEnterDeg = p.thetaThDeg() * p.deadEnterRatio();
        if (delta < deadEnterDeg) {
            return Decision.BELOW_THRESHOLD;
        }
        // 超阈判据：deltaDeg >= thetaThDeg 才发重算（deadEnterRatio 与 hystEnterRatio 同为 1.0，
        // 此处仍按两键分别取值，改表后两道门限可独立生效）。
        if (delta < p.thetaThDeg() || delta < p.thetaThDeg() * p.hystEnterRatio()) {
            return Decision.BELOW_THRESHOLD;
        }

        double hystExitDeg = p.thetaThDeg() * p.hystExitRatio();
        if (Boolean.TRUE.equals(hystActive.get(orderId))) {
            if (delta > hystExitDeg) {
                // 仍在变向态：一次变向只重算一次，抑制乒乓。
                return Decision.HYSTERESIS_LOCK;
            }
            hystActive.remove(orderId);
        }
        hystActive.put(orderId, Boolean.TRUE);
        return Decision.ALLOW;
    }

    /** 判定放行、重算请求已发出时登记触发时刻（供最小间隔判据）。 */
    public void markTriggered(String orderId, long nowMs) {
        if (orderId != null && !orderId.isBlank()) {
            lastTriggerMs.put(orderId, nowMs);
        }
    }

    /** 重调度完成（会面点已提交）时登记冷却起点。 */
    public void markCompleted(String orderId, long nowMs) {
        if (orderId != null && !orderId.isBlank()) {
            completedMs.put(orderId, nowMs);
            hystActive.remove(orderId);
        }
    }

    /** 决策截止只上报 M2 时累加计数，供排障与判据核对。 */
    public void noteDeadlineReport() {
        deadlineReports.incrementAndGet();
    }

    public long deadlineReportCount() {
        return deadlineReports.get();
    }

    /** 读表八键；坏值回落 002 登记值，不臆造键名。 */
    public Params params() {
        return new Params(
                positiveDouble(THETA_TH_DEG_KEY, DEFAULT_THETA_TH_DEG),
                positiveInt(MIN_TRIGGER_INTERVAL_SEC_KEY, DEFAULT_MIN_TRIGGER_INTERVAL_SEC),
                positiveInt(COOLDOWN_SEC_KEY, DEFAULT_COOLDOWN_SEC),
                positiveDouble(HYST_ENTER_RATIO_KEY, DEFAULT_HYST_ENTER_RATIO),
                positiveDouble(HYST_EXIT_RATIO_KEY, DEFAULT_HYST_EXIT_RATIO),
                positiveDouble(DEAD_ENTER_RATIO_KEY, DEFAULT_DEAD_ENTER_RATIO),
                positiveDouble(DEAD_EXIT_RATIO_KEY, DEFAULT_DEAD_EXIT_RATIO),
                positiveInt(DECISION_DEADLINE_SEC_KEY, DEFAULT_DECISION_DEADLINE_SEC));
    }

    private double positiveDouble(String key, double defaultValue) {
        double value = sysParamLoader.getDouble(key, defaultValue);
        return Double.isFinite(value) && value > 0.0 ? value : defaultValue;
    }

    private int positiveInt(String key, int defaultValue) {
        int value = sysParamLoader.getInt(key, defaultValue);
        return value > 0 ? value : defaultValue;
    }
}
