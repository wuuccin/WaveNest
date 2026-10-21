package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.ws.ShipTrajFrame;

/**
 * 船舶预测快照刷新完成事件。
 *
 * <p>事件携带 M2 已完成计算并登记版本的快照；推送层只取其契约投影，
 * 不重复预测或从 AIS 原始观测自行拼轨迹。
 */
public record PredictionUpdatedEvent(ShipTrajFrame frame) {

    public PredictionUpdatedEvent {
        if (frame == null) {
            throw new IllegalArgumentException("ship trajectory frame is required");
        }
    }
}
