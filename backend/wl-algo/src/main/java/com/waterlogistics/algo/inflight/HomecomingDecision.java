package com.waterlogistics.algo.inflight;

import com.waterlogistics.contract.rest.GeoPoint;
import java.util.List;

/**
 * 功能10 三级落点结论，字段照《84-E组算法方案》§4.2 输出表。
 *
 * <p>§4.2 原文：「三级落点结论｜去向 <b>B 组</b>（生成返航航迹 ＋ 精算返程电量）｜
 * 回原巢 ／ 迫停他巢（机位标识）／ 迫降野外（点标识）」；
 * 「占位请求（<b>仅迫停他巢时</b>）｜D 组」。
 *
 * <p>本类把「占位请求只在第二级发出」这条口径<b>钉在构造器里</b>——
 * needSlotRequest 为真却不是第二级（或缺目标巢标识）时直接拒绝构造，
 * 避免编排层漏判把占位请求发到第一级或第三级去。
 *
 * @param tier 三级落点级别，声明顺序即降级顺序
 * @param targetId 目标标识（巢标识或迫降点标识）；<b>第三级待本组 L4 迫降点层出</b>
 *                        （《84》§7.6 第 7 步：台账候选 → 否决筛除 → 可达性 → 出迫降点 ID），
 *                        未接 L4 台账时为 null，<b>不得由 B 组代出</b>——B 组只生成航迹并精算返程电量
 * @param targetPosition 目标坐标；第三级同 {@code targetId}，未出点时为 null
 * @param needSlotRequest 是否需向 D 组发起归巢时隙占位请求；<b>仅第二级可为 true</b>
 * @param candidateAudit 候选他巢逐项打叉记录（4.5 D3 可审计）；第一级时为空列表
 * @param riskFlags 风险标记（如通信无覆盖）；<b>只标记不否决</b>（《84》§7.4）
 * @param reason 判定说明（落库与审计用）
 */
public record HomecomingDecision(
        HomecomingTier tier,
        String targetId,
        GeoPoint targetPosition,
        boolean needSlotRequest,
        List<NestCheckResult> candidateAudit,
        List<String> riskFlags,
        String reason) {

    /** 风险标记：通信无覆盖。按《84》§7.4「作风险标记，不作否决」处理。 */
    public static final String RISK_COMM_UNCOVERED = "COMM_UNCOVERED";

    public HomecomingDecision {
        if (tier == null) {
            throw new IllegalArgumentException("tier 不可为空：三级落点结论必须有级别");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不可为空：落点结论要能落库与审计");
        }
        if (needSlotRequest && tier != HomecomingTier.FORCE_STOP_OTHER_NEST) {
            throw new IllegalArgumentException("占位请求只在第二级「迫停他巢」发出，当前级别=" + tier.getValue());
        }
        if (needSlotRequest && (targetId == null || targetId.isBlank())) {
            throw new IllegalArgumentException("第二级须带 targetId（机位标识），否则 D 组无从占位");
        }
        candidateAudit = candidateAudit == null ? List.of() : List.copyOf(candidateAudit);
        riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
    }

    /** 第一级／第三级不出占位请求——给编排层的便捷判定 */
    public boolean needsNoSlotRequest() {
        return !needSlotRequest;
    }
}