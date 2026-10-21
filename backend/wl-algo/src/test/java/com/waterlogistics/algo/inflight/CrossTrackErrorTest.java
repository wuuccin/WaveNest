package com.waterlogistics.algo.inflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 船侧横向偏差 XTE（《84》§2.1 退化版）单测。
 *
 * <p><b>期望值怎么来的</b>：主用例取正东向航迹（120.0,30.0）→（120.01,30.0），
 * 船置于其正北／正南各偏 0.001 度处。等距圆球近似下 <b>0.001 度纬 ＝ 111.1949 m</b>，
 * 且正东向航行的船偏北即<b>左侧</b>（面向东，左为北）。两者均由几何独立算出，
 * <b>不是抄实现结果</b>——本类第一版就是靠这种自我印证把符号写反的。
 */
class CrossTrackErrorTest {

    /** 0.001 度纬对应的米数（等距圆球近似） */
    private static final double ONE_MILLI_DEG_LAT_M = 111.1949;

    private static final double EPS = 0.05;

    /** 正东向航迹的两点经纬度 */
    private static final List<Double> LONS = List.of(120.0, 120.01);

    private static final List<Double> LATS = List.of(30.0, 30.0);

    /** 船在航迹正北 0.001 度：应判为左侧、符号为正 */
    @Test
    void signedM_shouldBePositiveWhenShipIsLeftOfEastboundTrack() {
        double signedXte = CrossTrackError.signedM(120.005, 30.001, LONS, LATS);

        assertTrue(signedXte > 0,
                "面向东航行时偏北即左侧，符号应为正（曾用航向差定符号导致此例反号），实为 " + signedXte);
        assertEquals(ONE_MILLI_DEG_LAT_M, Math.abs(signedXte), EPS, "垂距＝0.001 度纬的米数");
    }

    /** 船在航迹正南 0.001 度：应判为右侧、符号为负 */
    @Test
    void signedM_shouldBeNegativeWhenShipIsRightOfEastboundTrack() {
        double signedXte = CrossTrackError.signedM(120.005, 29.999, LONS, LATS);

        assertTrue(signedXte < 0, "面向东航行时偏南即右侧，符号应为负，实为 " + signedXte);
        assertEquals(ONE_MILLI_DEG_LAT_M, Math.abs(signedXte), EPS);
    }

    /** 符号不因船位在航迹哪一侧而翻转符号以外的量：两侧同距时绝对值相同 */
    @Test
    void absM_shouldBeSymmetricForEqualOffsets() {
        double north = CrossTrackError.absM(120.005, 30.001, LONS, LATS);
        double south = CrossTrackError.absM(120.005, 29.999, LONS, LATS);

        assertEquals(north, south, EPS, "等距左右偏移的偏差绝对值应相同");
    }

    /** 船正好在航迹上：偏差为零 */
    @Test
    void absM_shouldBeZeroWhenShipOnTrack() {
        assertEquals(0.0, CrossTrackError.absM(120.005, 30.0, LONS, LATS), 1e-6);
    }

    /** 走廊判定只看绝对值：越限与否与符号无关 */
    @Test
    void outsideCorridor_shouldIgnoreSign() {
        assertFalse(CrossTrackError.outsideCorridor(111.19, 150.0), "111 m ≤ 150 m 走廊内");
        assertTrue(CrossTrackError.outsideCorridor(111.19, 100.0), "111 m > 100 m 越限");
    }

    /** 走廊半宽非法须报错：0 或负值会让「永不越限」的判定静默成立 */
    @Test
    void outsideCorridor_shouldRejectNonPositiveXtl() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> CrossTrackError.outsideCorridor(10.0, 0.0));
        assertTrue(zero.getMessage().contains("XTL"), "须点明是走廊半宽 XTL：" + zero.getMessage());

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> CrossTrackError.outsideCorridor(10.0, -1.0));
        assertTrue(negative.getMessage().contains("XTL"),
                "负走廊半宽同样须点名 XTL：" + negative.getMessage());
    }

    /** 单点航迹无方向、无垂距可言，须拒绝 */
    @Test
    void signedM_shouldRejectSinglePointTrack() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CrossTrackError.signedM(120.005, 30.0, List.of(120.0), List.of(30.0)));

        assertTrue(thrown.getMessage().contains("至少需 2 点"),
                "须点明单点无法构成线段：" + thrown.getMessage());
    }

    /** 经纬度列长度不一致须拒绝，避免错位配对算出假偏差 */
    @Test
    void signedM_shouldRejectMismatchedColumns() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> CrossTrackError.signedM(120.005, 30.0, List.of(120.0, 120.01), List.of(30.0)));

        assertTrue(thrown.getMessage().contains("长度不一致"),
                "须点明两列长度不一致：" + thrown.getMessage());
    }

    /** 折线航迹：船落在某段中点上时该段垂距为零，不被其它段拉走 */
    @Test
    void signedM_shouldUseNearestSegmentOfPolyline() {
        List<Double> bentLons = List.of(120.0, 120.005, 120.01);
        List<Double> bentLats = List.of(30.0, 30.002, 30.0);

        // 第二段中点：(120.0075, 30.001)——船压在该点上时垂距应为 0
        double onMiddleSegment = CrossTrackError.absM(120.0075, 30.001, bentLons, bentLats);
        assertTrue(onMiddleSegment < 1.0,
                "船在第二段中点上时垂距应接近 0，实为 " + onMiddleSegment);

        // 同一经度但退到第二段所在纬线以南 0.002 度：垂距应为该纬向差的可观量级，
        // 且显著小于「到最近端点」的距离——说明走的是正交投影而非端点退化
        double offSegment = CrossTrackError.absM(120.0075, 30.0, bentLons, bentLats);
        assertTrue(offSegment > 50.0 && offSegment < 150.0,
                "斜段上的垂距应为几十米量级（实为 " + offSegment + " m），不应退化成到端点的 240 m");
    }

    /** 垂足落在段外：取到端点距离而非虚高的垂距 */
    @Test
    void signedM_shouldFallBackToEndpointWhenProjectionOutsideSegment() {
        // 船在起点正西 0.001 度，垂足必然落在段外 → 退化为「到起点距离」＝0.001 度经的米数
        double west = CrossTrackError.absM(119.999, 30.0, LONS, LATS);
        double expected = ONE_MILLI_DEG_LAT_M * Math.cos(Math.toRadians(30.0));

        assertEquals(expected, west, 1.0,
                "0.001 度经在纬度 30 处约 " + Math.round(expected) + " m，实为 " + west);
    }
}