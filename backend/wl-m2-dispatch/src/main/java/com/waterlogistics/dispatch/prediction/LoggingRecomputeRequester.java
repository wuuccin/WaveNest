package com.waterlogistics.dispatch.prediction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link RecomputeRequester} 的默认实现：<b>只留痕，不真调求解</b>（M2 · 卢肄文）。
 *
 * <p>存在的意义：让「预测刷新 → 发重算请求」这条驱动链<b>今天就能端到端跑通并可观测</b>
 * （日志 ＋ 内存留痕），同时不阻塞在「门面装配」与「求解上下文（哪单／哪机）」上。
 *
 * <p><b>待接（16 号）：</b>{@code wl-app} 装配 {@code RendezvousFacade} 后，
 * 新增一个门面实现替换本类 —— 届时它把 {@link RecomputeRequest} 补上
 * {@code droneId}／{@code stateSnapshot} 等求解上下文，构造
 * {@code rendezvous.solver.RendezvousRecalcRequest} 调
 * {@code RendezvousFacade.recompute(...)}，并按 {@code RendezvousRecalcResult} 推进后续。
 *
 * <p>留痕上限 {@value #MAX_ENTRIES} 条，仅供当日联调观察，<b>不落 {@code task_log}</b>：
 * {@code task_log.order_id} 是非空列，而重算信号只知「哪条船」、不知「哪一单」，
 * 订单归属由 {@code dispatch.queue} 掌握。待 6a 接上求解上下文（含 {@code orderId}）后，
 * 由承接方在落库时补该字段——本类不臆造 orderId，也不为落库放宽表约束。
 */
@Component
public class LoggingRecomputeRequester implements RecomputeRequester {

    private static final Logger log = LoggerFactory.getLogger(LoggingRecomputeRequester.class);

    /** 内存留痕上限（环形，仅联调观察用）。 */
    static final int MAX_ENTRIES = 200;

    private final Deque<RecomputeRequest> recent = new ArrayDeque<>();

    @Override
    public void requestRecompute(RecomputeRequest request) {
        synchronized (recent) {
            if (recent.size() >= MAX_ENTRIES) {
                recent.removeFirst();
            }
            recent.addLast(request);
        }
        log.info("重算请求：mmsi={} 版本={} 航向 {}° → {}°（变化 {}°，原因={}）",
                request.mmsi(), request.snapshotVersion(),
                String.format("%.1f", request.previousCourseDeg()),
                String.format("%.1f", request.currentCourseDeg()),
                String.format("%.1f", request.courseDeltaDeg()),
                request.triggerReason());
    }

    /** 已留痕的重算请求快照（按发生顺序）。 */
    public List<RecomputeRequest> recentRequests() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /** 已留痕条数。 */
    public int requestCount() {
        synchronized (recent) {
            return recent.size();
        }
    }
}
