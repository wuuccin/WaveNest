package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimAisConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 变向注入控制器（6b 触发源）。
 *
 * <p>验收口径（《编码分工表》①）：点「变向」后航向角改变并【持续】发新航迹。
 * 用法示例：
 * <pre>
 *   POST /sim/ship/turn?delta=45      船右转 45 度
 *   POST /sim/ship/cog?cog=200        直接设航向 200 度
 *   POST /sim/ship/sog?sog=12         设航速 12 节
 *   POST /sim/ship/reset              船回起点（重演开场）
 *   GET  /sim/ship                    查看当前状态
 * </pre>
 */
@RestController
@RequestMapping("/sim/ship")
public class TurnInjectController {

    private final SimAisConnector sim;

    public TurnInjectController(SimAisConnector sim) {
        this.sim = sim;
    }

    /** 注入变向：在当前航向上叠加 delta（度，正=右转），此后持续按新航向发航迹。 */
    @PostMapping("/turn")
    public ResponseEntity<Map<String, Object>> turn(@RequestParam double delta) {
        double newCog = sim.injectTurn(delta);
        return ResponseEntity.ok(Map.of(
                "action", "turn",
                "deltaDeg", delta,
                "newCog", newCog));
    }

    /** 直接设置航向（度）。 */
    @PostMapping("/cog")
    public ResponseEntity<Map<String, Object>> setCog(@RequestParam double cog) {
        double newCog = sim.setCog(cog);
        return ResponseEntity.ok(Map.of("action", "setCog", "newCog", newCog));
    }

    /** 设置航速（节）。 */
    @PostMapping("/sog")
    public ResponseEntity<Map<String, Object>> setSog(@RequestParam double sog) {
        sim.setSog(sog);
        return ResponseEntity.ok(Map.of("action", "setSog", "sogKnots", sog));
    }

    /** 重置到起点（重演开场）。 */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        sim.reset();
        return ResponseEntity.ok(Map.of("action", "reset", "cog", sim.currentCog()));
    }

    /** 查看当前仿真状态。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("cog", sim.currentCog()));
    }
}
