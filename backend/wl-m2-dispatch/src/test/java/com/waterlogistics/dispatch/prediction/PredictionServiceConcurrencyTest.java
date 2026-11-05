package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waterlogistics.contract.mq.AisMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionService} 版本并发回归（HIGH.23）。
 *
 * <p><b>缺陷复现原理</b>：修复前「取版本」与「登记快照」之间无同步——
 * 线程 A 取到 v1、线程 B 取到 v2 并先登记，A 再登记时就会<b>低版本覆盖高版本</b>；
 * 结束时 {@code snapshotOf(mmsi).version() < 分配出的最大版本}。
 *
 * <p><b>判据</b>：N 线程并发刷新同一船，结束后登记的快照版本必须等于
 * 版本计数器的最终值（一版不缺、一版不倒退）。修复前该断言在多轮压测下会偶发红；
 * 修复后（按船串行化）必须稳定绿。
 */
class PredictionServiceConcurrencyTest {

    private static final String MMSI = "413888888";

    @Test
    @DisplayName("HIGH.23：并发刷新下版本不倒退、快照不被旧版覆盖")
    void concurrentRefresh_shouldNeverRollBackVersion() throws Exception {
        PredictionService service = new PredictionService(
                new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 1L, 840L),
                mmsi -> java.util.Optional.empty(),
                event -> { });

        int threads = 8;
        int perThread = 250;
        AtomicLong tsClock = new AtomicLong(System.currentTimeMillis());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    // 观测时标前进（模拟真实 AIS 序列）；位置沿航段微移
                    long ts = tsClock.incrementAndGet();
                    service.refresh(new AisMessage(MMSI, "并发测试船",
                            115.8590 + (i % 10) * 1e-6, 28.6630, 1.0, 31.2, ts));
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        long totalVersions = (long) threads * perThread;
        PredictionSnapshot latest = service.snapshotOf(MMSI).orElseThrow();
        assertEquals(totalVersions, latest.version(),
                "登记的最新快照版本必须等于版本计数器终值——若小于它，说明低版本覆盖了高版本");
    }
}
