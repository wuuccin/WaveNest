package com.waterlogistics.dispatch.prediction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link RecomputeRequester} 的默认实现：<b>收到信号即真触发一版重算</b>（M2 · 卢肄文）。
 *
 * <p><b>F3「重算链接通」（10-23 任务清单 §三 卢肄文段）</b>：本类原先只打日志 ＋ 内存留痕，
 * 信号到舱门为止；现在把它接到 {@link PredictionService#recompute(String)}，
 * 让「预测发现前提变了 → 发重算信号 → 预测确实重算并广播新版本」这条闭环的前一半真正闭上。
 *
 * <p><b>类名保留</b>（当日任务明确要求）：本类同时承担两件事——留痕（{@link #recentRequests()}／
 * {@link #requestCount()}，供联调观察与单测断言）与触发。名字里的 Logging 是历史成因，
 * 不在当日改动范围内，改名会让 {@code dispatch} 侧的装配与既有测试全动一遍。
 *
 * <p><b>为什么用 setter 注入而不是构造器</b>：{@link PredictionService} 的构造器要本接口的 Bean，
 * 本类若在构造器里要 {@code PredictionService} 就成环，Spring Boot 4 默认禁环、启动即失败。
 * 故构造器保持无参（单测可直接 {@code new}），再由标注 {@code @Lazy} 的 setter 注入一个
 * 延迟代理——装配期只登记代理，调用期才解引用，环在时间维度上被拆开。
 *
 * <p><b>失败口径</b>：本方法在预测刷新热路径上被调用，下游不可用不得影响预测本身，
 * 故一切异常在此吞掉并记日志（接口约定即如此）。
 *
 * <p><b>待接（后续）：</b>{@code RendezvousFacade} 装配完成后，由 {@code wl-app} 提供一个
 * 门面实现替换本 Bean，届时信号可直达 M3 的 6a 会面点重算；本类不回发信号、不落
 * {@code task_log}（{@code task_log.order_id} 非空，而信号只知「哪条船」）。
 */
@Component
public class LoggingRecomputeRequester implements RecomputeRequester {

    private static final Logger log = LoggerFactory.getLogger(LoggingRecomputeRequester.class);

    /** 内存留痕上限（环形，仅联调观察用）。 */
    static final int MAX_ENTRIES = 200;

    /** 重算信号留痕（按发生顺序）。 */
    private final Deque<RecomputeRequest> recent = new ArrayDeque<>();

    /** 闭环回跳点；装配完成前为 {@code null}（此时退化为「只留痕」，不抛异常）。 */
    private volatile PredictionService predictionService;

    /**
     * 注入重算落点。<b>刻意不加 {@code @Autowired(required = true)} 的构造器版本</b>，
     * 见类注释「为什么用 setter 注入」。
     */
    @Autowired
    void bindPredictionService(@Lazy PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @Override
    public void requestRecompute(RecomputeRequest request) {
        record(request);
        log.info("重算请求：mmsi={} 版本={} 航向 {}° → {}°（变化 {}°，原因={}）",
                request.mmsi(), request.snapshotVersion(),
                String.format("%.1f", request.previousCourseDeg()),
                String.format("%.1f", request.currentCourseDeg()),
                String.format("%.1f", request.courseDeltaDeg()),
                request.triggerReason());

        PredictionService driver = this.predictionService;
        if (driver == null) {
            log.warn("重算落点未装配，信号仅留痕：mmsi={}", request.mmsi());
            return;
        }

        try {
            Optional<PredictionSnapshot> recomputed = driver.recompute(request.mmsi());
            if (recomputed.isPresent()) {
                PredictionSnapshot snapshot = recomputed.get();
                log.info("重算完成：mmsi={} 新版本={} 点数={} 有效期={}s，已广播 ship_traj",
                        snapshot.mmsi(), snapshot.version(),
                        snapshot.points().size(), snapshot.validitySec());
            } else {
                log.warn("重算跳过：mmsi={} 尚无可用观测", request.mmsi());
            }
        } catch (RuntimeException e) {
            // 预测热路径：下游算不动不能反过来带停整条链
            log.warn("重算失败，已忽略：mmsi={} 原因={}", request.mmsi(), e.getMessage());
        }
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

    private void record(RecomputeRequest request) {
        synchronized (recent) {
            if (recent.size() >= MAX_ENTRIES) {
                recent.removeFirst();
            }
            recent.addLast(request);
        }
    }
}
