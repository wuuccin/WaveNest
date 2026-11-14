package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoggingRecomputeRequester} 与 {@link RecomputeRequester} 的防护单测
 * （11-02 评审 LOW.6 / MEDIUM.2 / MEDIUM.10 / MEDIUM.12）。
 *
 * <p>这里验的都是「正常路径上看不见、出问题时很难查」的东西：
 * 同线程递归（栈溢出前没有任何报错）、逐条日志（把磁盘写满）、异常外抛（打断 AIS 消费线程）。
 */
class LoggingRecomputeRequesterTest {

    private static final long T0 = 1_760_000_000_000L;

    private static RecomputeRequest request(String mmsi) {
        return new RecomputeRequest(mmsi, 1L, 350.0, 30.0, 40.0, "测试", T0);
    }

    @Test
    @DisplayName("★ LOW.6：监听方在重算回调里再次请求重算 ⇒ 同线程递归被断开，不外抛")
    void reentrantRecompute_shouldBeCut() {
        LoggingRecomputeRequester requester = new LoggingRecomputeRequester();

        // 模拟「recompute 内部 publishEvent → 监听方在同一线程再摸一次预测」
        PredictionSettings settings = new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        PredictionService recursive = new PredictionService(settings, requester) {
            @Override
            public Optional<PredictionSnapshot> recompute(String mmsi) {
                requester.requestRecompute(request(mmsi));
                return Optional.empty();
            }
        };
        requester.bindPredictionService(recursive);

        assertDoesNotThrow(() -> requester.requestRecompute(request("413000001")),
                "递归必须断在闸门里，不能以 StackOverflowError 收场");
        assertTrue(requester.flush(5_000L), "重算队列未在超时前处理完");

        assertEquals(1, requester.recursionDroppedCount(), "内层那次应被记一次断开");
        assertEquals(1, requester.receivedRequestCount(),
                "只有外层真正进入处理，内层在入口就被挡下");
    }

    @Test
    @DisplayName("★ LOW.6：断开后闸门必须复位，同一线程的下一条请求照常处理")
    void gate_shouldResetAfterEachRequest() {
        LoggingRecomputeRequester requester = new LoggingRecomputeRequester();

        requester.requestRecompute(request("413000001"));
        requester.requestRecompute(request("413000002"));

        assertEquals(2, requester.receivedRequestCount(),
                "闸门若没复位（ThreadLocal 未 remove），第二条会被误判成递归");
        assertEquals(0, requester.recursionDroppedCount());
    }

    @Test
    @DisplayName("★ MEDIUM.2：日志采样不影响计数与留痕")
    void sampling_shouldNotAffectCounters() {
        LoggingRecomputeRequester requester = new LoggingRecomputeRequester();

        for (int i = 0; i < LoggingRecomputeRequester.LOG_EVERY_N * 3; i++) {
            requester.requestRecompute(request("41300000" + (i % 10)));
        }

        assertEquals(LoggingRecomputeRequester.LOG_EVERY_N * 3, requester.receivedRequestCount(),
                "采样只影响日志条数，累计计数必须完整——否则「重算风暴」在指标上看不见");
        assertEquals(LoggingRecomputeRequester.LOG_EVERY_N * 3, requester.requestCount(),
                "环形缓冲未到上限时，留痕条数与收到条数一致");
    }

    @Test
    @DisplayName("空请求只记警告，不抛")
    void nullRequest_shouldBeIgnored() {
        LoggingRecomputeRequester requester = new LoggingRecomputeRequester();

        assertDoesNotThrow(() -> requester.requestRecompute(null));
        assertEquals(0, requester.receivedRequestCount());
    }

    @Test
    @DisplayName("★ MEDIUM.12：接口的防护壳吞掉实现方的异常（这才是『不得抛异常』的代码约束）")
    void notifyRecompute_shouldSwallowImplementationFailure() {
        AtomicInteger calls = new AtomicInteger();
        RecomputeRequester throwing = r -> {
            calls.incrementAndGet();
            throw new IllegalStateException("门面实现跨模块调用失败");
        };

        assertDoesNotThrow(() -> throwing.notifyRecompute(request("413000001")));
        assertEquals(1, calls.get(), "异常被吞，但实现确实被调用过一次");

        assertDoesNotThrow(() -> throwing.notifyRecompute(null), "null 请求同样不外抛");
    }

    @Test
    @DisplayName("RecomputeRequest 强制最短弧与非空船号（LOW.22）")
    void recomputeRequest_shouldEnforceInvariants() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecomputeRequest("413000001", 1L, 0.0, 0.0, -1.0, "负弧差", T0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecomputeRequest("413000001", 1L, 0.0, 0.0, 181.0, "超 180", T0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecomputeRequest("  ", 1L, 0.0, 0.0, 10.0, "空船号", T0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RecomputeRequest("413000001", 1L, 0.0, 0.0, Double.NaN, "NaN", T0));
    }

    /**
     * ★ 11-14 序号 2-6 · MEDIUM.2：重算不得占着调用方线程。
     *
     * <p>判据用「把重算卡住，再量 {@code requestRecompute} 的返回耗时」来验：
     * 同步实现会一直卡到重算结束，异步实现立即返回。这比断言「有线程池字段」更本质——
     * 验的是<b>行为</b>，实现换成别的异步方案也照样成立。
     */
    @Test
    @DisplayName("★ MEDIUM.2：重算在 worker 线程上跑，调用方不被阻塞；同船连发被合并")
    void requestRecompute_shouldNotBlockCaller() throws Exception {
        LoggingRecomputeRequester requester = new LoggingRecomputeRequester();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        PredictionSettings settings = new PredictionSettings(30.0, 0.15, 10.0, 0.02, 600L, 60L, 900L);
        PredictionService blocking = new PredictionService(settings, requester) {
            @Override
            public Optional<PredictionSnapshot> recompute(String mmsi) {
                started.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        requester.bindPredictionService(blocking);

        long beginNs = System.nanoTime();
        requester.requestRecompute(request("413000001"));
        long elapsedMs = (System.nanoTime() - beginNs) / 1_000_000L;

        assertTrue(elapsedMs < 500L,
                "重算被卡住时调用方仍应立即返回，实测耗时 " + elapsedMs + " ms");
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "worker 应已开始处理");

        // 同一条船再有信号：不排队，直接并进在途的那次
        requester.requestRecompute(request("413000001"));
        assertEquals(1L, requester.coalescedCount(), "同船在途重算期间的新信号应被合并");

        release.countDown();
        assertTrue(requester.flush(5_000L), "放行后队列应在超时前清空");
        assertEquals(2L, requester.receivedRequestCount(), "两条信号都要记入收到数");
    }
}
