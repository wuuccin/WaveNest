package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 派单闸门服务（模块二 功能 12 关联；算法见 83 §2.5 / §6.2.4 熔断层）。
 * 维护闸门开关与熔断来源，{@link #summary()} 回吐契约
 * {@code contract.rest.DispatchGate} record，供序 5 的 GET /api/orders
 * 与 POST /api/intervene 响应直接回显（22 §3.2）。
 *
 * 字段口径已随序 2 契约落定（此前待定项关闭）：gateStatus=GateStatus、
 * gateSources=GateSource 列表，落库传输一律取 getValue() 中文取值。
 */
@Service
public class DispatchGateService {

    private final List<GateSource> gateSources = new CopyOnWriteArrayList<>();

    private volatile GateStatus gateStatus = GateStatus.NORMAL;

    /** 闸门是否放行（正常即放行） */
    public boolean isOpen() {
        return gateStatus == GateStatus.NORMAL;
    }

    /** 回显摘要：契约 DispatchGate（正常时 gateSources 为空数组） */
    public DispatchGate summary() {
        return new DispatchGate(gateStatus, List.copyOf(gateSources));
    }

    /** 熔断/冻结（闸门关闭），记来源；重复来源不重复记 */
    public synchronized void trip(GateSource source) {
        if (gateSources.stream().noneMatch(s -> s == source)) {
            gateSources.add(source);
        }
        gateStatus = GateStatus.BROKEN;
    }

    /**
     * 解除一个来源；全部解除后闸门恢复（22 §3.2：人力冻结不覆盖自动熔断，
     * 是否恢复派单由 M2 确认——演示阶段按"清空即恢复"处理）。
     */
    public synchronized void clear(GateSource source) {
        gateSources.removeIf(s -> s == source);
        if (gateSources.isEmpty()) {
            gateStatus = GateStatus.NORMAL;
        }
    }

    /** 一键复位（演示用） */
    public synchronized void reset() {
        gateSources.clear();
        gateStatus = GateStatus.NORMAL;
    }
}
