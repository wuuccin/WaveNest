package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LateralOuFilter} 单测（A 组 · {@code 80} §3.2.6 步骤 D ＋ §3.2.5 横向滤波）。
 *
 * <p>★ 最重要的两条：
 * <ol>
 *   <li><b>回复目标是 {@code e_mean} 而不是 0</b>——偏侧航行的船不应被拉回中心线（否则 XTE 恒不为零）；</li>
 *   <li><b>换道机动会被检出并放大 {@code σ_cross}</b>，但关闭位置偏移时不得凭空移动位置。</li>
 * </ol>
 */
class LateralOuFilterTest {

    /** 基础参数（演示量级）：τ_e=120 s、σ_e=15 m、σ_pos=20 m、半宽 50 m、换道模型关闭。 */
    private static LateralOuFilter.Params baseParams() {
        return new LateralOuFilter.Params(120.0, 15.0, 20.0, 300.0, 60.0,
                5.0, 3, 2.0, 120.0, false, 50.0);
    }

    private static LateralOuFilter.Params wideParams(boolean laneChangeModelEnabled) {
        return new LateralOuFilter.Params(120.0, 15.0, 20.0, 300.0, 60.0,
                2.0, 3, 2.0, 120.0, laneChangeModelEnabled, 400.0);
    }

    @Test
    @DisplayName("未初始化 update 抛异常；时间不前进也抛异常")
    void guard_shouldRejectUninitializedOrNonAdvancingTime() {
        LateralOuFilter filter = new LateralOuFilter(baseParams());
        assertThrows(IllegalStateException.class, () -> filter.update(10.0, 1_000L));

        filter.initialize(10.0, 1_000L);
        assertThrows(IllegalStateException.class, () -> filter.update(10.0, 1_000L));
    }

    @Test
    @DisplayName("★ 回复目标是 e_mean 不是 0：稳定偏侧 20 m 的船，滤波后仍应停在 20 m 附近")
    void meanReversion_shouldTargetEMeanNotZero() {
        LateralOuFilter filter = new LateralOuFilter(baseParams());
        filter.initialize(20.0, 0L);

        LateralOuFilter.State state = null;
        for (int i = 1; i <= 12; i++) {
            state = filter.update(20.0, i * 5_000L);
        }

        assertEquals(20.0, state.e(), 1.0,
                "偏侧航行应保持在惯常横向位置，不能被拉回中心线 e=0");
        assertEquals(20.0, state.eMean(), 1.0, "e_mean 应估计出 20 m 的惯常航路");
        assertEquals(20.0, filter.predictedE(120.0), 3.0,
                "预测也应回到 e_mean≈20，而不是 0");
    }

    @Test
    @DisplayName("横向位置被航道半宽夹紧（e ∈ [−W/2, W/2]）")
    void e_shouldBeClampedToChannelWidth() {
        LateralOuFilter filter = new LateralOuFilter(baseParams());
        filter.initialize(20.0, 0L);

        LateralOuFilter.State state = filter.update(500.0, 5_000L);   // 远超声明的半宽 50 m

        assertTrue(Math.abs(state.e()) <= 50.0, "横向偏移应被夹到半宽内，实际 " + state.e());
    }

    @Test
    @DisplayName("★ 换道检测：连续同号横向位移超门限 → laneChange=true，σ_cross 放大")
    void laneChange_shouldBeDetectedAndInflateSigma() {
        LateralOuFilter filter = new LateralOuFilter(wideParams(false));
        filter.initialize(0.0, 0L);

        assertFalse(filter.laneChange());
        assertEquals(1.0, filter.sigmaCrossMultiplier(), 1e-9, "平稳期不放大");

        LateralOuFilter.State state = null;
        for (int i = 1; i <= 5; i++) {
            state = filter.update(i * 30.0, i * 5_000L);   // 每拍 +30 m，同号持续
        }

        assertTrue(state.laneChange(), "连续 5 拍同向大幅横移，应判换道机动");
        assertEquals(2.0, filter.sigmaCrossMultiplier(), 1e-9,
                "机动期 σ_cross 应按 lcInflate 放大");
    }

    @Test
    @DisplayName("★ 关闭换道位置偏移时：σ 照样放大，但 predictedE 不额外前推")
    void laneChangeDisabled_shouldOnlyInflateSigma() {
        LateralOuFilter disabled = new LateralOuFilter(wideParams(false));
        LateralOuFilter enabled = new LateralOuFilter(wideParams(true));

        for (LateralOuFilter filter : new LateralOuFilter[] {disabled, enabled}) {
            filter.initialize(0.0, 0L);
            for (int i = 1; i <= 5; i++) {
                filter.update(i * 30.0, i * 5_000L);
            }
        }

        assertTrue(disabled.laneChange() && enabled.laneChange());
        assertEquals(2.0, disabled.sigmaCrossMultiplier(), 1e-9, "关闭位置偏移也要放大 σ");
        assertTrue(enabled.predictedE(60.0) > disabled.predictedE(60.0),
                "开启位置偏移时，机动期预测应沿横速额外前推");
    }

    @Test
    @DisplayName("机动期线性外推封顶 tauLcSec：更长的 τ 不会无限外推")
    void laneChangeExtrapolation_shouldBeCappedByTauLc() {
        LateralOuFilter filter = new LateralOuFilter(wideParams(true));
        filter.initialize(0.0, 0L);
        for (int i = 1; i <= 5; i++) {
            filter.update(i * 30.0, i * 5_000L);
        }

        double atTauLc = filter.predictedE(120.0);
        double atDoubleTauLc = filter.predictedE(240.0);

        assertEquals(atTauLc, atDoubleTauLc, 1e-9,
                "超过 τ_lc 后外推封顶，预测不再随 τ 增长");
    }

    @Test
    @DisplayName("平稳期 predictedE 按 OU 回复：τ 越大越接近 e_mean")
    void predictedE_shouldRevertToMean() {
        LateralOuFilter filter = new LateralOuFilter(baseParams());
        filter.initialize(0.0, 0L);
        for (int i = 1; i <= 6; i++) {
            filter.update(0.0, i * 5_000L);
        }
        // 人为把当前 e 拉开：喂一个偏离观测
        filter.update(30.0, 31_000L);

        double near = filter.predictedE(10.0);
        double far = filter.predictedE(240.0);

        assertTrue(far < near, "OU 回复：越远的预测越靠近回复目标（|e| 更小）");
        assertTrue(Math.abs(far) <= Math.abs(near) + 1e-9);
    }

    @Test
    @DisplayName("参数自校验：τ_e / σ_e / σ_pos / 半宽必须为正，lcCount≥1、lcInflate≥1")
    void params_shouldSelfValidate() {
        assertThrows(IllegalArgumentException.class,
                () -> new LateralOuFilter.Params(0.0, 15, 20, 300, 60, 5, 3, 2, 120, false, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new LateralOuFilter.Params(120, 0, 20, 300, 60, 5, 3, 2, 120, false, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new LateralOuFilter.Params(120, 15, 20, 300, 60, 5, 0, 2, 120, false, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new LateralOuFilter.Params(120, 15, 20, 300, 60, 5, 3, 0.5, 120, false, 50));
        assertThrows(IllegalArgumentException.class, () -> new LateralOuFilter(null));
    }
}
