package com.waterlogistics.dispatch.order;

import java.math.BigDecimal;

/**
 * 订单受理事件（B0 主链触发器的入口信号）。
 *
 * <p>{@link OrderAdmissionService#submit} 判定出口为「受理并调度」或「延后待唤醒」时发布本事件，
 * {@code dispatch.queue.DispatchTrigger} 监听后驱动落库；前者继续
 * 「受理 → 组批调度」，后者只落库为「已受理待唤醒」、不进调度
 * （阶段一 26 接线：接单判定不通过的延后单也要在 {@code order_task} 可见）。
 *
 * <h3>构造期校验（11-05 序号 2 · MED-BUG.38 / LOW-MAINT.11）</h3>
 * <p>本事件是「已经发生的事实」，一旦发出就会被落库成 {@code order_task} 的一行；
 * 坏值在这里放过，只会在库里变成一条看起来合法、但语义错误的订单（事后无从分辨）。
 * 故三处校验放在紧凑构造器，宁可让发布方在日志里看见异常，也不写脏行：
 * <ul>
 *   <li>{@code goodsWeightKg} 非空时必须<b>有限且为正</b>：{@code NaN} 与负重量过
 *       {@link BigDecimal#valueOf(double)} 会造出无法比较的列值，载重上界判据对它恒
 *       {@code false}（静默放行超载单）。</li>
 *   <li>{@code submitTsMs} 必须为正：它是落库的 {@code create_time}，0 会把订单排到
 *       1970 年，重复单的 {@code dupWindowSec} 判据与列表排序都会跟着错。原语
 *       {@code long} 无法表达「未设置」，所以只能用「非正即非法」表达漏传。</li>
 *   <li>{@code orderId}／{@code shipMmsi} 非空白：这两列在 {@code order_task} 是主键与
 *       非空列，空值不是「稍后补」而是直接插入失败。</li>
 * </ul>
 *
 * @param orderId         订单号
 * @param shipMmsi        目标船 MMSI
 * @param goodsWeightKg   物资载重，kg
 * @param goodsType       物资类型（可空）
 * @param expectedTimeMs  期望送达时刻，Unix 毫秒（可空）
 * @param priority        优先级（NORMAL/URGENT/EMERGENCY）
 * @param submitTsMs      下单时刻，Unix 毫秒（必须为正）
 * @param waitReason      延后等待原因（{@code WAIT_SHIP}／{@code WAIT_CARGO}／{@code GATE_OR_DATA}）；
 *                        {@code null} 表示受理并调度，进完整主链
 * @param wakeEtaTs       预计唤醒时刻，Unix 毫秒；不可估计为 {@code null}（仅延后有语义）
 * @param etaTs           预计送达时刻，Unix 毫秒；未算出为 {@code null}。
 *                        11-05 序号 2 新增：由 {@code AdmissionPipeline.Decision.etaTsMs} 给出，
 *                        {@code DispatchTrigger} 落 {@code order_task.eta_ts}。
 *                        与 {@code wakeEtaTs}（预计唤醒）不同义，不得互赋
 * @param cargoNestId     货物所在巢；{@code null}＝未指定（取离船最近巢）。
 *                        11-05 序号 2 新增：{@code DispatchTrigger} 落 {@code order_task.cargo_nest_id}，
 *                        重复单判据也以它为准
 */
public record OrderSubmittedEvent(
        String orderId,
        String shipMmsi,
        Double goodsWeightKg,
        String goodsType,
        Long expectedTimeMs,
        String priority,
        long submitTsMs,
        String waitReason,
        Long wakeEtaTs,
        Long etaTs,
        String cargoNestId) {

    public OrderSubmittedEvent {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("订单号不得为空");
        }
        if (shipMmsi == null || shipMmsi.isBlank()) {
            throw new IllegalArgumentException("船舶 MMSI 不得为空");
        }
        // MED-BUG.38：NaN／负重量在库里既不能比较也不能聚合，载重上界判据对它恒 false
        if (goodsWeightKg != null
                && (!Double.isFinite(goodsWeightKg) || !(goodsWeightKg > 0.0))) {
            throw new IllegalArgumentException("物资载重必须为正值有限数：" + goodsWeightKg);
        }
        // LOW-MAINT.11：原语 long 表达不了「未设置」，非正即视为漏传
        if (submitTsMs <= 0L) {
            throw new IllegalArgumentException("下单时刻必须为正的 Unix 毫秒：" + submitTsMs);
        }
        // 货巢全空白视同未指定：与 OrderSubmitRequest 的归一同一口径，
        // 否则「␠」会作为一个独立的货巢值参与重复单判据，把本可判重的单放过去
        if (cargoNestId != null && cargoNestId.isBlank()) {
            cargoNestId = null;
        }
    }

    /**
     * 兼容 11-05 序号 2 之前的 10 参构造：未带货物所在巢。
     *
     * @param orderId        订单号
     * @param shipMmsi       目标船 MMSI
     * @param goodsWeightKg  物资载重，kg
     * @param goodsType      物资类型
     * @param expectedTimeMs 期望送达时刻，Unix 毫秒
     * @param priority       优先级
     * @param submitTsMs     下单时刻，Unix 毫秒
     * @param waitReason     延后等待原因
     * @param wakeEtaTs      预计唤醒时刻，Unix 毫秒
     * @param etaTs          预计送达时刻，Unix 毫秒
     */
    public OrderSubmittedEvent(String orderId, String shipMmsi, Double goodsWeightKg,
            String goodsType, Long expectedTimeMs, String priority, long submitTsMs,
            String waitReason, Long wakeEtaTs, Long etaTs) {
        this(orderId, shipMmsi, goodsWeightKg, goodsType, expectedTimeMs, priority, submitTsMs,
                waitReason, wakeEtaTs, etaTs, null);
    }

    /**
     * 兼容 11-05 序号 2 之前的 9 参构造：预计送达时刻与货物所在巢均未给出。
     *
     * @param orderId        订单号
     * @param shipMmsi       目标船 MMSI
     * @param goodsWeightKg  物资载重，kg
     * @param goodsType      物资类型
     * @param expectedTimeMs 期望送达时刻，Unix 毫秒
     * @param priority       优先级
     * @param submitTsMs     下单时刻，Unix 毫秒
     * @param waitReason     延后等待原因
     * @param wakeEtaTs      预计唤醒时刻，Unix 毫秒
     */
    public OrderSubmittedEvent(String orderId, String shipMmsi, Double goodsWeightKg,
            String goodsType, Long expectedTimeMs, String priority, long submitTsMs,
            String waitReason, Long wakeEtaTs) {
        this(orderId, shipMmsi, goodsWeightKg, goodsType, expectedTimeMs, priority, submitTsMs,
                waitReason, wakeEtaTs, null, null);
    }

    /** 载重转 BigDecimal（落库用）；null 安全。 */
    public BigDecimal goodsWeightDecimal() {
        return goodsWeightKg == null ? null : BigDecimal.valueOf(goodsWeightKg);
    }

    /** 是否延后单（延后单落库即「已受理待唤醒」，不进调度）。 */
    public boolean deferred() {
        return waitReason != null;
    }
}
