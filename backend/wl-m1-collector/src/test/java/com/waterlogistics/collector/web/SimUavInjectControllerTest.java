package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimUavTelemetryConnector;
import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.TopicName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无人机注入端口测试（11-16 任务 3-8）。
 *
 * <p>判据：端口只造遥测数据、不碰判定阈值，非法入参给 400 与可读原因（不是 500），
 * 复位覆盖单架与全部两种范围。
 */
class SimUavInjectControllerTest {

    private SimUavTelemetryConnector sim;
    private SimUavInjectController controller;

    @BeforeEach
    void setUp() {
        sim = new SimUavTelemetryConnector(new RecordingPublisher());
        FieldInjector.applySimDefaults(sim);
        sim.start();
        controller = new SimUavInjectController(sim);
    }

    @Test
    @DisplayName("偏航注入：回显目标位移、方向、已施加位移与渐进说明")
    void offtrack_shouldEchoStateAndRampNote() {
        ResponseEntity<Map<String, Object>> response = controller.offtrack("UAV-01", 60.0, 90.0);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("injectOfftrack", body.get("action"));
        assertEquals(TopicName.DATA_UAV_TELEMETRY, body.get("topic"));
        assertEquals("UAV-01", body.get("uavId"));
        assertEquals(60.0, body.get("targetDeviationM"));
        assertEquals(0.0, body.get("currentDeviationM"));
        assertEquals(90.0, body.get("bearingDeg"));
        assertEquals(true, body.get("offtrackActive"));
        assertTrue(String.valueOf(body.get("rampNote")).contains("POSITION_JUMP"),
                "回显须写明为什么不是一步到位");
    }

    @Test
    @DisplayName("低电量注入：回显当前电量与继续递减的口径")
    void battery_shouldEchoPercent() {
        ResponseEntity<Map<String, Object>> response = controller.battery("UAV-02", 15.0);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("injectBattery", body.get("action"));
        assertEquals("UAV-02", body.get("uavId"));
        assertEquals(15.0, body.get("batteryPercent"));
        assertTrue(String.valueOf(body.get("effect")).contains("0.02"));
    }

    @Test
    @DisplayName("状态查询：四架机全列出，未注入的机偏航未生效")
    void status_shouldListWholeFleet() {
        controller.battery("UAV-04", 18.0);

        ResponseEntity<Map<String, Object>> response = controller.status();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(4, body.get("droneCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drones = (List<Map<String, Object>>) body.get("drones");
        assertEquals("UAV-04", drones.get(3).get("uavId"));
        assertEquals(18.0, drones.get(3).get("batteryPercent"));
        assertEquals(false, drones.get(0).get("offtrackActive"));
    }

    @Test
    @DisplayName("复位：单架与全部两种范围都返回 200 并回显")
    void clear_shouldSupportSingleAndAll() {
        controller.offtrack("UAV-01", 60.0, 90.0);
        controller.battery("UAV-03", 12.0);

        ResponseEntity<Map<String, Object>> single = controller.clear("UAV-01");
        assertEquals(200, single.getStatusCode().value());
        assertEquals("单架", single.getBody().get("scope"));
        assertEquals(0.0, single.getBody().get("targetDeviationM"));
        assertEquals(100.0, single.getBody().get("batteryPercent"));

        ResponseEntity<Map<String, Object>> all = controller.clear(null);
        assertEquals(200, all.getStatusCode().value());
        assertEquals("全部机", all.getBody().get("scope"));
        assertNotNull(all.getBody().get("drones"));
    }

    @Test
    @DisplayName("非法入参：400 且给出可读原因，不是 500")
    void invalidInput_shouldReturnBadRequest() {
        ResponseEntity<Map<String, Object>> unknown = controller.offtrack("UAV-09", 60.0, 90.0);
        assertEquals(400, unknown.getStatusCode().value());
        assertTrue(String.valueOf(unknown.getBody().get("error")).contains("未知机号"));

        ResponseEntity<Map<String, Object>> badPercent = controller.battery("UAV-01", 120.0);
        assertEquals(400, badPercent.getStatusCode().value());

        ResponseEntity<Map<String, Object>> badClear = controller.clear("UAV-42");
        assertEquals(400, badClear.getStatusCode().value());
    }

    @Test
    @DisplayName("端口不读也不改偏航阈值：仿真器只暴露位置与电量两类注入")
    void controllerDoesNotTouchThresholds() {
        // 判据是「只造数据」：端点类上不得出现阈值字段（阈值在 sys_param，由 M4 读取）
        for (var field : SimUavInjectController.class.getDeclaredFields()) {
            String name = field.getName();
            assertTrue(name.equals("sim"), "端点只应持有仿真器引用，实际还有字段 " + name);
        }
        assertThrows(NoSuchFieldException.class,
                () -> SimUavInjectController.class.getDeclaredField("offtrackWarningThreshold"));
    }
}
