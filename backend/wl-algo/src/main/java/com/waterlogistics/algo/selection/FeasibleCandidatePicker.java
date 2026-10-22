package com.waterlogistics.algo.selection;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.FeasibilityJudge;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选组合的<b>可行性择优</b>（C1 · 10-21 任务清单 · 卢肄文）。
 *
 * <p>解决的是「<b>选机段只选不判</b>」这个缺口：{@link CandidateGenerator} 只按距离升序产出候选，
 * {@link FeasibilityJudge} 能判单个组合能不能飞，但两者此前<b>没有生产调用方把它们串起来</b>——
 * 于是「风速一大就刷掉抗风不足的机型」只在单测里成立，真实派发链上照样会把 A 型机派出去。
 *
 * <p>本类做三件事，顺序固定：
 * <ol>
 *   <li><b>逐个判定</b>：对候选列表里每个组合调 {@link FeasibilityJudge#judge}，
 *       距离取组合自带的 {@link CandidateGenerator.CandidateCombo#distKm()}
 *       （机巢→取货点单程；判定内部按往返 2× 粗估），载荷取订单载荷；</li>
 *   <li><b>过滤不可飞</b>：风况闸／电量闸任一不过即淘汰，并保留逐条中文原因（可解释性优先）；</li>
 *   <li><b>择优</b>：在<b>可行</b>候选里取 {@link CandidateGenerator} 排好的第一个
 *       ——生成端已按距离升序，故「第一个可行者」即「最近的可飞候选」，
 *       不再另立一套排序口径（两套排序迟早不一致）。</li>
 * </ol>
 *
 * <h3>为什么把判定放在算法层而不是编排层</h3>
 * <p>规则本身（风况闸、电量闸、抗风上限取自机型）全在 {@link FeasibilityJudge}，
 * 本类只做「遍历＋过滤＋取首个」这一层纯函数组合。<b>无 Spring、无 DB、无随机</b>，
 * 因此可以直接用两个候选组合把「注入 11 m/s 后 M30T（抗风 10.0）被淘汰、M400（12.0）保留」
 * 这条验收判据钉死在单测里，而不必起数据库与消息队列。
 *
 * <h3>本类不做的事</h3>
 * <ul>
 *   <li><b>不取风况</b>：现场风由调用方传入（生产链上是
 *       {@code WeatherSnapshotStore.worstWindMsOrDefault}，见 {@code DispatchOrchestrator}）；</li>
 *   <li><b>不读参数表</b>：{@link FeasibilityJudge.Params} 由调用方按 {@code sys_param} 组装；</li>
 *   <li><b>不决定「判不出来怎么办」</b>：把所有组合都淘汰的情形如实返回
 *       （{@link Selection#found()} 为 {@code false}），由编排层决定是拒单还是延后。</li>
 * </ul>
 */
public final class FeasibleCandidatePicker {

    private static final FeasibilityJudge JUDGE = new FeasibilityJudge();

    private FeasibleCandidatePicker() {
    }

    /**
     * 一次择优的结果。
     *
     * @param chosen    被选中的候选；<b>没有可行候选时为 {@code null}</b>
     * @param evaluated 全部候选的判定结果（顺序与入参一致，供日志与断言追溯）
     */
    public record Selection(
            CandidateGenerator.CandidateCombo chosen,
            List<FeasibilityJudge.FeasibilityResult> evaluated) {

        public Selection {
            evaluated = List.copyOf(evaluated);
        }

        /** 是否选出了可飞候选。 */
        public boolean found() {
            return chosen != null;
        }

        /** 被淘汰的组合条数。 */
        public int rejectedCount() {
            return (int) evaluated.stream().filter(r -> !r.feasible()).count();
        }

        /**
         * 淘汰摘要（可直接进日志／异常消息）：每条一段「组合号：原因」。
         *
         * <p>没有可行候选时必须能用它回答「为什么一单也派不出去」，
         * 否则现场只会看到一句「无可用候选组合」，无从判断是风大、电少还是没机。
         */
        public String rejectionSummary() {
            if (evaluated.isEmpty()) {
                return "（候选列表为空：无机巢／无空闲无人机／全被生成端门槛滤掉）";
            }
            StringBuilder builder = new StringBuilder();
            for (FeasibilityJudge.FeasibilityResult result : evaluated) {
                if (result.feasible()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append("；");
                }
                builder.append(result.comboId()).append("：")
                        .append(String.join("／", result.reasons()));
            }
            return builder.length() == 0 ? "（全部可行）" : builder.toString();
        }
    }

    /**
     * 逐个判定后取最近的可飞候选。
     *
     * @param combos      候选组合（生成端已按距离升序；空列表合法，返回未选出）
     * @param worstWindMs 现场最不利风速，m/s（<b>不得传 0 来代替「没有风况」</b>）
     * @param payloadKg   订单载荷，kg（载荷附加耗电用）
     * @param params      判定参数（由调用方按 {@code sys_param} 组装）
     * @return 择优结果；无可行候选时 {@link Selection#found()} 为 {@code false}
     */
    public static Selection pick(List<CandidateGenerator.CandidateCombo> combos,
            double worstWindMs, double payloadKg, FeasibilityJudge.Params params) {
        if (params == null) {
            throw new IllegalArgumentException("判定参数不得为 null");
        }
        if (Double.isNaN(worstWindMs) || worstWindMs < 0.0) {
            throw new IllegalArgumentException("最不利风速必须为非负有限值：" + worstWindMs);
        }
        if (Double.isNaN(payloadKg) || payloadKg < 0.0) {
            throw new IllegalArgumentException("载荷必须为非负有限值：" + payloadKg);
        }
        if (combos == null || combos.isEmpty()) {
            return new Selection(null, List.of());
        }

        List<FeasibilityJudge.FeasibilityResult> evaluated = new ArrayList<>(combos.size());
        CandidateGenerator.CandidateCombo chosen = null;
        for (CandidateGenerator.CandidateCombo combo : combos) {
            FeasibilityJudge.FeasibilityResult result =
                    JUDGE.judge(combo, combo.distKm(), worstWindMs, payloadKg, params);
            evaluated.add(result);
            // 生成端按距离升序 ⇒ 第一个可行者即最近的可飞候选
            if (chosen == null && result.feasible()) {
                chosen = combo;
            }
        }
        return new Selection(chosen, evaluated);
    }
}
