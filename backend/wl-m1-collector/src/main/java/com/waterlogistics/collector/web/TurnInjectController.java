package com.waterlogistics.collector.web;

import com.waterlogistics.collector.simulator.SimAisConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 变向注入控制器（6b 触发源，10-16 增强）。
 *
 * <p>10-16 口径（〈10-16 并行任务〉杨添爻条目）：
 * <ul>
 *   <li><b>稳定可复现</b>：连点 10 次必触发、角度可设——注入是同步确定性操作，
 *       每次调用都立即改航向并持续发新航迹；本控制器累计注入次数与历史，
 *       供演示时当场核对（turnCount 应＝点击次数）；</li>
 *   <li><b>双触发模式</b>：①订单触发（带 orderId，代表剧情由订单推进）
 *       ②定时触发（schedule 端点，到点自动注入，演示无人值守推进用）。</li>
 * </ul>
 * 用法示例：
 * <pre>
 *   POST /sim/ship/turn?delta=45                    手动变向：右转 45 度
 *   POST /sim/ship/turn?delta=45&amp;orderId=ORD-001   订单触发变向
 *   POST /sim/ship/turn/schedule?delaySec=30&amp;delta=60   30 秒后自动变向 60 度
 *   GET  /sim/ship/turn/schedule                    查看待触发的定时任务
 *   DELETE /sim/ship/turn/schedule/{id}             取消某个定时任务
 *   POST /sim/ship/cog?cog=200 / sog?sog=12 / reset 原有能力不变
 *   GET  /sim/ship                                  状态（含注入计数，验收"连点 10 次必触发"）
 * </pre>
 */
@RestController
@RequestMapping("/sim/ship")
public class TurnInjectController {

    private final SimAisConnector sim;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sim-turn-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 注入总次数（验收锚点：连点 10 次 → turnCount=10） */
    private final AtomicInteger turnCount = new AtomicInteger();
    private final AtomicLong scheduleSeq = new AtomicLong();
    /** 待触发的定时任务：id → future */
    private final ConcurrentHashMap<Long, ScheduledTurn> pending = new ConcurrentHashMap<>();

    /** 一次定时变向任务 */
    public record ScheduledTurn(long id, double deltaDeg, String orderId, long fireAtMs,
                                ScheduledFuture<?> future) {
    }

    public TurnInjectController(SimAisConnector sim) {
        this.sim = sim;
    }

    // ===== 模式一：手动 / 订单触发（同步、确定性）=====

    /**
     * 注入变向：在当前航向上叠加 delta（度，正=右转），此后持续按新航向发航迹。
     *
     * @param delta   航向改变量（度）
     * @param orderId 可选；提供时视为「订单触发」模式，否则为「手动」
     */
    @PostMapping("/turn")
    public ResponseEntity<Map<String, Object>> turn(@RequestParam double delta,
                                                    @RequestParam(required = false) String orderId) {
        double newCog = doTurn(delta);
        return ResponseEntity.ok(Map.of(
                "action", "turn",
                "triggerMode", orderId != null ? "order" : "manual",
                "orderId", orderId == null ? "" : orderId,
                "deltaDeg", delta,
                "newCog", newCog,
                "turnCount", turnCount.get()));
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

    /** 重置到起点（重演开场；同时清注入计数与定时任务，保证可复现）。 */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        sim.reset();
        turnCount.set(0);
        cancelAll();
        return ResponseEntity.ok(Map.of("action", "reset", "cog", sim.currentCog(),
                "turnCount", 0));
    }

    /** 查看当前仿真状态（含注入计数——演示验收"连点 10 次必触发"就看这个数）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "cog", sim.currentCog(),
                "turnCount", turnCount.get(),
                "pendingSchedules", pending.size()));
    }

    // ===== 模式二：定时触发（无人值守推进）=====

    /** 定时变向：delaySec 秒后自动注入一次 delta。返回任务 id，可查可取消。 */
    @PostMapping("/turn/schedule")
    public ResponseEntity<Map<String, Object>> schedule(@RequestParam long delaySec,
                                                        @RequestParam double delta,
                                                        @RequestParam(required = false) String orderId) {
        long id = scheduleSeq.incrementAndGet();
        long fireAt = System.currentTimeMillis() + delaySec * 1000L;
        ScheduledFuture<?> future = scheduler.schedule(
                () -> doTurn(delta), delaySec, TimeUnit.SECONDS);
        pending.put(id, new ScheduledTurn(id, delta, orderId, fireAt, future));
        return ResponseEntity.ok(Map.of(
                "action", "scheduleTurn",
                "id", id, "delaySec", delaySec, "deltaDeg", delta,
                "orderId", orderId == null ? "" : orderId,
                "fireAtMs", fireAt));
    }

    /** 查看全部待触发的定时任务。 */
    @GetMapping("/turn/schedule")
    public ResponseEntity<List<ScheduledTurn>> listSchedules() {
        purgeDone();
        return ResponseEntity.ok(List.copyOf(pending.values()));
    }

    /** 取消一个定时任务。 */
    @DeleteMapping("/turn/schedule/{id}")
    public ResponseEntity<Map<String, Object>> cancelSchedule(@PathVariable long id) {
        ScheduledTurn t = pending.remove(id);
        boolean cancelled = t != null && t.future().cancel(false);
        return ResponseEntity.ok(Map.of("action", "cancelSchedule", "id", id,
                "cancelled", cancelled));
    }

    // ===== 内部 =====

    /** 统一注入入口：手动/订单/定时三路都汇到这里，保证计数与行为一致。 */
    private double doTurn(double delta) {
        double newCog = sim.injectTurn(delta);
        turnCount.incrementAndGet();
        return newCog;
    }

    /** 清理全部定时任务（reset 时用）。 */
    private void cancelAll() {
        pending.values().forEach(t -> t.future().cancel(false));
        pending.clear();
    }

    /** 清掉已触发完毕的任务（列表接口不残留历史）。 */
    private void purgeDone() {
        pending.values().removeIf(t -> t.future().isDone());
    }
}
