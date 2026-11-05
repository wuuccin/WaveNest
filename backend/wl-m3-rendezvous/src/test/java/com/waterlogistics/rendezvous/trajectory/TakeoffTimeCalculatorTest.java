package com.waterlogistics.rendezvous.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.algo.meeting.model.TakeoffInfeasibleReason;
import org.junit.jupiter.api.Test;

/**
 * 起飞时刻回填单测——基本式＋取整、不可行判定与边界、入参校验
 * （《81-B组算法方案》§3.3、《43-编码分工说明》§8 ④）。
 *
 * <p>数值夹具以 T0（对齐到 5 s 网格）为基准，期望值全部手算。
 */
class TakeoffTimeCalculatorTest {

    private static final long T0 = 1_700_000_000_000L;

    /** 基本式：起飞＝会面−精算时长，向下对齐到整秒（余量舍去不跨格）。 */
    @Test
    void compute_shouldBackCalculateAndAlignDownToGrid() {
        B_TakeoffTimeResult r = TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0 + 100_450L, 20, T0, 1_000L));

        assertEquals(T0 + 80_000L, r.takeoffT(), "raw＝+80.45 s 向下对齐整秒");
        assertEquals(20, r.tauCoarseUsed(), "记录反推实际所用时长（首版＝τ_fine）");
        assertTrue(r.feasible(), "起飞晚于当前，判可行");
        assertNull(r.infeasibleReason(), "可行时原因为 null");
        assertEquals("combo-1", r.comboId(), "组合标识透传");
    }

    /** 已对齐值不再变动（对齐幂等）。 */
    @Test
    void compute_shouldKeepAlreadyAlignedValue() {
        B_TakeoffTimeResult r = TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0 + 220_000L, 20, T0, 1_000L));

        assertEquals(T0 + 200_000L, r.takeoffT(), "整秒值原样保留");
    }

    /** 粗粒度对齐：5 s 网格下舍到所在网格起点。 */
    @Test
    void compute_shouldAlignToCoarseGrid() {
        B_TakeoffTimeResult r = TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0 + 87_000L, 20, T0, 5_000L));

        assertEquals(T0 + 65_000L, r.takeoffT(), "raw＝+67 s → 网格 5 s → +65 s");
    }

    /** 不可行：反推早于当前时刻 → feasible=false ＋「早于当前时刻」；算式值照给（追溯）。 */
    @Test
    void compute_shouldFlagInfeasible_whenBeforeNow() {
        B_TakeoffTimeResult r = TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0 + 50_000L, 60, T0 + 10_000L, 1_000L));

        assertFalse(r.feasible(), "反推早于当前，应判不可行");
        assertEquals(TakeoffInfeasibleReason.BEFORE_NOW, r.infeasibleReason());
        assertEquals("早于当前时刻", r.infeasibleReason().getValue());
        assertEquals(T0 - 10_000L, r.takeoffT(), "算式结果照给（追溯用）");
    }

    /** 边界：起飞时刻正好等于当前时刻 → 可行（「早于」是严格早于）。 */
    @Test
    void compute_shouldBeFeasible_whenTakeoffEqualsNow() {
        B_TakeoffTimeResult r = TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0 + 60_000L, 60, T0, 1_000L));

        assertEquals(T0, r.takeoffT(), "60−60＝0，对齐不变");
        assertTrue(r.feasible(), "判等即可行");
    }

    /** 对齐后早于当前时刻：即使未对齐值仍在未来，也不得顺延到下一格，必须判不可行。 */
    @Test
    void compute_shouldFlagInfeasible_whenAlignedValueBeforeNow() {
        B_TakeoffTimeResult r = TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0 + 21_500L, 20, T0 + 1_500L, 1_000L));

        assertEquals(T0 + 1_000L, r.takeoffT(), "对齐到整秒后为 +1 s");
        assertFalse(r.feasible(), "对齐值早于当前 +1.5 s，应判不可行");
        assertEquals(TakeoffInfeasibleReason.BEFORE_NOW, r.infeasibleReason());
    }

    /** 入参校验：时长负数、粒度非正一律拒绝。 */
    @Test
    void compute_shouldRejectInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0, -1, T0, 1_000L)), "负时长应拒绝");
        assertThrows(IllegalArgumentException.class, () -> TakeoffTimeCalculator.compute(
                new TakeoffTimeCommand("combo-1", T0, 20, T0, 0L)), "粒度非正应拒绝");
    }

    /** 不可行原因枚举的中文口径（《22》§2.1.2／《81》§3.3）。 */
    @Test
    void infeasibleReason_shouldFollowDocValues() {
        assertEquals("早于当前时刻", TakeoffInfeasibleReason.BEFORE_NOW.getValue());
        assertEquals("迭代不收敛", TakeoffInfeasibleReason.ITERATION_NOT_CONVERGED.getValue());
        assertEquals(TakeoffInfeasibleReason.ITERATION_NOT_CONVERGED,
                TakeoffInfeasibleReason.fromValue("迭代不收敛"));
    }
}
