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
 *   <li>电量闸：初估<b>往返</b>能耗后的余量 ≥ 最低保留——
 *       往返里程＝{@code 2×(取↔交单程 + 巢→取单程)}（机巢段由组合透传），
 *       能耗＝往返里程 × 单位距离耗电（百分比/km）＋ 载荷附加；</li>
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
            /**
             * 风速上限（m/s）——<b>仅作缺省回落</b>：组合未登记机型抗风上限时用它兜底，
             * 并给出「未登记、按统一上限判」的原因。
             *
             * <p>有登记值时必须用机型值——否则「同一风速下 A 型淘汰、B 型保留」不成立（10-20 B1）。
             */
            double maxWindMs,
            /** 电量最低保留（%，判定通过后仍须余出的量） */
            double reserveBatteryPercent,
            /** 单位距离耗电（%/km，往返粗估用，演示标定值） */
            double batteryPerKmPercent,
            /** 载荷附加耗电（%/kg） */
            double batteryPerKgPercent) {

        public static Params defaults() {
            // 演示标定：回落上限 10 m/s（对应 wind_safety 反向标定的简化），耗电 1.5%/km、0.2%/kg、保留 20%
            return new Params(10.0, 20.0, 1.5, 0.2);
        }
    }

    /**
     * 判定一个候选组合的可行性（简化规则）。
     *
     * @param combo        候选生成输出（<b>自带机型抗风上限</b>，见 {@link CandidateGenerator.CandidateCombo#windLimitMs()}）
     * @param distKm       本次任务主航段单程距离（km，取货点→交货点，调用方按订单给；
     *                     机巢→取货段由 {@link CandidateGenerator.CandidateCombo#distKm()} 提供，本方法自动计入）
     * @param worstWindMs  现场最不利风速（m/s；首版实测代偿，取自 data.weather 按位置取最不利）
     * @param payloadKg    订单载荷（kg，载荷附加耗电用）
     * @param params       判定参数
     * @throws IllegalArgumentException {@code distKm}／{@code worstWindMs}／{@code payloadKg} 非有限或为负，
     *         或 {@code combo} 为 {@code null}、{@code combo.distKm()} 非有限或为负
     *         （评审 HIGH.8：NaN 参与 {@code >}／{@code <} 比较恒为 false，
     *         会让风况闸与电量闸同时失效、全部候选静默判 FEASIBLE）
     */
    public FeasibilityResult judge(CandidateGenerator.CandidateCombo combo,
                                   double distKm,
                                   double worstWindMs,
                                   double payloadKg,
                                   Params params) {
        // 0) 入口非有限值守卫——判定层的最后一道防线，不依赖调用方自觉（评审 HIGH.8）
        requireFiniteNonNegative("distKm", distKm);
        requireFiniteNonNegative("worstWindMs", worstWindMs);
        requireFiniteNonNegative("payloadKg", payloadKg);
        if (combo == null) {
            throw new IllegalArgumentException("combo 不能为 null");
        }
        // MED-BUG.12：distKm 只要求「非负有限」，NaN 会被这里拦下；电量/载荷
        // 在下面各自那一闸里单独判（NaN 有明确业务含义＝未登记，不是坏参数）。
        requireFiniteNonNegative("combo.distKm", combo.distKm());

        List<String> reasons = new ArrayList<>();

        // 1) 风况闸：机型抗风等级 ≥ 任务区最不利风级（62 判据 windclass_match）
        //
        //    抗风上限取「机型」值（随组合透传），不是调用方传的统一阈值——
        //    这是「同一风速下 A 型淘汰、B 型保留」能成立的唯一前提（10-20 B1）。
        //    统一上限 maxWindMs 退居两用：①未登记机型时的回落；②现场整体禁飞的上界。
        double windLimit = combo.windLimitMs();
        boolean registered = Double.isFinite(windLimit) && windLimit > 0;
        if (registered) {
            if (worstWindMs > windLimit) {
                reasons.add(String.format("机型抗风不足：最不利风 %.1f m/s > %s 机型抗风上限 %.1f m/s",
                        worstWindMs, combo.type(), windLimit));
            }
        } else if (worstWindMs > params.maxWindMs()) {
            // 未登记时不能默默放行，也不能按 0 拦死——按统一上限判，并在原因里写明是回落口径。
            // 该上限由调用方给「保守值」（已登记机型最小值与预警阈值的较小者），
            // 不再是气象熔断阈值 12.0（评审 MEDIUM.16：原口径下"未登记"反而最宽松）
            reasons.add(String.format(
                    "风况超限：最不利风 %.1f m/s > 上限 %.1f m/s"
                            + "（机型抗风未登记 drone.wind_limit_ms，按保守口径判、未按最宽松值放行）",
                    worstWindMs, params.maxWindMs()));
        }

        // 2) 电量闸（三段里程估算：巢→取货→交货 + 交货→巢）
        //    <p>taskDistKm 是调用方传的「取货点→交货点」单程；<b>机巢→取货段由
        //    combo 自带的 distKm 提供</b>（CandidateGenerator 用 haversine 算好的
        //    「巢→货点」距离）。真实航程 = 巢→取 + 取→交 + 交→巢。
        //    交货→巢一段没有交货点坐标无法精确计算，按<b>保守上界</b>取
        //    「巢→取 + 取→交」（把交货点视作在取货点外侧 taskDistKm 处）——
        //    宁可多算能耗，不漏算。<b>此前只算 2×taskDistKm，机巢→取货的往返整段
        //    漏掉，电量闸系统性偏乐观（Open CodeReview 点出、已修）</b>。
        //    后续 51 清单 #19（航迹生成接入派发）后改为按真实任务航迹累计里程。
        Legs legs = legsOf(combo, distKm);
        double consume = legs.totalKm() * params.batteryPerKmPercent()
                + payloadKg * params.batteryPerKgPercent();
        // ★ 11-05（MED-BUG.12 配套）：电量未登记（NaN）时不做减法。
        // 原实现 NaN − consume = NaN，`NaN < reserve` 恒 false → <b>电量闸静默通过</b>，
        // 一架台账漏填电量的机反而最"能飞"，是最危险的方向。生成端已不再把缺失填 0，
        // 这里必须显式拦住：判不了就判不了，写明原因交给人。
        if (!Double.isFinite(combo.battery())) {
            reasons.add("电量未登记：drone.battery 为空，电量闸无法判定——"
                    + "请补全台账后重评（不按 0 判死，也不按满电放行）");
        } else {
            double remaining = combo.battery() - consume;
            if (remaining < params.reserveBatteryPercent()) {
                // 文案用「巢↔取／取↔交／往返里程」，与 legsOf 的算法逐字对应：
                // 原写法「三段里程 + 两个 2× 分项」对不上账（两个往返分项之和就是合计，
                // 中间并没有第三段），现场按文案复算会以为公式错了。
                // 「↔」已表明是往返，故合计只写「往返里程」，单位紧随数字不空格
                // （断言按此逐字匹配，改文案要同步改 FeasibilityJudgeBatteryLegTest）。
                reasons.add(String.format(
                        "电量不足：%.0f%% − 往返里程 %.1fkm（巢↔取 %.1f + 取↔交 %.1f）× %.1f%%/km"
                                + " − 载荷附加 ≈ 余 %.0f%% < 保留 %.0f%%",
                        combo.battery(), legs.totalKm(), legs.nestLegKm(), legs.taskLegKm(),
                        params.batteryPerKmPercent(),
                        remaining, params.reserveBatteryPercent()));
            }
        }

        return new FeasibilityResult(combo.comboId(),
                reasons.isEmpty() ? Verdict.FEASIBLE : Verdict.INFEASIBLE,
                reasons);
    }

    /** 电量闸用的分段里程（km）：{@code nestLeg}＝巢↔取往返，{@code taskLeg}＝取↔交往返。 */
    private record Legs(double nestLegKm, double taskLegKm, double totalKm) {
    }

    /**
     * 三段里程拆分：{@code totalKm = 2×(distKm + combo.distKm())}。
     *
     * <p>逐字审查：拆分出的每个分量都单独校验有限非负——
     * {@code combo} 由调用方构造（测试与旁路可能直接 new），
     * {@code combo.distKm()} 若是 {@code NaN}，{@code totalKm} 也会变 {@code NaN}，
     * 于是 {@code NaN < 保留值} 恒 false ⇒ 电量闸被静默放行，正是 HIGH.8 那类缺陷换了入口。
     */
    private static Legs legsOf(CandidateGenerator.CandidateCombo combo, double distKm) {
        if (combo == null) {
            throw new IllegalArgumentException("候选组合 combo 不能为 null");
        }
        double nestToPickupKm = combo.distKm();
        requireFiniteNonNegative("combo.distKm", nestToPickupKm);
        double nestLeg = 2.0 * nestToPickupKm;
        double taskLeg = 2.0 * distKm;
        return new Legs(nestLeg, taskLeg, nestLeg + taskLeg);
    }

    /** 非有限值（NaN／±Infinity）与负值一律拒绝——见 {@link #judge} 的入口守卫说明。 */
    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " 必须是有限非负数，实际 " + value);
        }
    }
}
