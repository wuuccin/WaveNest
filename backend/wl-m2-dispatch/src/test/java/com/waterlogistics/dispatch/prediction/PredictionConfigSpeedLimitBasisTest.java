package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分段限速与中心线的基准一致性闸门（11-06 序号 2 · 卢肄文）。
 *
 * <p>限速段的 {@code fromS／toS} 是<b>沿中心线的绝对弧长</b>。引擎的参考线有两条来源：
 * 配了中心线时用真中心线（{@code s} 从中心线首点起算）；没配时逐船自建（{@code s} 从
 * 该船本窗首条实测点起算）。后者的 {@code s} 与限速段不同源，照常查表就是<b>基准错位</b>
 * ——限速会在任意位置生效且不报错。本测试锁住这条闸门。
 */
class PredictionConfigSpeedLimitBasisTest {

    private static final WaterwayGeometry CENTERLINE = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(115.852, 28.646),
            new WaterwayGeometry.Vertex(115.879, 28.692)));

    private final PredictionConfig config = new PredictionConfig();

    @Test
    @DisplayName("中心线缺失时整体关闭分段限速：弧长不同源，按段生效就是按错位置生效")
    void missingCenterline_shouldDisableSpeedLimits() {
        CenterlineSpeedLimitLookup configured =
                CenterlineSpeedLimitLookup.parse("0,2000,4.5;4000,5780,2.5");
        assertFalse(configured.isUnbounded(), "前提：限速确实配上了");

        SpeedLimitLookup effective = config.speedLimitFor(null, configured);

        assertEquals(Double.MAX_VALUE, effective.speedLimitMs(4500.0), 0.0,
                "退化参考线下必须恒不限速");
        assertTrue(effective.switchPointsM().length == 0, "退化参考线下不应有任何限速切换点");
    }

    @Test
    @DisplayName("中心线存在时限速原样透传，段边界仍按中心线弧长切分")
    void presentCenterline_shouldKeepSpeedLimits() {
        CenterlineSpeedLimitLookup configured =
                CenterlineSpeedLimitLookup.parse("0,2000,4.5;4000,5780,2.5");

        SpeedLimitLookup effective = config.speedLimitFor(CENTERLINE, configured);

        assertSame(configured, effective, "基准一致时应原样透传，不做任何改写");
        assertEquals(4.5, effective.speedLimitMs(1000.0), 1e-9, "首段限速仍按弧长生效");
        assertEquals(2.5, effective.speedLimitMs(4500.0), 1e-9, "末段限速仍按弧长生效");
    }

    @Test
    @DisplayName("未配置限速（恒不限速实例）时不受中心线有无影响")
    void unboundedLookup_shouldPassThrough() {
        SpeedLimitLookup unbounded = CenterlineSpeedLimitLookup.parse("");

        assertSame(unbounded, config.speedLimitFor(null, unbounded));
        assertSame(unbounded, config.speedLimitFor(CENTERLINE, unbounded));
    }

    @Test
    @DisplayName("限速段越过中心线总长时仍保留限速（只在装配期告警，不关闭）")
    void limitsBeyondCenterline_shouldStillApply() {
        // 中心线总长约 5.8 km；配置覆盖到 12 km，明显按另一条参考线定的
        CenterlineSpeedLimitLookup configured = CenterlineSpeedLimitLookup.parse("0,12000,3.0");

        SpeedLimitLookup effective = config.speedLimitFor(CENTERLINE, configured);

        assertSame(configured, effective, "越界只是口径提示，段内部分仍应按段生效");
        assertEquals(3.0, effective.speedLimitMs(5000.0), 1e-9);
    }
}
