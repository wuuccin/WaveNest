package com.waterlogistics.contract.rest;

import com.waterlogistics.contract.ws.AlarmFrame;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.contract.ws.ShipTrajFrame;
import com.waterlogistics.contract.ws.WsFrameType;
import java.util.List;

/**
 * 一次返回同一订单时间窗内按 eventTs 排序的多帧。taskId 为实际无人机号，orderId 为订单号。
 * 可返回 drone_state、order_state、alarm、plan_track、ship_traj；缺少可核验来源的帧直接省略。
 * ship_traj 只取真实预测事件在 Redis 的历史帧，不把 AIS 观测伪装成预测。
 * 未知帧类型由旧客户端跳过，已知帧依照各自的 WS 载荷字段解析。
 */
public record FlightReplayResponse(String taskId, String orderId, List<Frame> frames) {
    public FlightReplayResponse {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId is required");
        frames = List.copyOf(frames);
    }

    /** data 保持对应 WS 载荷；order_state 的 stateVersion 使用 task_log.log_id + 1。 */
    public record Frame(String type, Object data, long eventTs) {
        public Frame {
            if (type == null || type.isBlank() || data == null || eventTs < 0)
                throw new IllegalArgumentException("Replay frame is incomplete");
        }

        public static Frame drone(DroneStateFrame data) {
            return new Frame(WsFrameType.DRONE_STATE.getType(), data, data.ts());
        }
        public static Frame order(Object data, long eventTs) {
            return new Frame(WsFrameType.ORDER_STATE.getType(), data, eventTs);
        }
        public static Frame alarm(AlarmFrame data, long eventTs) {
            return new Frame(WsFrameType.ALARM.getType(), data, eventTs);
        }
        public static Frame plan(PlanTrackFrame data, long eventTs) {
            return new Frame(WsFrameType.PLAN_TRACK.getType(), data, eventTs);
        }
        public static Frame ship(ShipTrajFrame data, long eventTs) {
            return new Frame(WsFrameType.SHIP_TRAJ.getType(), data, eventTs);
        }
    }
}
