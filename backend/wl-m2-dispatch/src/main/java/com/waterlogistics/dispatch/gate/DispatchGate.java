package com.waterlogistics.dispatch.gate;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 派单闸门（模块二 功能 12 关联；算法见 83 §2.5 / §6.2.4 熔断层）。
 * 维护闸门状态（正常/熔断中）与闸门来源（gateSources），供 /api/orders GET 回显。
 *
 * 字段名与取值待 22 §3.2 定稿（当前为「一键冻结派单的闸门状态回显字段」待定项）；
 * 本类先按编码分工表口径实现：dispatchGate = { gateStatus, gateSources }。
 */
@Service
public class DispatchGate {

    /** 闸门状态：正常 / 熔断中 */
    public static final String GATE_NORMAL = "正常";
    public static final String GATE_TRIPPED = "熔断中";

    /** 闸门来源示例：气象熔断 / 人工冻结 */
    private final List<String> gateSources = new CopyOnWriteArrayList<>();

    private volatile String gateStatus = GATE_NORMAL;

    /** 闸门是否放行（正常即放行） */
    public boolean isOpen() {
        return GATE_NORMAL.equals(gateStatus);
    }

    /** 回显：闸门状态 */
    public String getGateStatus() {
        return gateStatus;
    }

    /** 回显：闸门来源（关闭原因） */
    public List<String> getGateSources() {
        return List.copyOf(gateSources);
    }

    /** 熔断/冻结（闸门关闭），记来源 */
    public void trip(String source) {
        gateSources.add(source);
        gateStatus = GATE_TRIPPED;
    }

    /** 解除熔断（闸门恢复），清来源 */
    public void reset() {
        gateSources.clear();
        gateStatus = GATE_NORMAL;
    }
}
