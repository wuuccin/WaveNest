package com.waterlogistics.algo.candidate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 回归：机型抗风上限真正进入候选判定。
 *
 * <p><b>本类要守的是什么</b>：10-20 B1 之前，{@code FeasibilityJudge} 只吃调用方传的
 * 统一上限 {@code Params.maxWindMs}，所有组合比的是同一个数——于是「同一风速下
 * A 型淘汰、B 型保留」在代码里不可能发生。B1 把抗风上限随组合透传，本类用
 * <b>同一阵风、同一份输入</b> 下的两架不同机型来钉住这个分叉。
 *
 * <p>判定口径依据：{@code 62-C组算法方案} 功能 3 判据 {@code windclass_match}
 * ＝「机型抗风等级 ≥ 任务区最不利风级」。
 */
class FeasibilityJudgeWindLimitTest {

    private final CandidateGenerator generator = new CandidateGenerator();
    private final FeasibilityJudge judge = new FeasibilityJudge();

    /** 判定参数：统一上限压到 5.0，低于两台机型各自的抗风上限 */
    private static final FeasibilityJudge.Params PARAMS =
            new FeasibilityJudge.Params(5.0, 20.0, 1.5, 0.2);

    /** 电量给足、距离给短，让电量闸不干扰风况闸的观察 */
    private static final double DIST_KM = 1.0;
    private static final double PAYLOAD_KG = 2.0;

    /**
     * 构造一份「一高一低两档抗风」的输入：同一机巢、同一载荷能力、同一电量，
     * 唯一差别是机型与机型抗风上限。
     */
    private static CandidateInput twoTypesSameEverything(double lightWindLimit, double heavyWindLimit) {
        CandidateInput.NestView nest = new CandidateInput.NestView(
                "NEST-01", 114.305, 30.605, List.of("M30T", "M400"));
        return new CandidateInput(
                114.310, 30.605, PAYLOAD_KG,
                List.of(nest),
                List.of(
                        // 轻载机型：抗风上限低
                        new CandidateInput.DroneView("UAV-01", "NEST-01", "M30T", "空闲",
                                90.0, 6.0, lightWindLimit, 12.0),
                        // 重载机型：抗风上限高
                        new CandidateInput.DroneView("DRN-M400-01", "NEST-01", "M400", "空闲",
                                90.0, 20.0, heavyWindLimit, 15.0)));
    }

    /**
     * <b>B1 的核心断言</b>：风 11 m/s 落在两档抗风之间（10 < 11 < 12）——
     * M30T 淘汰、M400 保留。若抗风上限没有贯通到判定端，两架机比的是同一个
     * 统一上限（5.0），会一起被淘汰或一起通过，本条必挂。
     */
    @Test
    void sameWindKnocksOutLightTypeButKeepsHeavyType() {
        double wind = 11.0;
        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(twoTypesSameEverything(10.0, 12.0), CandidateGenerator.Params.defaults());

        assertEquals(2, combos.size(), "两架机都应先通过候选生成（风况不是生成端的过滤项）");

        FeasibilityJudge.FeasibilityResult light = judgeFor(combos, "M30T", wind);
        FeasibilityJudge.FeasibilityResult heavy = judgeFor(combos, "M400", wind);

        assertFalse(light.feasible(), "M30T（抗风 10.0）在 11 m/s 下应淘汰");
        assertTrue(heavy.feasible(), "M400（抗风 12.0）在 11 m/s 下应保留");

        // 淘汰原因要点明「机型」二字与机型名，便于直接贴事件流定位
        assertTrue(light.reasons().stream().anyMatch(r -> r.contains("机型抗风不足") && r.contains("M30T")),
                "淘汰原因应指明是机型抗风不足，实际：" + light.reasons());
    }

    /** 风低于两档抗风：都给过——反向验证上一条不是因为「一律淘汰低抗风机型」 */
    @Test
    void belowBothLimitsBothPass() {
        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(twoTypesSameEverything(10.0, 12.0), CandidateGenerator.Params.defaults());
        assertTrue(judgeFor(combos, "M30T", 9.0).feasible());
        assertTrue(judgeFor(combos, "M400", 9.0).feasible());
    }

    /** 风高于两档抗风：都给不过 */
    @Test
    void aboveBothLimitsBothFail() {
        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(twoTypesSameEverything(10.0, 12.0), CandidateGenerator.Params.defaults());
        assertFalse(judgeFor(combos, "M30T", 13.0).feasible());
        assertFalse(judgeFor(combos, "M400", 13.0).feasible());
    }

    /**
     * 边界：风速恰等于抗风上限应<b>通过</b>（判据是「抗风等级 ≥ 最不利风级」，
     * 不是严格大于）。
     */
    @Test
    void exactlyAtLimitPasses() {
        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(twoTypesSameEverything(10.0, 12.0), CandidateGenerator.Params.defaults());
        assertTrue(judgeFor(combos, "M30T", 10.0).feasible());
    }

    /**
     * 机型抗风未登记（NaN）时：退回统一上限判定，不能默默放行，也不能按 0 拦死，
     * 且原因里必须写明是回落口径——否则事后查不出「这条为什么按统一值判的」。
     */
    @Test
    void unregisteredFallsBackToGlobalLimitAndSaysSo() {
        List<CandidateGenerator.CandidateCombo> combos = generator.generate(
                twoTypesSameEverything(Double.NaN, Double.NaN), CandidateGenerator.Params.defaults());

        // 5.0 恰好不超统一上限 5.0 → 通过
        assertTrue(judgeFor(combos, "M30T", 5.0).feasible());

        // 6.0 超统一上限 → 淘汰，且原因说明是回落
        FeasibilityJudge.FeasibilityResult r = judgeFor(combos, "M30T", 6.0);
        assertFalse(r.feasible());
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("未登记")),
                "回落判定必须写明未登记，实际：" + r.reasons());
    }

    /** 抗风上限随组合透传：生成端不能把它丢掉（丢了判定端就读不到机型差异） */
    @Test
    void generatorCarriesTypeCapabilityOntoCombo() {
        List<CandidateGenerator.CandidateCombo> combos =
                generator.generate(twoTypesSameEverything(10.0, 12.0), CandidateGenerator.Params.defaults());
        CandidateGenerator.CandidateCombo light = combos.stream()
                .filter(c -> "M30T".equals(c.type())).findFirst().orElseThrow();
        CandidateGenerator.CandidateCombo heavy = combos.stream()
                .filter(c -> "M400".equals(c.type())).findFirst().orElseThrow();

        assertEquals(10.0, light.windLimitMs());
        assertEquals(12.0, heavy.windLimitMs());
        assertEquals(12.0, light.cruiseSpeedMs());
        assertEquals(15.0, heavy.cruiseSpeedMs());
    }

    private FeasibilityJudge.FeasibilityResult judgeFor(List<CandidateGenerator.CandidateCombo> combos,
                                                        String type, double windMs) {
        CandidateGenerator.CandidateCombo combo = combos.stream()
                .filter(c -> type.equals(c.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("候选里没有机型 " + type));
        return judge.judge(combo, DIST_KM, windMs, PAYLOAD_KG, PARAMS);
    }
}
