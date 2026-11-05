package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AisObservationFilter} 单测（A 组 · {@code 80} §3.2.3 步骤 A）。
 *
 * <p>验证点：去重、乱序排序、正常航迹全接受、<b>单点野值被剔</b>、
 * <b>真实机动被保留</b>（低速大转向不会误剔——这是文档强调"pending 不立即丢弃"的原因）、
 * 以及丢包质量标记。
 */
class AisObservationFilterTest {

    private static final String MMSI = "413000001";
    private static final double BASE_LON = 115.85;
    private static final double BASE_LAT = 28.60;

    /** 北向匀速的一条航迹：每秒北移约 5.14 m（10 节）。 */
    private static AisMessage northbound(int second, double extraLatDeg) {
        double lat = BASE_LAT + extraLatDeg
                + 5.144_444 * second / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        return new AisMessage(MMSI, "测试船", BASE_LON, lat, 10.0, 0.0, 1_000L + second * 5_000L);
    }

    @Test
    @DisplayName("去重：相同 (mmsi, floor(ts)) 的重复报文只保留一条")
    void duplicateReports_shouldBeCollapsed() {
        AisMessage a = northbound(0, 0);
        AisMessage duplicate = northbound(0, 0);      // 同 ts、同位置

        AisObservationFilter.Result result =
                AisObservationFilter.filter(List.of(a, duplicate), AisObservationFilter.Params.defaults());

        assertEquals(1, result.accepted().size());
        assertEquals(1, result.dropped().size());
        assertEquals(AisObservationFilter.Quality.OK, result.quality());
    }

    @Test
    @DisplayName("乱序输入按时间升序输出")
    void outOfOrder_shouldBeSorted() {
        List<AisMessage> raw = new ArrayList<>();
        raw.add(northbound(2, 0));
        raw.add(northbound(0, 0));
        raw.add(northbound(1, 0));

        AisObservationFilter.Result result =
                AisObservationFilter.filter(raw, AisObservationFilter.Params.defaults());

        List<Long> timestamps = result.accepted().stream().map(AisMessage::ts).toList();
        assertEquals(List.of(1_000L, 6_000L, 11_000L), timestamps);
    }

    @Test
    @DisplayName("正常匀速航迹：全部接受，质量为 OK")
    void normalTrack_shouldAllPass() {
        List<AisMessage> raw = List.of(northbound(0, 0), northbound(1, 0), northbound(2, 0),
                northbound(3, 0), northbound(4, 0));

        AisObservationFilter.Result result =
                AisObservationFilter.filter(raw, AisObservationFilter.Params.defaults());

        assertEquals(5, result.accepted().size());
        assertTrue(result.dropped().isEmpty());
        assertEquals(AisObservationFilter.Quality.OK, result.quality());
        assertEquals(0L, result.gapAccumMs());
    }

    @Test
    @DisplayName("★ 单点野值被剔除（一步跳 1000 m → 隐含速度 200 m/s 远超门限）")
    void singleOutlier_shouldBeDropped() {
        double outlierLat = BASE_LAT + 1000.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        AisMessage outlier = new AisMessage(MMSI, "测试船", BASE_LON, outlierLat, 10.0, 0.0, 6_000L);

        List<AisMessage> raw = List.of(northbound(0, 0), outlier, northbound(2, 0));

        AisObservationFilter.Result result =
                AisObservationFilter.filter(raw, AisObservationFilter.Params.defaults());

        assertEquals(2, result.accepted().size(), "野值应被剔除，前后两点保留");
        assertEquals(1, result.dropped().size());
        assertEquals(outlier.ts(), result.dropped().get(0).ts());
    }

    @Test
    @DisplayName("★ 真实机动被保留：先一个可疑大位移，随后连续自洽 → 整段判机动接受")
    void realManeuver_shouldBeKept() {
        // 从原点跳 200 m（40 m/s > 25 门限）→ 可疑；随后每 5 s 前移 15 m（3 m/s）→ 互相自洽
        double jumpLat = BASE_LAT + 200.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
        double stepDeg = 15.0 / ConstantVelocityPredictor.METERS_PER_DEG_LAT;

        List<AisMessage> raw = List.of(
                new AisMessage(MMSI, "t", BASE_LON, BASE_LAT, 0.5, 0.0, 1_000L),
                new AisMessage(MMSI, "t", BASE_LON, jumpLat, 0.6, 0.0, 6_000L),
                new AisMessage(MMSI, "t", BASE_LON, jumpLat + stepDeg, 0.6, 0.0, 11_000L),
                new AisMessage(MMSI, "t", BASE_LON, jumpLat + 2 * stepDeg, 0.6, 0.0, 16_000L),
                new AisMessage(MMSI, "t", BASE_LON, jumpLat + 3 * stepDeg, 0.6, 0.0, 21_000L));

        AisObservationFilter.Result result =
                AisObservationFilter.filter(raw, AisObservationFilter.Params.defaults());

        assertEquals(5, result.accepted().size(),
                "确认长度 ≥ confirmCount 且内部自洽 → 判真实机动，整段接受");
        assertTrue(result.dropped().isEmpty());
    }

    @Test
    @DisplayName("丢包：Δt 超过 3 倍标称间隔 → 质量标 EXTRAPOLATED 并累计 gap")
    void largeGap_shouldMarkExtrapolated() {
        // 0 s 与 60 s 两点，Δt=60 s > 3×5 s=15 s
        List<AisMessage> raw = List.of(
                new AisMessage(MMSI, "t", BASE_LON, BASE_LAT, 10.0, 0.0, 1_000L),
                new AisMessage(MMSI, "t", BASE_LON, BASE_LAT, 10.0, 0.0, 61_000L));

        AisObservationFilter.Result result =
                AisObservationFilter.filter(raw, AisObservationFilter.Params.defaults());

        assertEquals(AisObservationFilter.Quality.EXTRAPOLATED, result.quality());
        assertTrue(result.gapAccumMs() >= 60_000L);
    }

    @Test
    @DisplayName("丢包累计超过 gapMaxSec → 质量标 INSUFFICIENT（功能 1 据此暂缓）")
    void accumulatedGap_shouldBecomeInsufficient() {
        AisObservationFilter.Params strict = new AisObservationFilter.Params(
                30L, 25.0, 20.0, 1.5, 5.0, 3, 6, 1.0, 5.0, 30L);   // gapFactor=1、gapMax=30 s

        List<AisMessage> raw = List.of(
                new AisMessage(MMSI, "t", BASE_LON, BASE_LAT, 10.0, 0.0, 1_000L),
                new AisMessage(MMSI, "t", BASE_LON, BASE_LAT, 10.0, 0.0, 61_000L));

        AisObservationFilter.Result result = AisObservationFilter.filter(raw, strict);

        assertEquals(AisObservationFilter.Quality.INSUFFICIENT, result.quality());
    }

    @Test
    @DisplayName("空输入 → 空结果且质量为 OK（不抛异常）")
    void emptyInput_shouldReturnEmpty() {
        AisObservationFilter.Result result =
                AisObservationFilter.filter(List.of(), AisObservationFilter.Params.defaults());

        assertTrue(result.accepted().isEmpty());
        assertTrue(result.dropped().isEmpty());
        assertEquals(AisObservationFilter.Quality.OK, result.quality());
    }

    @Test
    @DisplayName("入参校验：null 列表 / null 参数 → 抛异常；参数自校验生效")
    void invalidInput_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> AisObservationFilter.filter(null, AisObservationFilter.Params.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> AisObservationFilter.filter(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new AisObservationFilter.Params(30L, 25.0, 20.0, 1.5, 5.0, 3, 2, 3.0, 5.0, 120L));
    }
}
