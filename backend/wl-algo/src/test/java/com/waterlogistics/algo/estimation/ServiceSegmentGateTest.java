package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ServiceSegmentGate} 单测（A 组 · {@code 80} §3.1.3「驶入时刻」）。
 *
 * <p>验证点：已在段内、从下游进入、从上游进入（上行船）、<b>整段跨过</b>（快船）、
 * <b>永不再入</b>，以及——最重要的——<b>不依赖 {@code s(t)} 单调</b>（掉头后重新进入）。
 */
class ServiceSegmentGateTest {

    /** 一条正北向的直线航道：纬度 28.60 → 28.70，长约 11132 m。构造成 s 与纬度线性对应，便于断言。 */
    private static final double BASE_LAT = 28.60;
    private static final WaterwayGeometry STRAIGHT_NORTH = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(115.85, BASE_LAT),
            new WaterwayGeometry.Vertex(115.85, BASE_LAT + 0.10)));

    /** 沿该航道距起点 s 米处的预测点。 */
    private static EstimatedPoint pointAtS(long tMs, double s) {
        double lat = BASE_LAT + s / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        return new EstimatedPoint(tMs, 115.85, lat, 20.0, 15.0);
    }

    private static List<EstimatedPoint> track(double... sValues) {
        List<EstimatedPoint> points = new ArrayList<>(sValues.length);
        for (int i = 0; i < sValues.length; i++) {
            points.add(pointAtS(i * 60_000L, sValues[i]));   // 每点间隔 60 s
        }
        return points;
    }

    @Test
    @DisplayName("首个点已在服务航段内 → 驶入时刻就是预测起点")
    void alreadyInside_shouldEnterAtFirstPoint() {
        List<EstimatedPoint> points = track(1500.0, 2500.0, 3500.0);

        ServiceSegmentGate.EntryResult result =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 3000.0);

        assertTrue(result.reaches());
        assertEquals(0L, result.enterTsMs(), "第 0 点就在段内，驶入时刻为 t0");
        assertEquals(1500.0, result.enterS(), 30.0);
    }

    @Test
    @DisplayName("★ 从下游进入：500 m → 1500 m 跨过 sStart=1000，按中点插值得 30 s")
    void enteringFromDownstream_shouldInterpolate() {
        List<EstimatedPoint> points = track(500.0, 1500.0, 2500.0);

        ServiceSegmentGate.EntryResult result =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 3000.0);

        assertTrue(result.reaches());
        assertEquals(30_000L, result.enterTsMs(), 1000L, "500→1500 的中点，t0=0、t1=60s → 约 30 s");
        assertEquals(1000.0, result.enterS(), 30.0, "驶入时跨过的是下界 sStart");
    }

    @Test
    @DisplayName("★ 从上游进入（上行船）：5000 m → 2000 m 跨过 sEnd=3000")
    void enteringFromUpstream_shouldInterpolate() {
        List<EstimatedPoint> points = track(5000.0, 2000.0, 1500.0);

        ServiceSegmentGate.EntryResult result =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 3000.0);

        assertTrue(result.reaches());
        // 5000→2000 跨 3000：比例 (3000-5000)/(2000-5000) = 2/3，t1=0、t2=60s → 40 s
        assertEquals(40_000L, result.enterTsMs(), 1000L);
        assertEquals(3000.0, result.enterS(), 30.0, "从上游进入时跨过的是上界 sEnd");
    }

    @Test
    @DisplayName("★ 整段跨过（快船）：200 m → 5000 m 一步跳完，仍能捕获进入")
    void jumpingOverWholeSegment_shouldStillDetectEntry() {
        List<EstimatedPoint> points = track(200.0, 5000.0);

        ServiceSegmentGate.EntryResult result =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 3000.0);

        assertTrue(result.reaches(), "不能因为采样太稀就漏判进入");
        assertEquals(1000.0, result.enterS(), 30.0, "进入边界应取下界 sStart");
        assertTrue(result.enterTsMs() > 0L && result.enterTsMs() < 60_000L,
                "驶入时刻应落在两采样点之间");
    }

    @Test
    @DisplayName("★ 掉头：先冲出上界，再折返进入 → 仍能捕获（不假设 s 单调）")
    void reversal_shouldStillDetectEntry() {
        // 3000s 外 → 5000 → 2000（折返回来）
        List<EstimatedPoint> points = track(200.0, 5000.0, 4500.0, 2000.0);
        // 前两点 (200→5000) 会先跨越 sStart=1000，所以这里应判定为"进入 sStart"
        ServiceSegmentGate.EntryResult firstTouch =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 3000.0);
        assertTrue(firstTouch.reaches());
        assertEquals(1000.0, firstTouch.enterS(), 30.0, "首次进入跨的是 sStart");

        // 若从界外高处直接折返进入（起始就 > sEnd，且从未跨过 sStart）
        List<EstimatedPoint> fromAbove = track(6000.0, 5000.0, 2000.0);
        ServiceSegmentGate.EntryResult reEntry =
                ServiceSegmentGate.firstEntry(fromAbove, STRAIGHT_NORTH, 1000.0, 3000.0);
        assertTrue(reEntry.reaches());
        assertEquals(3000.0, reEntry.enterS(), 30.0, "折返进入跨的是 sEnd");
    }

    @Test
    @DisplayName("永不进入 → reaches=false（模块层据此判拒绝 OUT_OF_SERVICE）")
    void neverEntering_shouldReportFalse() {
        List<EstimatedPoint> points = track(100.0, 200.0, 300.0, 400.0);

        ServiceSegmentGate.EntryResult result =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 3000.0);

        assertFalse(result.reaches());
        assertEquals(0L, result.enterTsMs());
    }

    @Test
    @DisplayName("区间退化（sStart == sEnd）：单点区间也能判")
    void degenerateSegment_shouldWork() {
        List<EstimatedPoint> points = track(500.0, 1500.0, 2500.0);

        ServiceSegmentGate.EntryResult result =
                ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 1000.0, 1000.0);

        assertTrue(result.reaches());
        assertEquals(1000.0, result.enterS(), 30.0);
    }

    @Test
    @DisplayName("投影序列可复算（可解释性）：s 值应与构造值一致")
    void projectAll_shouldBeRecomputable() {
        List<EstimatedPoint> points = track(0.0, 1000.0, 2000.0, 3000.0);

        double[] s = ServiceSegmentGate.projectAll(points, STRAIGHT_NORTH);

        assertEquals(4, s.length);
        assertEquals(0.0, s[0], 30.0);
        assertEquals(1000.0, s[1], 30.0);
        assertEquals(2000.0, s[2], 30.0);
        assertEquals(3000.0, s[3], 30.0);
    }

    @Test
    @DisplayName("入参校验：空点列 / 空几何 / 区间反向 → 抛异常")
    void firstEntry_shouldRejectInvalidInput() {
        List<EstimatedPoint> points = track(1500.0);

        assertThrows(IllegalArgumentException.class,
                () -> ServiceSegmentGate.firstEntry(List.of(), STRAIGHT_NORTH, 1000.0, 3000.0));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceSegmentGate.firstEntry(points, null, 1000.0, 3000.0));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceSegmentGate.firstEntry(points, STRAIGHT_NORTH, 3000.0, 1000.0));
    }
}
