package com.waterlogistics.contract.ws;

/**
 * WebSocket 统一信封——依据《22-接口契约》v1.2 §4，形如 {@code {"type":"...","data":{...}}}。
 *
 * @param <T> 载荷类型，取决于 {@link WsFrameType}
 */
public record WsEnvelope<T>(

        /** 帧类型字符串，见 {@link WsFrameType#getType()} */
        String type,

        /** 帧载荷 */
        T data) {

    /**
     * 按帧类型构造信封，避免手写 type 字符串。
     *
     * @param frameType 帧类型
     * @param data      帧载荷
     */
    public static <T> WsEnvelope<T> of(WsFrameType frameType, T data) {
        return new WsEnvelope<>(frameType.getType(), data);
    }
}
