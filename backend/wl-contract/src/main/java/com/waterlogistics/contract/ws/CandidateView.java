package com.waterlogistics.contract.ws;

import java.util.List;

/**
 * 候选对比视图——会面帧里「逐条候选」的结构化透出，依据《22-接口契约》v1.2 §4。
 *
 * <p><b>为什么新开一个 record，而不直接把 {@code CandidateVerdict} 放进帧</b>：
 * 判定结果住在实现层（{@code wl-m2-dispatch/feasibility}），契约层不能反向依赖实现层，
 * 否则 {@code wl-contract} 会出现「契约 → 实现」的倒挂。故由帧投影器做一次映射，
 * 契约层只认本 record。
 *
 * <p><b>字段名与前端对齐，不可自行改名</b>：前端
 * {@code frontend/src/realtime/candidateAssessment.js} 已按下列名解析（10-29 彭紫情已合入），
 * 改名即对比卡失效：
 * <ul>
 *   <li>{@code candidateId}——列表 key，前端首选字段（缺失才退化成 droneId/nestId）；</li>
 *   <li>{@code selected}——是否中选，前端读 {@code selected}，兼容别名 {@code chosen}；</li>
 *   <li>{@code feasible}——布尔，不是枚举（前端按布尔判定「置灰 / 高亮」）。</li>
 * </ul>
 *
 * @param candidateId 候选标识（取判定端 {@code comboId}，不另编流水号）
 * @param model       机型
 * @param nestId      机巢
 * @param droneId     无人机
 * @param distKm      机巢到取货点距离（km，粗估直线）；组合缺失导致上游为 NaN 时为 {@code null}
 *                    ——<b>不用 NaN 出库</b>：NaN 不是合法 JSON，会让前端整帧解析失败
 * @param feasible    是否可行（等价于判定端 {@code verdict == FEASIBLE}）
 * @param reasons     逐条淘汰原因；可行者为空列表
 * @param selected    是否中选（本轮被选中的组合）
 */
public record CandidateView(

        String candidateId,

        String model,

        String nestId,

        String droneId,

        Double distKm,

        boolean feasible,

        List<String> reasons,

        boolean selected) {

    public CandidateView {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
