package com.waterlogistics.contract.rest;

import java.util.List;

/**
 * 派单闸门状态摘要——依据《22-接口契约》v1.2 §3.2。
 *
 * <p>响应级字段，出现在 {@code GET /api/orders} 与 {@code POST /api/intervene} 的响应里。
 * 由 M2 后端读取，M5 不自建第二个冻结开关、不自行判断气象是否熔断；WS 不另设闸门消息。
 *
 * @param gateStatus  闸门状态：{@link GateStatus}
 * @param gateSources 熔断来源列表：{@link GateSource}；正常时为空数组
 */
public record DispatchGate(GateStatus gateStatus, List<GateSource> gateSources) {

    /** 闸门是否处于熔断中 */
    public boolean isBroken() {
        return gateStatus == GateStatus.BROKEN;
    }
}
