package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimWeatherConnector;
import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 气象注入端口单测（不起 Web 容器，直调方法）。
 *
 * <p>验收锚点：注入一次回显「发了几个位置」＝网格点数，且这几个位置互不相同
 * ——回显本身就是给验收方核对 U-05 甲案粒度用的，不能只回一个数。
 */
class WeatherInjectControllerTest {

    private SimWeatherConnector sim;
    private RecordingPublisher publisher;
    private WeatherInjectController controller;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        sim = new SimWeatherConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
        publisher.clear();
        controller = new WeatherInjectController(sim);
    }

    @Test
    @DisplayName("注入 11 m/s：回显发 4 个位置，实际也发 4 条")
    void injectReportsMultiplePositions() {
        ResponseEntity<Map<String, Object>> resp = controller.inject(11.0, 135.0, null, true);

        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("injectWeather", body.get("action"));
        assertEquals("data.weather", body.get("topic"));
        assertEquals(4, body.get("publishedCount"));
        assertEquals(11.0, body.get("windMs"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grid = (List<Map<String, Object>>) body.get("gridPoints");
        assertEquals(4, grid.size(), "回显应给出 4 个网格点，便于核对'按位置多条'");

        assertEquals(4, publisher.weather().size(), "实际发出的条数应等于网格点数");
    }

    @Test
    @DisplayName("非法风速返回 400 与原因，不是 500")
    void illegalWindReturns400() {
        ResponseEntity<Map<String, Object>> resp = controller.inject(-3.0, 135.0, null, true);

        assertEquals(400, resp.getStatusCode().value(), "调用方入参问题应是 400，不是 500");
        assertNotNull(resp.getBody());
        assertTrue(String.valueOf(resp.getBody().get("error")).contains("风速"));
        assertEquals(0, publisher.weather().size(), "非法入参不应发出消息");
    }

    @Test
    @DisplayName("阵风低于风速返回 400")
    void gustBelowWindReturns400() {
        ResponseEntity<Map<String, Object>> resp = controller.inject(10.0, 135.0, 6.0, true);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("状态端点回显偏置与网格点数；清除后偏置归零")
    void statusAndClearBias() {
        controller.inject(11.0, 135.0, null, true);

        Map<String, Object> s = controller.status().getBody();
        assertNotNull(s);
        assertEquals(true, s.get("biasActive"));
        assertEquals(4, s.get("gridPointCount"));
        assertEquals(1L, s.get("injectedCount"));

        controller.clearBias();
        Map<String, Object> after = controller.status().getBody();
        assertNotNull(after);
        assertEquals(false, after.get("biasActive"));
    }

    @Test
    @DisplayName("persist=false 时不改后续海况（偏置不激活）")
    void transientInjectLeavesNoBias() {
        controller.inject(11.0, 135.0, null, false);
        Map<String, Object> s = controller.status().getBody();
        assertNotNull(s);
        assertEquals(false, s.get("biasActive"));
    }
}
