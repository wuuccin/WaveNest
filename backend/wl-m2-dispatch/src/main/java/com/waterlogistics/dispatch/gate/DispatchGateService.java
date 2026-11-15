package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.rest.DispatchGate;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 派单闸门服务（模块二 功能 12 关联；算法见 83 §2.5 / §6.2.4 熔断层）。
 * 维护闸门开关与熔断来源，{@link #summary()} 回吐契约
 * {@code contract.rest.DispatchGate} record，供序 5 的 GET /api/orders
 * 与 POST /api/intervene 响应直接回显（22 §3.2）。
 *
 * 字段口径已随序 2 契约落定（此前待定项关闭）：gateStatus=GateStatus、
 * gateSources=GateSource 列表，落库传输一律取 getValue() 中文取值。
 *
 * <p>序号 3 起，本类同时是八个人工干预动作的唯一入口：三个闸门动作直接改状态，
 * 五个订单动作委托 {@link OrderInterventionService}。订单干预出口可空，
 * 以便只验闸门逻辑的单测继续用无参构造直接 {@code new}。
 *
 * <h3>并发口径（HIGH.10 / HIGH.11 / LOW.5）</h3>
 * 状态（status + 来源列表）<b>只由一把锁保护</b>：四个读写方法全部 {@code synchronized}，
 * 列表为普通 {@code ArrayList}（不再混用 CopyOnWrite，避免「两套保护机制 + 读路径无保护」
 * 的快照不一致）。{@link #isOpen()} 只读单个 {@code volatile} 字段，不参与组合读，保持无锁。
 * 五个订单动作不读写闸门状态，刻意不加 {@code synchronized}，避免把数据库事务圈进闸门锁。
 */
@Service
public class DispatchGateService {

    private final List<GateSource> gateSources = new ArrayList<>();

    private volatile GateStatus gateStatus = GateStatus.NORMAL;

    private final OrderInterventionService orderInterventionService;

    /** 单测与仅用闸门的装配：不带订单干预出口。 */
    public DispatchGateService() {
        this(null);
    }

    /**
     * 生产装配：注入订单干预出口。{@code @Lazy} 断开「闸门 ↔ 订单服务 ↔ 预测链」
     * 可能形成的构造环，与 {@code RendezvousRecomputeRequester} 的装配口径一致。
     */
    @Autowired
    public DispatchGateService(@Lazy OrderInterventionService orderInterventionService) {
        this.orderInterventionService = orderInterventionService;
    }

    /** 闸门是否放行（正常即放行） */
    public boolean isOpen() {
        return gateStatus == GateStatus.NORMAL;
    }

    /**
     * 回显摘要：契约 DispatchGate（正常时 gateSources 为空数组）。
     *
     * <p>与 {@link #trip}/{@link #clear}/{@link #reset} 共用同一把锁，保证
     * 「状态 + 来源列表」成对读取，不会出现「BROKEN + 空列表」或「NORMAL + 有来源」的撕裂快照。
     */
    public synchronized DispatchGate summary() {
        return new DispatchGate(gateStatus, List.copyOf(gateSources));
    }

    /**
     * 熔断/冻结（闸门关闭），记来源；重复来源不重复记。
     *
     * <p><b>MEDIUM.20</b>：{@code source} 为空直接失败。历史实现会把 {@code null} 写进列表，
     * 之后 {@link #summary()} 的 {@code List.copyOf} 抛 NPE，把整份闸门状态毒化到 reset 为止。
     */
    public synchronized void trip(GateSource source) {
        Objects.requireNonNull(source, "source");
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
        Objects.requireNonNull(source, "source");
        gateSources.removeIf(s -> s == source);
        if (gateSources.isEmpty()) {
            gateStatus = GateStatus.NORMAL;
        }
    }

    /**
     * 解除人工冻结（演示用）。
     *
     * <p><b>HIGH.12</b>：只移除 {@link GateSource#MANUAL}，<b>保留</b>{@link GateSource#AUTO}。
     * 历史实现无条件清空全部来源，会把气象自动熔断一并抹掉——{@code WeatherGateTrigger}
     * 本地标记此时仍为「已熔断」，不会重新置位，结果是大风天闸门被放开且持续放开。
     * 闸门状态由「剩余来源」派生，而不是强行置 NORMAL。
     */
    public synchronized void reset() {
        gateSources.removeIf(s -> s == GateSource.MANUAL);
        gateStatus = gateSources.isEmpty() ? GateStatus.NORMAL : GateStatus.BROKEN;
    }

    /**
     * 调整会面点：按订单当前最新预测重算会面点（《1112》§3.2），不改订单状态。
     *
     * @return {@code null} 表示会面点已改成功；非空为未生效原因（供 §3.4 {@code message} 回吐）
     */
    public String adjustMeet(String orderId) {
        return delegate("adjust_meet", orderId);
    }

    /** 撤回未起飞订单：仅 OUTBOUND 之前的五个状态可执行，落「已拒绝」。 */
    public void withdraw(String orderId) {
        delegate("withdraw", orderId);
    }

    /** 取消订单：任一未终止、未完成状态可执行，落「已拒绝」。 */
    public void cancel(String orderId) {
        delegate("cancel", orderId);
    }

    /** 紧急叫停：仅去程中／末段交接中／返航中可执行，落「异常终止」。 */
    public void emergencyAbort(String orderId) {
        delegate("abort", orderId);
    }

    /** 人工接管：按《23》§3 四对一映射表落「已拒绝（转人工）」。 */
    public void manualTakeover(String orderId) {
        delegate("manual", orderId);
    }

    private String delegate(String action, String orderId) {
        if (orderInterventionService == null) {
            throw new IllegalStateException("订单干预出口未装配：" + action);
        }
        return orderInterventionService.execute(action, orderId);
    }
}
