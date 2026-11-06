package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.MultiStepPredictor;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分段限速在<b>真中心线折线</b>上仍按段生效（11-06 序号 2 · 卢肄文 · 判据「按段生效」证据）。
 *
 * <p>为什么单列一个测试：既有的 {@code SegmentSpeedLimitSteadyStateTest} 用的是一条
 * <b>两点直线</b>当中心线，验的是限速的衰减逻辑，验不到「限速段的 {@code fromS／toS}
 * 与参考线是否同一把尺子」。本类改用 {@code application.yml} 里那条 13 点真折线
 * （朝阳—八一 5.78 km，中段 11.3° 折点），把「按中心线<b>弧长</b>」与「按直线距离」
 * 两种口径的差异变成可断言的数。
 *
 * <h3>两种口径在赣江航段差多少（本测试的实测值）</h3>
 * <table border="1">
 *   <caption>弧长口径 vs 直线距离口径</caption>
 *   <tr><th>点位</th><th>沿折线弧长 s</th><th>到起点的直线距离</th><th>差</th></tr>
 *   <tr><td>末段起点</td><td>4000 m</td><td>3980.5 m</td><td>19.5 m</td></tr>
 *   <tr><td>折线末端</td><td>5785.4 m</td><td>5759.9 m</td><td>25.5 m</td></tr>
 * </table>
 * <p>差 20 m 上下看着不大，但限速段的边界是<b>硬切换</b>：拿直线距离查表，末段限速会在
 * 实际还没到 4000 m 弧长时就提前生效（或反之），而这个偏差随航段弯曲度增长，
 * 台账换成更弯的定稿线之后只会更大。故必须锁住「按弧长」这一条。
 */
class CenterlineSegmentLimitOnRealPolylineTest {

    /** {@code application.yml} 的 {@code wavenest.m2.prediction.centerline}，13 点真折线。 */
    private static final WaterwayGeometry GANJIANG = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(115.852, 28.646),
            new WaterwayGeometry.Vertex(115.853739, 28.650224),
            new WaterwayGeometry.Vertex(115.855479, 28.654448),
            new WaterwayGeometry.Vertex(115.857218, 28.658673),
            new WaterwayGeometry.Vertex(115.859, 28.663),
            new WaterwayGeometry.Vertex(115.861586, 28.666749),
            new WaterwayGeometry.Vertex(115.864236, 28.670593),
            new WaterwayGeometry.Vertex(115.866887, 28.674436),
            new WaterwayGeometry.Vertex(115.869537, 28.678279),
            new WaterwayGeometry.Vertex(115.872188, 28.682123),
            new WaterwayGeometry.Vertex(115.874838, 28.685966),
            new WaterwayGeometry.Vertex(115.877489, 28.689809),
            new WaterwayGeometry.Vertex(115.879, 28.692)));

    /** {@code application.yml} 的 {@code segment-speed-limits}。 */
    private static final String SPEED_LIMIT_SPEC = "0,2000,4.5;2000,4000,4.5;4000,5780,2.5";

    private static final double LAST_SEGMENT_FROM_S = 4000.0;
    private static final double FIRST_LIMIT_MS = 4.5;
    private static final double LAST_LIMIT_MS = 2.5;

    @Test
    @DisplayName("真折线总长约 5.78 km，限速段覆盖到 5780 m（末 5 m 落在段外属正常写法）")
    void realPolyline_totalLengthShouldCoverConfiguredLimits() {
        double total = GANJIANG.totalLengthM();
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse(SPEED_LIMIT_SPEC);

        assertEquals(5780.0, total, 10.0,
                "13 点真折线的累计弧长应在 5.78 km 量级，实际 " + total);
        assertEquals(5780.0, lookup.maxCoveredS(), 1e-9);
        assertTrue(total - lookup.maxCoveredS() <= PredictionConfig.COVERAGE_TOLERANCE_M + 10.0,
                "末段 toS 与折线总长的差应小于一个容差量级，实际差 "
                        + (total - lookup.maxCoveredS()) + " m");
    }

    @Test
    @DisplayName("限速切换点按中心线弧长切分：s=3999 仍 4.5、s=4000 起 2.5")
    void switchPoint_shouldBeOnArcLengthNotStraightDistance() {
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse(SPEED_LIMIT_SPEC);

        assertEquals(FIRST_LIMIT_MS, lookup.speedLimitMs(LAST_SEGMENT_FROM_S - 1.0), 1e-9,
                "末段起点前 1 m 仍是前段限速");
        assertEquals(LAST_LIMIT_MS, lookup.speedLimitMs(LAST_SEGMENT_FROM_S), 1e-9,
                "末段起点起为末段限速（左闭）");

        // 同一位置按直线距离算是多少：明显不是 4000
        WaterwayGeometry.Vertex pointAt4000 =
                GANJIANG.pointAt(LAST_SEGMENT_FROM_S);
        double straightFromStart = planarDistanceM(GANJIANG.pointAt(0.0), pointAt4000);
        assertTrue(Math.abs(straightFromStart - LAST_SEGMENT_FROM_S) > 10.0,
                "若两种口径差不到 10 m，本用例就无法区分「按弧长」与「按直线距离」；实测差 "
                        + Math.abs(straightFromStart - LAST_SEGMENT_FROM_S) + " m");
        assertEquals(3980.5, straightFromStart, 2.0,
                "s=4000 处到起点的直线距离应约 3980.5 m（比弧长少 19.5 m）");
    }

    @Test
    @DisplayName("真折线上推进：跨 4000 m 后速度被末段限速压住（按段生效，不是整段一个常数）")
    void propagationOnRealPolyline_shouldObeySegmentSwitch() {
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse(SPEED_LIMIT_SPEC);
        MultiStepPredictor.Params params = new MultiStepPredictor.Params(
                MultiStepPredictor.DEFAULT_PROCESS_NOISE_Q, MultiStepPredictor.DEFAULT_TAU_V_SEC,
                0.0, 0.0, 50.0, 180.0, 40.0, 60L, 1200L);
        // 从 3500 m 出发、4.4 m/s 顺航道正向；600 s 内必然越过 4000 m 切换点
        MultiStepPredictor.Input input = new MultiStepPredictor.Input(
                1_700_000_000_000L, 3500.0, 4.4, 400.0, 0.0, 0.0,
                MultiStepPredictor.LateralForecast.ou(0.0, 0.0, 0.0, 120.0, 15.0),
                GANJIANG, lookup);

        List<MultiStepPredictor.Sample> samples = MultiStepPredictor.predict(input, params);

        List<Double> beforeSwitch = new ArrayList<>();
        List<Double> afterSteady = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) {
            double sFrom = samples.get(i - 1).s();
            double dtSec = samples.get(i).tauSec() - samples.get(i - 1).tauSec();
            double speed = (samples.get(i).s() - sFrom) / dtSec;
            if (sFrom < LAST_SEGMENT_FROM_S - 200.0) {
                beforeSwitch.add(speed);
            }
            // 稳态判据取切换点后 800 m：一阶衰减 τV=60 s，4.5→2.5 收敛到 +0.05 m/s 内约需 220 s、约 560 m
            if (sFrom > LAST_SEGMENT_FROM_S + 800.0) {
                afterSteady.add(speed);
            }
        }

        assertTrue(!beforeSwitch.isEmpty() && !afterSteady.isEmpty(),
                "点列未同时覆盖切换点前后，判据未被执行");
        double maxBefore = beforeSwitch.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double maxAfter = afterSteady.stream().mapToDouble(Double::doubleValue).max().orElseThrow();

        assertTrue(maxBefore <= FIRST_LIMIT_MS + 0.1,
                "切换点之前应保持前段限速 4.5，实测最大 " + maxBefore);
        assertTrue(maxAfter <= LAST_LIMIT_MS + 0.1,
                "进入末段后应被压到 2.5，实测最大 " + maxAfter + "（说明限速没按段生效）");
        assertTrue(maxBefore > maxAfter + 1.0,
                "前后段速度应出现明显台阶，实测前 " + maxBefore + " 后 " + maxAfter);
    }

    /** 两点平面距离，m（与 {@code WaterwayGeometry} 同一近似）。 */
    private static double planarDistanceM(WaterwayGeometry.Vertex from, WaterwayGeometry.Vertex to) {
        double metersPerDegLon =
                WaterwayGeometry.METERS_PER_DEG_LAT * Math.cos(Math.toRadians(from.lat()));
        double dx = (to.lon() - from.lon()) * metersPerDegLon;
        double dy = (to.lat() - from.lat()) * WaterwayGeometry.METERS_PER_DEG_LAT;
        return Math.hypot(dx, dy);
    }
}
