package com.waterlogistics.contract.order;

import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.OrderStatus;

/**
 * 订单展示视图——REST 快照与 WS {@code order_state} 帧共用的同一份字段。
 *
 * <p>依据《22-接口契约》v1.2 §3.1，并在 §4 {@code order_state} 中同步。
 * 两处共用同一对象，避免出现「REST 一套字段、WS 另一套」的分歧。
 *
 * @param orderStatus           订单状态（12 态），显示后台真实状态，不由遥测或告警推断
 * @param eventTs               最近一次状态或展示数据变化的发生时刻，Unix 毫秒
 * @param stateVersion          每订单递增版本号；REST 快照与 WS 共用，重连时按版本合并，
 *                              只应用更高版本，拒绝旧消息覆盖
 * @param waitReason            仅"已受理待唤醒"有值，其余 null
 * @param wakeEtaTs             预计唤醒时刻，Unix 毫秒；无法估计时为 null。
 *                              前端只格式化展示，不据此生成倒计时、不自行唤醒、不改订单状态
 * @param emergencyLandingPoint 失败时的实际迫降坐标，来源为已确认的
 *                              {@code emergency_landing_point} 记录；缺失时不得用最后已知机位冒充
 * @param handoverPhase         末段子阶段（接近／匹配／交接／脱离），不新增订单状态
 */
public record OrderView(

        String orderId,

        OrderStatus orderStatus,

        long eventTs,

        long stateVersion,

        WaitReason waitReason,

        Long wakeEtaTs,

        GeoPoint emergencyLandingPoint,

        String handoverPhase) {
}
