package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link PredictionController} 单测：路径与错误码口径（不启 Spring MVC）。
 */
class PredictionControllerTest {

    private static final long T0 = 1_760_000_000_000L;

    private PredictionQueryService queryService;
    private PredictionController controller;

    @BeforeEach
    void setUp() {
        PredictionSettings settings = new PredictionSettings(
                30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        PredictionService predictionService =
                new PredictionService(settings, new LoggingRecomputeRequester());
        queryService = new PredictionQueryService(predictionService);
        controller = new PredictionController(queryService);
    }

    @Test
    @DisplayName("路径常量：列表 /api/prediction/ships，单船 /api/prediction/ships/{mmsi}")
    void paths_shouldBeStable() {
        assertEquals("/api/prediction/ships", PredictionController.PREDICTION_SHIPS);
        assertEquals("/api/prediction/ships/{mmsi}", PredictionController.PREDICTION_SHIP);
    }

    @Test
    @DisplayName("空库列表不报 404，返回 total=0 的空列表")
    void ships_shouldReturnEmptyListNot404() {
        PredictionQueryService.ShipsResponse resp = controller.ships(1, 50);

        assertEquals(0, resp.total());
        assertEquals(0, resp.ships().size());
    }

    @Test
    @DisplayName("单船不存在 → 404（该船尚无预测快照）")
    void ship_shouldReturn404WhenUnknown() {
        queryService = new PredictionQueryService(new PredictionService(
                new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L),
                new LoggingRecomputeRequester()));
        controller = new PredictionController(queryService);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.ship("999999999"));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("999999999"));
    }

    @Test
    @DisplayName("单船存在 → 返回完整点列")
    void ship_shouldReturnDetailWhenTracked() {
        PredictionService predictionService = new PredictionService(
                new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L),
                new LoggingRecomputeRequester());
        predictionService.refresh(new AisMessage(
                "413000001", "测试船", 121.4700, 30.5000, 10.0, 0.0, T0));
        controller = new PredictionController(new PredictionQueryService(predictionService));

        PredictionQueryService.PredictionDetail detail = controller.ship("413000001");

        assertEquals("413000001", detail.mmsi());
        assertEquals(11, detail.points().size());
    }
}
