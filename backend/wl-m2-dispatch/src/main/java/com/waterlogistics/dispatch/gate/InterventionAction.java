package com.waterlogistics.dispatch.gate;

import com.waterlogistics.contract.state.OrderStatus;

import java.util.List;
import java.util.Locale;

/**
 * 人工干预动作表（M2 · 序号 3）——八个归一 action 的<b>唯一定义处</b>。
 *
 * <p>取值、中文按钮原文与错误码见《1112-功能做实批次｜接口约定》§3.2、§3.3。
 * 前端发中文原文、后端收归一英文 action，映射集中在本枚举，避免控制器与订单侧
 * 实现各维护一份而漂移。
 *
 * <p>订单级动作的目标状态只取《23-状态机定义》§3 已定义的十二态，不新增状态词；
 * 三个终止态互斥、进入后不再流转。{@code MANUAL} 落「已拒绝（转人工）」按《23》§3
 * 四对一映射表；{@code ABORT} 在飞叫停落「异常终止」；{@code WITHDRAW}／{@code CANCEL}
 * 落「已拒绝」。
 *
 * <p>{@code adjust_meet} 不改订单状态：请求体不含新会面点坐标，本批只做校验与
 * 干预留痕，航迹重算由序号 3 第二批接主链时挂接，不伪造 6a 航向跳变信号。
 */
enum InterventionAction {

    TRIP("trip", "冻结派单", false, null,
            List.of("冻结派单", "冻结")),

    CLEAR("clear", "解除冻结", false, null,
            List.of("解除冻结", "解冻", "解除人工冻结")),

    RESET("reset", "重置闸门", false, null,
            List.of("重置", "复位")),

    ADJUST_MEET("adjust_meet", "调整会面点", true, null,
            List.of("调整会面点")),

    WITHDRAW("withdraw", "撤回未起飞订单", true, OrderStatus.REJECTED,
            List.of("撤回未起飞订单")),

    CANCEL("cancel", "取消订单", true, OrderStatus.REJECTED,
            List.of("取消订单")),

    ABORT("abort", "紧急叫停", true, OrderStatus.ABORTED,
            List.of("紧急叫停")),

    MANUAL("manual", "人工接管", true, OrderStatus.REJECTED,
            List.of("人工接管"));

    /** §3.3 未知 action 错误文案中的支持列表，顺序与 §3.2 动作表一致。 */
    static final String SUPPORTED = "trip/clear/reset/adjust_meet/withdraw/cancel/abort/manual";

    private final String action;
    private final String label;
    private final boolean requiresOrderId;
    private final OrderStatus targetStatus;
    private final List<String> chineseAliases;

    InterventionAction(String action, String label, boolean requiresOrderId,
            OrderStatus targetStatus, List<String> chineseAliases) {
        this.action = action;
        this.label = label;
        this.requiresOrderId = requiresOrderId;
        this.targetStatus = targetStatus;
        this.chineseAliases = chineseAliases;
    }

    /** 前端原始值归一：先精确匹配中文别名或英文 action，再按 {@code Locale.ROOT} 小写兜底。 */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.trim();
        for (InterventionAction candidate : values()) {
            if (candidate.chineseAliases.contains(trimmed) || candidate.action.equals(trimmed)) {
                return candidate.action;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** 按归一后的 action 查表；未命中返回 {@code null}，由调用方按 §3.3 回 400。 */
    static InterventionAction fromNormalized(String normalized) {
        for (InterventionAction candidate : values()) {
            if (candidate.action.equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /** 该动作是否允许在给定订单状态下执行；闸门级动作恒允许（不看订单状态）。 */
    boolean allows(OrderStatus status) {
        return switch (this) {
            case TRIP, CLEAR, RESET -> true;
            case WITHDRAW -> status == OrderStatus.PENDING_ACCEPT
                    || status == OrderStatus.ACCEPTED_WAIT_WAKE
                    || status == OrderStatus.WAIT_DISPATCH
                    || status == OrderStatus.DISPATCHING
                    || status == OrderStatus.DISPATCHED;
            case ABORT -> status == OrderStatus.OUTBOUND
                    || status == OrderStatus.HANDOVER
                    || status == OrderStatus.RETURNING;
            case ADJUST_MEET, CANCEL, MANUAL -> status != null
                    && !status.isTerminal()
                    && status != OrderStatus.FINISHED;
        };
    }

    String action() {
        return action;
    }

    String label() {
        return label;
    }

    boolean requiresOrderId() {
        return requiresOrderId;
    }

    /** {@code null} 表示保持原状态（只留痕，不改订单）。 */
    OrderStatus targetStatus() {
        return targetStatus;
    }
}
