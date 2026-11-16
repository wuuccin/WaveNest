package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimUavTelemetryConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无人机遥测注入端口（11-16 任务 3-8 · 缺陷 7）。
 *
 * <p><b>这是什么</b>：给演示与联调一个「把这架机弄偏／把电量打低」的入口。原来仿真遥测只有
 * 一种行为——绕机巢巡航、电量按拍递减，想演示「偏航告警」「低电量返航」就没法造出来，
 * 评审看不到那两条判据真的在工作。本端点只是 {@link SimUavTelemetryConnector} 的远程开关，
 * 发出去的仍是标准 {@code data.uav.telemetry}。
 *
 * <p><b>不做什么（重要）</b>：
 * <ul>
 *   <li>不放宽偏航阈值：{@code offtrackWarningThreshold}／{@code offtrackCriticalThreshold}
 *       仍在 {@code sys_param}，本端口只改位置；</li>
 *   <li>不做告警判定：偏航与低电量的判定归 M4（宋冰鑫），本端口只造数据，
 *       注入后链路是否告警由 4-2 统一验收；</li>
 *   <li>不改默认行为：不调用这些端点时，仿真器与现在完全一致（四机绕巢、电量递减）。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   POST   /sim/uav/offtrack?uavId=UAV-01&amp;deviationM=60&amp;bearingDeg=90   把这架机推开 60 m（正东）
 *   POST   /sim/uav/battery?uavId=UAV-01&amp;percent=15                      电量打到 15%
 *   GET    /sim/uav                                                      看四架的注入状态与已施加位移
 *   DELETE /sim/uav/injections?uavId=UAV-01                              复位单架（位移退回、电量回满）
 *   DELETE /sim/uav/injections                                           复位全部
 * </pre>
 *
 * <p>位移是<b>按拍渐进</b>的（每拍 ≤ 12 m），调用后要等几拍 {@code currentDeviationM} 才追平
 * {@code targetDeviationM}——原因见 {@link SimUavTelemetryConnector} 类注释：
 * 瞬移会被 M4 清洗判成 {@code POSITION_JUMP} 而丢弃。
 */
@RestController
@RequestMapping("/sim/uav")
public class SimUavInjectController {

    private final SimUavTelemetryConnector sim;

    public SimUavInjectController(SimUavTelemetryConnector sim) {
        this.sim = sim;
    }

    /**
     * 注入偏航：把指定机整体推开若干米。
     *
     * @param uavId       机号（UAV-01 起）
     * @param deviationM  目标位移（米，默认 60——越过偏航严重阈值 50 m）
     * @param bearingDeg  方向（度，0＝正北，90＝正东；缺省 90）
     * @return 回显：目标位移、已施加位移、方向、电量
     */
    @PostMapping("/offtrack")
    public ResponseEntity<Map<String, Object>> offtrack(@RequestParam String uavId,
                                                        @RequestParam(defaultValue = "60") double deviationM,
                                                        @RequestParam(required = false) Double bearingDeg) {
        try {
            double bearing = bearingDeg != null ? bearingDeg : 90.0;
            SimUavTelemetryConnector.Injection state = sim.injectOfftrack(uavId, deviationM, bearing);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "injectOfftrack");
            body.put("topic", "data.uav.telemetry");
            body.put("effect", "位置渐进外推，不改偏航阈值");
            body.putAll(stateView(state));
            body.put("rampNote", "每拍最多偏移约 12 m，约 " + (int) Math.ceil(deviationM / 12.0)
                    + " 拍后追平；瞬移会被清洗判 POSITION_JUMP 丢弃，故不一步到位");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "action", "injectOfftrack",
                    "error", e.getMessage()));
        }
    }

    /**
     * 注入低电量：把指定机电量直接打到 percent%。
     *
     * @param uavId   机号
     * @param percent 目标电量（0–100，默认 15——低于触发阈值 20、高于严重阈值 10）
     * @return 回显：当前电量
     */
    @PostMapping("/battery")
    public ResponseEntity<Map<String, Object>> battery(@RequestParam String uavId,
                                                       @RequestParam(defaultValue = "15") double percent) {
        try {
            SimUavTelemetryConnector.Injection state = sim.injectBattery(uavId, percent);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "injectBattery");
            body.put("topic", "data.uav.telemetry");
            body.put("effect", "电量一步到位，此后仍按每拍 0.02% 递减（下限 5%）");
            body.putAll(stateView(state));
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "action", "injectBattery",
                    "error", e.getMessage()));
        }
    }

    /** 当前注入状态：四架机各自的目标位移、已施加位移、方向与电量。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        List<Map<String, Object>> drones = sim.injections().stream()
                .map(SimUavInjectController::stateView)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", "data.uav.telemetry");
        body.put("droneCount", drones.size());
        body.put("drones", drones);
        return ResponseEntity.ok(body);
    }

    /**
     * 复位：偏航位移渐进退回、电量回满。
     *
     * @param uavId 可空；空则复位全部
     */
    @DeleteMapping("/injections")
    public ResponseEntity<Map<String, Object>> clear(@RequestParam(required = false) String uavId) {
        try {
            if (uavId == null || uavId.isBlank()) {
                sim.clearInjections();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("action", "clearUavInjections");
                body.put("scope", "全部机");
                body.put("drones", sim.injections().stream().map(SimUavInjectController::stateView).toList());
                return ResponseEntity.ok(body);
            }
            SimUavTelemetryConnector.Injection state = sim.clearInjection(uavId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "clearUavInjections");
            body.put("scope", "单架");
            body.putAll(stateView(state));
            body.put("rampNote", "偏航位移按拍退回原点，不回跳");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "action", "clearUavInjections",
                    "error", e.getMessage()));
        }
    }

    /** 单架状态 → 回显字段（前端注入面板直接吃这几个键）。 */
    private static Map<String, Object> stateView(SimUavTelemetryConnector.Injection state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uavId", state.uavId());
        m.put("targetDeviationM", state.targetDeviationM());
        m.put("currentDeviationM", state.currentDeviationM());
        m.put("bearingDeg", state.bearingDeg());
        m.put("batteryPercent", state.batteryPercent());
        m.put("offtrackActive", state.targetDeviationM() > 0);
        return m;
    }
}
