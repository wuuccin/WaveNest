package com.waterlogistics.dispatch.taskstate;

import com.waterlogistics.contract.state.OrderStatus;
import java.util.List;

/**
 * 飞行阶段时间表——纯算术，不读库、不写日志，便于单测与复用。
 *
 * <p>起飞与会面之间按「机巢到会面点距离 ÷ 巡航速度」估去程时长；
 * 交接持续 {@code handoverSec} 秒；返程沿用去程时长。
 */
final class FlightPhaseTimeline {

    private FlightPhaseTimeline() {
    }

    /**
     * 生成四段迁移的到期时刻。
     *
     * @param meetMs       会面时刻，Unix 毫秒
     * @param outboundM    机巢到会面点距离，米
     * @param cruiseSpeedMs 巡航速度，米每秒
     * @param handoverSec  交接持续时长，秒
     * @return 按时间升序的四段迁移
     * @throws IllegalArgumentException 距离、速度或会面时刻非法，或去程时长溢出
     */
    static List<Transition> schedule(long meetMs, double outboundM, double cruiseSpeedMs,
            int handoverSec) {
        if (meetMs <= 0L) {
            throw new IllegalArgumentException("会面时刻须为 Unix 毫秒值，实际 " + meetMs);
        }
        if (!Double.isFinite(outboundM) || outboundM < 0.0) {
            throw new IllegalArgumentException("去程距离须为非负有限数，实际 " + outboundM);
        }
        if (!Double.isFinite(cruiseSpeedMs) || cruiseSpeedMs <= 0.0) {
            throw new IllegalArgumentException("巡航速度须为正的有限数，实际 " + cruiseSpeedMs);
        }
        if (handoverSec < 0) {
            throw new IllegalArgumentException("交接时长不得为负，实际 " + handoverSec);
        }
        double outboundMsExact = outboundM / cruiseSpeedMs * 1000.0;
        if (!Double.isFinite(outboundMsExact)) {
            throw new IllegalArgumentException("去程时长溢出：去程距离=" + outboundM
                    + " 米，巡航速度=" + cruiseSpeedMs + " 米每秒");
        }
        long outboundMs = Math.round(outboundMsExact);
        long handoverMs = handoverSec * 1000L;
        long returningStartMs = meetMs + handoverMs;
        // 倒推的起飞时刻可能早于下单时刻（「下单即临期」）；这里只保证它是正的 Unix 毫秒值，
        // 真正落库时刻由 auditTs 按「已发生的下界」再钳一次。
        long takeoffMs = Math.max(1L, meetMs - outboundMs);
        return List.of(
                new Transition(OrderStatus.DISPATCHED, OrderStatus.OUTBOUND,
                        "起飞确认", takeoffMs),
                new Transition(OrderStatus.OUTBOUND, OrderStatus.HANDOVER,
                        "进入末段交接", meetMs),
                new Transition(OrderStatus.HANDOVER, OrderStatus.RETURNING,
                        "交接完成", returningStartMs),
                new Transition(OrderStatus.RETURNING, OrderStatus.FINISHED,
                        "落巢完成", returningStartMs + outboundMs));
    }

    /**
     * 审计时刻钳制：取理论到期时刻，但不得早于「已发生的下界」——<b>时间不得倒挂</b>。
     *
     * <p>为什么需要钳制：迁移时刻是按 {@code meet_time} 倒推的，而订单可能「下单即临期」
     * （去程时长大于下单到会面的剩余时间），此时倒推的起飞时刻早于下单时刻；位置驱动提前
     * 切入又用当前时刻写审计、随后时间轴仍按 {@code meet_time} 取值。两种情形都会让
     * {@code task_log.ts} 与 {@code log_id} 顺序相反，按时间排序的回放与按主键取最新的
     * 「最后阶段」互相矛盾。
     *
     * @param dueMs   理论到期时刻，Unix 毫秒
     * @param floorMs 已发生的下界（下单时刻或本周期上一条审计时刻）；无下界传 0
     * @return 严格大于 {@code floorMs} 且不小于 {@code dueMs} 的时刻
     */
    static long auditTs(long dueMs, long floorMs) {
        return dueMs > floorMs ? dueMs : floorMs + 1L;
    }

    /**
     * 一段飞行迁移。
     *
     * @param from   变更前状态
     * @param to     变更后状态
     * @param opType 审计操作类型
     * @param dueMs  应发生时刻，Unix 毫秒
     */
    record Transition(OrderStatus from, OrderStatus to, String opType, long dueMs) {

        Transition {
            if (from == null || to == null || opType == null || opType.isBlank()) {
                throw new IllegalArgumentException("飞行迁移的状态与操作类型不得为空");
            }
        }
    }
}
