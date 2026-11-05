package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionQueryService} 单测（阶段二 1，11-02）。
 *
 * <p>判据：① 摘要条数等于 {@code trackedShipCount()}；② 分页口径（page 从 1 起、
 * size 默认 50／上限 200）归一正确；③ 单船点列与预测快照同源；④ 有效期剩余不为负。
 * 不依赖 Spring／MQ／DB：直接 new 出真实 {@link PredictionService}（纯算法对象）。
 */
class PredictionQueryServiceTest {

    private static final long T0 = 1_760_000_000_000L;

    private PredictionService predictionService;
    private PredictionQueryService queryService;

    @BeforeEach
    void setUp() {
        PredictionSettings settings = new PredictionSettings(
                30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        predictionService = new PredictionService(settings, new LoggingRecomputeRequester());
        queryService = new PredictionQueryService(predictionService);
    }

    private static AisMessage ais(String mmsi, long ts) {
        return new AisMessage(mmsi, "测试船", 121.4700, 30.5000, 10.0, 0.0, ts);
    }

    @Test
    @DisplayName("判据①：摘要条数等于在航船数，按 MMSI 升序，字段来自快照")
    void ships_shouldListAllTrackedShips() {
        predictionService.refresh(ais("413000003", T0));
        predictionService.refresh(ais("413000001", T0));
        predictionService.refresh(ais("413000002", T0));

        PredictionQueryService.ShipsResponse resp = queryService.ships(1, 50);

        assertEquals(3, resp.total(), "total 为全量在航船数");
        assertEquals(predictionService.trackedShipCount(), resp.total());
        assertEquals(List.of("413000001", "413000002", "413000003"),
                resp.ships().stream().map(PredictionQueryService.PredictionShipSummary::mmsi).toList());

        PredictionQueryService.PredictionShipSummary first = resp.ships().get(0);
        assertEquals(1L, first.version());
        assertEquals("VALID", first.premise());
        assertEquals(11, first.pointCount(), "600 s / 60 s + 1 = 11 点");
        assertFalse(first.validityRemainingSec() < 0, "有效期剩余不得为负");
    }

    @Test
    @DisplayName("判据②：分页归一——page<1→1、size≤0→50、size>200→200，越界页返回空")
    void ships_shouldNormalizePaging() {
        for (int i = 1; i <= 3; i++) {
            predictionService.refresh(ais("41300000" + i, T0));
        }

        PredictionQueryService.ShipsResponse page1 = queryService.ships(1, 2);
        assertEquals(2, page1.ships().size());
        assertEquals(3, page1.total());
        assertEquals(1, page1.page());
        assertEquals(2, page1.size());

        PredictionQueryService.ShipsResponse page2 = queryService.ships(2, 2);
        assertEquals(1, page2.ships().size());

        PredictionQueryService.ShipsResponse beyond = queryService.ships(3, 2);
        assertEquals(0, beyond.ships().size(), "越界页返回空列表，不报错");

        PredictionQueryService.ShipsResponse normalized = queryService.ships(0, 999);
        assertEquals(1, normalized.page(), "page 小于 1 按 1 处理");
        assertEquals(200, normalized.size(), "size 上限 200");
        assertEquals(3, normalized.ships().size());

        PredictionQueryService.ShipsResponse defaultSize = queryService.ships(1, 0);
        assertEquals(50, defaultSize.size(), "size≤0 取默认 50");
    }

    @Test
    @DisplayName("判据③：单船点列与预测快照同源同值")
    void detail_shouldReturnSamePointsAsSnapshot() {
        PredictionSnapshot snapshot = predictionService.refresh(ais("413000001", T0));

        PredictionQueryService.PredictionDetail detail =
                queryService.detail("413000001").orElseThrow();

        assertEquals(snapshot.mmsi(), detail.mmsi());
        assertEquals(snapshot.version(), detail.version());
        assertEquals(snapshot.t0Ms(), detail.t0Ms());
        assertEquals(snapshot.courseDeg(), detail.courseDeg());
        assertEquals(snapshot.validitySec(), detail.validitySec());
        assertEquals(snapshot.premise(), detail.premise());
        assertEquals(snapshot.points(), detail.points(), "点列须与 WS 帧同一来源");
    }

    @Test
    @DisplayName("未跟踪的船 → 空 Optional（由控制层映射 404）")
    void detail_shouldBeEmptyForUnknownShip() {
        assertTrue(queryService.detail("999999999").isEmpty());
    }

    @Test
    @DisplayName("判据④：过期快照的有效期剩余截断到 0")
    void validityRemaining_shouldClampToZero() {
        PredictionSnapshot snapshot = predictionService.refresh(ais("413000001", T0));

        // T0 远早于当前时刻，剩余必为 0（不得出现负数）
        assertEquals(0L, PredictionQueryService.remainingValiditySec(
                snapshot, T0 + snapshot.validitySec() * 1000L + 5_000L));
    }
}
