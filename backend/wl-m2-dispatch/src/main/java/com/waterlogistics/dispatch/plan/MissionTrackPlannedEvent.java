package com.waterlogistics.dispatch.plan;

import com.waterlogistics.contract.ws.PlanTrackFrame;

/**
 * 规划航迹已装配事件。M2 在派发事务内发布，应用层在事务提交后转成 WebSocket 帧。
 *
 * @param frame 规划航迹帧
 */
public record MissionTrackPlannedEvent(PlanTrackFrame frame) {
}
