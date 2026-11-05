package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.ws.ShipTrajFrame;

/**
 * 船舶预测快照刷新完成事件。
 *
 * <p>事件携带 M2 已完成计算并登记版本的快照；推送层只取其契约投影，
 * 不重复预测或从 AIS 原始观测自行拼轨迹。
 *
 * <p><b>载荷不可变（LOW.21）</b>：本事件是<b>跨线程</b>发布的——{@code publishEvent} 在
 * 预测刷新的计算锁内同步调用，而监听方（{@code RealtimeEventBridge}）之后在自己的线程上
 * 序列化。若帧与生产方共享同一个 {@code points} 列表，生产方后续复用／改写该列表，
 * 监听方就会序列化出一版与「已登记版本号」不符的轨迹，而按版本判新旧的下游对此毫无察觉。
 * 故在构造期重建一份帧（{@code ShipTrajFrame} 的紧凑构造器内部再做 {@code List.copyOf}），
 * 让事件持有的内容与发布时刻严格一致。
 *
 * <p>不在这里做「点列非空」校验：那是 {@link PredictionSnapshot#toFrame()} 的职责
 * （空点列不发帧）。本类只保证「拿到的帧以后不会被别人改」。
 */
public record PredictionUpdatedEvent(ShipTrajFrame frame) {

    public PredictionUpdatedEvent {
        if (frame == null) {
            throw new IllegalArgumentException("ship trajectory frame is required");
        }
        if (frame.points() == null) {
            throw new IllegalArgumentException(
                    "ship trajectory frame must carry points（无预测应不发事件）：mmsi=" + frame.mmsi());
        }
        // 重建一份：即使生产方事后改了自己那个 List，事件里的内容也不会跟着变
        frame = new ShipTrajFrame(frame.mmsi(), frame.version(), frame.t0(), frame.courseDeg(),
                frame.validitySec(), frame.premise(), frame.points());
    }
}
