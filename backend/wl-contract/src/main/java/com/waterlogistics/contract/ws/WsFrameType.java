package com.waterlogistics.contract.ws;

/**
 * WebSocket 帧类型——依据《22-接口契约》v1.2 §4。
 *
 * <p>{@code type} 决定前端走哪套渲染逻辑；与《36-M5-可视化》§3 映射表一一对应。
 */
public enum WsFrameType {

    /** 船舶未来轨迹点列 ＋ 不确定性，前端画虚线轨迹与半透明置信椭圆 */
    SHIP_TRAJ("ship_traj"),

    /** 无人机位置、电量、飞行阶段，前端画机位图标与状态卡 */
    DRONE_STATE("drone_state"),

    /** 订单、会面点经纬度、是否锁定，前端画十字准星（锁定后加边框高亮） */
    RENDEZVOUS("rendezvous"),

    /** 级别、类型、目标、提示语，前端实体红色闪烁并追加事件流 */
    ALARM("alarm"),

    /**
     * 订单状态推送：所有订单状态变化统一由该 {@code type} 推送，不再按状态分设消息。
     * 写入方为 M2（状态确认后写库再向对应授权终端推送）。
     */
    ORDER_STATE("order_state");

    private final String type;

    WsFrameType(String type) {
        this.type = type;
    }

    /** 返回线上传输用的帧类型字符串 */
    public String getType() {
        return type;
    }

    /** 按帧类型字符串解析枚举 */
    public static WsFrameType fromType(String type) {
        for (WsFrameType frameType : values()) {
            if (frameType.type.equals(type)) {
                return frameType;
            }
        }
        throw new IllegalArgumentException("未知 WS 帧类型：" + type);
    }
}
