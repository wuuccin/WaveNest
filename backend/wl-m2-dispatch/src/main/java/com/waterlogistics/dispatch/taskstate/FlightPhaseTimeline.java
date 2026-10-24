package com.waterlogistics.dispatch.taskstate;

import com.waterlogistics.contract.state.OrderStatus;
import java.util.List;

/**
 * 飞行阶段时间表——纯算术，不读库、不写日志，便于单测与复用。
 *
 * <p>起飞与会面之间按「机巢到会面点距离 ÷ 巡航速度」估去程时长；
 * 交接持续 {@code tHandoverMinSec}；返程沿用去程时长。
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
     * @throws IllegalArgumentException 距离、速度或会面时刻非法
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
        long outboundMs = Math.round(outboundM / cruiseSpeedMs * 1000.0);
        long handoverMs = handoverSec * 1000L;
        long returningStartMs = meetMs + handoverMs;
        return List.of(
                new Transition(OrderStatus.DISPATCHED, OrderStatus.OUTBOUND,
                        "起飞确认", meetMs - outboundMs),
                new Transition(OrderStatus.OUTBOUND, OrderStatus.HANDOVER,
                        "进入末段交接", meetMs),
                new Transition(OrderStatus.HANDOVER, OrderStatus.RETURNING,
                        "交接完成", returningStartMs),
                new Transition(OrderStatus.RETURNING, OrderStatus.FINISHED,
                        "落巢完成", returningStartMs + outboundMs));
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
