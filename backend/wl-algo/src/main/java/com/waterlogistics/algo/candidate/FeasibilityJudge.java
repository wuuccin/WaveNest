package com.waterlogistics.algo.candidate;

import java.util.ArrayList;
import java.util.List;

/**
 * C 组·候选组合可行性判断（简化规则版，10-16 · 功能 4）。
 *
 * <p>口径（《10-16 并行任务》杨添爻条目）：【简化规则，不做精算剪枝】——
 * 两关剪枝（追得上 / 回得来）留接口、实现顺延；本类只做"规则闸"，
 * 给 dispatch 侧一个可直接用的判断结果＋逐条原因（可解释性优先）。
 *
 * <p>规则（全部参数注入，不硬编码）：
 * <ol>
 *   <li>风况闸：现场风速（取最不利）≤ 风速上限；</li>
 *   <li>电量闸：初估往返能耗后的余量 ≥ 最低保留——
 *       初估能耗＝往返距离 × 单位距离耗电（百分比/km）＋ 载荷附加；</li>
 *   <li>状态闸：组合须来自候选生成（本类不重复校验状态，生成端已滤）。</li>
 * </ol>
 * 纯 Java、无 Spring/DB——与 {@link CandidateGenerator} 同层，可独立单测。
 */
public class FeasibilityJudge {

    /** 判定结论 */
    public enum Verdict { FEASIBLE, INFEASIBLE }

    /** 判定结果：结论＋逐条原因（空列表＝全部通过） */
    public record FeasibilityResult(
            String comboId,
            Verdict verdict,
            /** 不通过原因（简化版用中文短句，直接可贴事件流/日志） */
            List<String> reasons) {

        public boolean feasible() {
            return verdict == Verdict.FEASIBLE;
        }
    }

    /** 判定参数（正式路径读 sys_param，由调用方注入） */
    public record Params(
            /** 风速上限（m/s，取最不利风况后比对） */
            double maxWindMs,
            /** 电量最低保留（%，判定通过后仍须余出的量） */
            double reserveBatteryPercent,
            /** 单位距离耗电（%/km，往返粗估用，演示标定值） */
            double batteryPerKmPercent,
            /** 载荷附加耗电（%/kg） */
            double batteryPerKgPercent) {

        public static Params defaults() {
            // 演示标定：上限 10 m/s（对应 wind_safety 反向标定的简化），耗电 1.5%/km、0.2%/kg、保留 20%
            return new Params(10.0, 20.0, 1.5, 0.2);
        }
    }

    /**
     * 判定一个候选组合的可行性（简化规则）。
     *
     * @param combo        候选生成输出
     * @param distKm       本次任务单程距离（km，调用方按取货点→交货点或机巢→取货点口径传入）
     * @param worstWindMs  现场最不利风速（m/s；首版实测代偿，取自 data.weather 按位置取最不利）
     * @param payloadKg    订单载荷（kg，载荷附加耗电用）
     * @param params       判定参数
     */
    public FeasibilityResult judge(CandidateGenerator.CandidateCombo combo,
                                   double distKm,
                                   double worstWindMs,
                                   double payloadKg,
                                   Params params) {
        List<String> reasons = new ArrayList<>();

        // 1) 风况闸（首版实测代偿：本值已含"以实测代偿预报"的口径，wind_safety 误差项不在此重复计）
        if (worstWindMs > params.maxWindMs()) {
            reasons.add(String.format("风况超限：最不利风 %.1f m/s > 上限 %.1f m/s",
                    worstWindMs, params.maxWindMs()));
        }

        // 2) 电量闸（往返粗估：去程 dist + 回程 dist，简化按同距离）
        double roundTripKm = 2.0 * distKm;
        double consume = roundTripKm * params.batteryPerKmPercent()
                + payloadKg * params.batteryPerKgPercent();
        double remaining = combo.battery() - consume;
        if (remaining < params.reserveBatteryPercent()) {
            reasons.add(String.format(
                    "电量不足：%.0f%% − 往返 %.1fkm × %.1f%%/km − 载荷附加 ≈ 余 %.0f%% < 保留 %.0f%%",
                    combo.battery(), roundTripKm, params.batteryPerKmPercent(),
                    remaining, params.reserveBatteryPercent()));
        }

        return new FeasibilityResult(combo.comboId(),
                reasons.isEmpty() ? Verdict.FEASIBLE : Verdict.INFEASIBLE,
                reasons);
    }
}
