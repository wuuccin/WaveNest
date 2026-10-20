package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimAisConnector;
import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变向注入控制器单测（不起 Web 容器，直调方法）。
 *
 * <p>验收锚点：连点 10 次必触发（turnCount ＝ 10）、双触发模式可区分、定时触发到点生效。
 */
class TurnInjectControllerTest {

    private SimAisConnector sim;
    private TurnInjectController controller;

    @BeforeEach
    void setUp() {
        sim = new SimAisConnector(new RecordingPublisher());
        FieldInjector.applySimDefaults(sim);
        sim.start();
        controller = new TurnInjectController(sim);
    }

    @Test
    @DisplayName("连点 10 次必触发：每次航向 +10°，计数到 10")
    void tenClicksAlwaysTrigger() {
        double cog = 90.0;
        for (int i = 0; i < 10; i++) {
            ResponseEntity<Map<String, Object>> resp = controller.turn(10.0, null);
            assertEquals(200, resp.getStatusCode().value());
            double newCog = (double) resp.getBody().get("newCog");
            assertTrue(Math.abs(newCog - (cog + 10.0)) < 1e-9,
                    "第 " + (i + 1) + " 次点击未生效");
            cog = newCog;
        }
        assertEquals(10, controller.status().getBody().get("turnCount"));
        assertEquals(190.0, sim.currentCog(), 1e-9);
    }

    @Test
    @DisplayName("订单触发模式：带 orderId 时回显 triggerMode=order 且带订单号")
    void orderTriggerEchoesMode() {
        Map<String, Object> body = controller.turn(60.0, "ORD-20261016-01").getBody();
        assertEquals("order", body.get("triggerMode"));
        assertEquals("ORD-20261016-01", body.get("orderId"));
        assertEquals(150.0, body.get("newCog"));
    }

    @Test
    @DisplayName("手动触发模式：不带 orderId 时 triggerMode=manual")
    void manualTriggerEchoesMode() {
        Map<String, Object> body = controller.turn(30.0, null).getBody();
        assertEquals("manual", body.get("triggerMode"));
        assertEquals("", body.get("orderId"));
    }

    @Test
    @DisplayName("定时触发：到点自动注入并生效")
    void scheduledTurnFires() throws Exception {
        Map<String, Object> scheduled = controller.schedule(1, 45.0, "ORD-T").getBody();
        long id = (long) scheduled.get("id");
        assertEquals("scheduleTurn", scheduled.get("action"));
        assertEquals(1, controller.listSchedules().getBody().size(), "应登记 1 个待触发任务");

        Thread.sleep(1_600L);

        assertEquals(135.0, sim.currentCog(), 1e-9, "1 秒后应已自动 +45°");
        assertEquals(1, controller.status().getBody().get("turnCount"));
        assertEquals(0, controller.listSchedules().getBody().size(), "触发后应从列表移除");
        assertNotNull(id);
    }

    @Test
    @DisplayName("定时任务可取消：取消后到点不变向")
    void cancelsScheduledTurn() throws Exception {
        long id = (long) controller.schedule(1, 45.0, null).getBody().get("id");
        Map<String, Object> cancelled = controller.cancelSchedule(id).getBody();
        assertEquals(true, cancelled.get("cancelled"));

        Thread.sleep(1_400L);
        assertEquals(90.0, sim.currentCog(), 1e-9, "取消后不应变向");
        assertEquals(0, controller.status().getBody().get("turnCount"));
    }

    @Test
    @DisplayName("reset 清空计数与定时任务（可反复重演）")
    void resetClearsCountersAndSchedules() {
        controller.turn(10.0, null);
        controller.turn(10.0, null);
        controller.schedule(60, 30.0, null);

        Map<String, Object> body = controller.reset().getBody();
        assertEquals("reset", body.get("action"));
        assertEquals(0, controller.status().getBody().get("turnCount"));
        assertEquals(0, controller.status().getBody().get("pendingSchedules"));
        assertEquals(90.0, sim.currentCog(), 1e-9);
    }

    @Test
    @DisplayName("航速与航向直设端点仍可用（原有能力不回归）")
    void keepsOriginalEndpoints() {
        assertEquals("setCog", controller.setCog(200.0).getBody().get("action"));
        assertEquals(200.0, sim.currentCog(), 1e-9);

        assertEquals("setSog", controller.setSog(12.0).getBody().get("action"));
        assertEquals(200.0, (double) controller.status().getBody().get("cog"), 1e-9);
    }
}
