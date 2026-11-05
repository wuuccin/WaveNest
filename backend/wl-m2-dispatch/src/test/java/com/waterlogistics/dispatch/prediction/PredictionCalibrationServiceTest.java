package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.NeesCalibrator;
import com.waterlogistics.storage.entity.PredErrorSampleEntity;
import com.waterlogistics.storage.repository.PredErrorSampleMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionCalibrationService} 单测（11-02 阶段二 5）。
 *
 * <p>守三条口径，每条都对应一个「看起来收敛了、其实是假象」的失败模式：
 * <ol>
 *   <li><b>不足量不采信</b>：样本 &lt; 200 或 {@code c} 的 95% 区间宽 &gt; 0.15 时，
 *       快照拿到的必须是 {@code null}（未标定），不是 1.0（假装标定过且中性）；</li>
 *   <li><b>不重复计数</b>：生产方每拍把同一批残差再吐一遍，去重失效会把 200 个样本喂成 2000 个，
 *       让区间宽虚假收窄到 0.04，从而「通过」验收；</li>
 *   <li><b>不拖累预测</b>：样本源抛异常、样本非法、库写失败，一律本地吞掉。</li>
 * </ol>
 */
class PredictionCalibrationServiceTest {

    private static final String MMSI = "413000001";
    private static final long T0 = 1_760_000_000_000L;

    /** 制造一条样本；σ 固定 10 m 便于反推 NEES。 */
    private static ResidualSample sample(long tsMs, int tauSec, double errAlong, double errCross) {
        return new ResidualSample(MMSI, 1L, tauSec, errAlong, errCross, 0.0, 10.0, 10.0, 3.0, tsMs);
    }

    /** 一批「NEES 恰好 ≈ 2」的样本——理论期望值，标定因子应落在 1.0 附近。 */
    private static List<ResidualSample> idealSamples(int count) {
        List<ResidualSample> samples = new ArrayList<>(count);
        // (10√2 / 10)² + 0² = 2
        double errAlong = 10.0 * Math.sqrt(2.0);
        for (int i = 0; i < count; i++) {
            samples.add(sample(T0 + i * 1000L, 60, errAlong, 0.0));
        }
        return samples;
    }

    /** 只统计 insert 次数的 Mapper 桩（BaseMapper 是接口，用动态代理最省）。 */
    private static PredErrorSampleMapper countingMapper(AtomicInteger inserts) {
        return (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        inserts.incrementAndGet();
                        assertTrue(args[0] instanceof PredErrorSampleEntity, "落库载荷应是实体");
                        return 1;
                    }
                    if ("toString".equals(method.getName())) {
                        return "countingMapper";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("★ 样本源未装配：链路整体不可用，标定因子为 null，不抛")
    void noSource_shouldYieldNullFactor() {
        PredictionCalibrationService service =
                PredictionCalibrationService.forTesting(null, null, null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(0, outcome.newSamples());
        assertNull(outcome.calibrationFactor(), "未装配样本源时不能给出一个「看起来能用」的因子");
        assertFalse(outcome.acceptable());
        assertNull(service.calibrationFactorOrNull());
    }

    @Test
    @DisplayName("★ 样本不足 200：标定因子必须为 null（1.0 会被下游误读成「标定过且中性」）")
    void fewerThanMinSamples_shouldWithholdFactor() {
        List<ResidualSample> samples = idealSamples(NeesCalibrator.MIN_SAMPLES_FOR_ACCEPTANCE - 1);
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(199, outcome.totalSamples());
        assertEquals(199, outcome.newSamples());
        assertTrue(outcome.confidenceWidth() <= NeesCalibrator.MAX_CONFIDENCE_WIDTH,
                "199 个样本的区间宽 1.96/√199 ≈ 0.139 本身合格——正因如此，"
                        + "这条用例才要证明「区间宽合格也不能替代样本量门槛」");
        assertFalse(outcome.acceptable());
        assertNull(outcome.calibrationFactor());
    }

    @Test
    @DisplayName("★ 满 200 且 NEES≈2：标定因子约 1.0、区间宽 ≤ 0.15，可下发")
    void enoughIdealSamples_shouldPublishFactor() {
        List<ResidualSample> samples = idealSamples(NeesCalibrator.MIN_SAMPLES_FOR_ACCEPTANCE);
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(200, outcome.totalSamples());
        assertTrue(outcome.acceptable(), "NEES 均值 ≈ 2、样本 200 ⇒ 应达到《80》§5.1 门槛");
        assertEquals(1.0, outcome.calibrationFactor(), 0.02, "c = m/2 ≈ 1.0");
        assertTrue(outcome.confidenceWidth() <= NeesCalibrator.MAX_CONFIDENCE_WIDTH);
        assertNotNull(service.calibrationFactorOrNull());
    }

    @Test
    @DisplayName("★ 同一批样本喂两遍只计一次（去重失效会让区间宽虚假收窄）")
    void repeatedBatch_shouldCountOnce() {
        List<ResidualSample> samples = idealSamples(100);
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        service.calibrate(MMSI);
        PredictionCalibrationService.CalibrationOutcome second = service.calibrate(MMSI);

        assertEquals(0, second.newSamples(), "第二遍不应再有新样本");
        assertEquals(100, second.totalSamples(), "累计样本数不得翻倍");
        assertEquals(100, service.neesResult().sampleCount());
    }

    @Test
    @DisplayName("增量样本：只消费水位之后的部分")
    void incrementalSamples_shouldOnlyConsumeNewOnes() {
        List<ResidualSample> samples = new ArrayList<>(idealSamples(50));
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        service.calibrate(MMSI);
        assertEquals(50, service.neesResult().sampleCount());

        // 生产方追加 10 条更新的样本
        for (int i = 50; i < 60; i++) {
            samples.add(sample(T0 + i * 1000L, 60, 10.0 * Math.sqrt(2.0), 0.0));
        }
        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(10, outcome.newSamples());
        assertEquals(60, outcome.totalSamples());
    }

    @Test
    @DisplayName("★ τ=0 的样本落库但不进统计（残差恒 0，是纯噪声）")
    void zeroTauSamples_shouldPersistButNotCount() {
        AtomicInteger inserts = new AtomicInteger();
        List<ResidualSample> samples = List.of(
                sample(T0, 0, 0.0, 0.0),
                sample(T0 + 1000L, 60, 10.0, 0.0));
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, countingMapper(inserts), null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(2, inserts.get(), "两条都要落库，τ=0 那条是配对留痕");
        assertEquals(1, outcome.totalSamples(), "但只有 τ>0 的那条进 NEES");
        assertEquals(2, service.persistedSampleCount(), "落库计数按「真写进去的行」算，两条都写成了");
    }

    @Test
    @DisplayName("★ 样本源抛异常：本地吞掉，预测链不受影响")
    void sourceThrowing_shouldBeSwallowed() {
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(
                mmsi -> {
                    throw new IllegalStateException("引擎内部炸了");
                }, null, null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(0, outcome.newSamples());
        assertNull(outcome.calibrationFactor());
    }

    @Test
    @DisplayName("落库失败只记警告，不影响统计结果")
    void persistFailure_shouldNotBreakCalibration() {
        AtomicInteger attempts = new AtomicInteger();
        PredErrorSampleMapper failing = (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("表不存在");
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "failingMapper";
                    }
                    return null;
                });

        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> idealSamples(10), failing, null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(10, attempts.get(), "十条都尝试过落库");
        assertEquals(10, outcome.totalSamples(), "库写失败不影响内存统计");
        assertEquals(0, service.persistedSampleCount(), "成功计数应为 0");
    }

    @Test
    @DisplayName("偏差字段取最近一条样本的二维欧氏距离")
    void deviation_shouldBeLatestEuclideanDistance() {
        List<ResidualSample> samples = List.of(
                sample(T0, 60, 3.0, 4.0),
                sample(T0 + 1000L, 60, 6.0, 8.0));
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        assertEquals(10.0, service.calibrate(MMSI).deviationM(), 1e-9);
    }

    @Test
    @DisplayName("节流：间隔内重复调用不再消费样本")
    void throttle_shouldSkipWithinInterval() {
        List<ResidualSample> samples = new ArrayList<>(idealSamples(10));
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        PredictionCalibrationService.CalibrationOutcome first =
                service.calibrateIfDue(MMSI, T0);
        assertEquals(10, first.newSamples());

        samples.add(sample(T0 + 99_000L, 60, 10.0, 0.0));
        PredictionCalibrationService.CalibrationOutcome throttled =
                service.calibrateIfDue(MMSI, T0 + 1000L);
        assertEquals(0, throttled.newSamples(), "缺省 60 s 节流内不应再次消费");
        assertEquals(10, throttled.totalSamples());
    }

    @Test
    @DisplayName("reset 后内存态清空，水位归零可重新消费")
    void reset_shouldClearMemoryState() {
        List<ResidualSample> samples = idealSamples(5);
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(mmsi -> samples, null, null);

        service.calibrate(MMSI);
        service.reset();

        assertEquals(0, service.neesResult().sampleCount());
        assertEquals(5, service.calibrate(MMSI).newSamples(), "水位已清，同一批应可重新消费");
    }

    @Test
    @DisplayName("★ 唯一键重复计「跳过」不计失败，并清零连续失败计数")
    void duplicateKey_shouldCountAsSkippedNotFailed() {
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(
                mmsi -> idealSamples(3),
                throwingMapper(new org.springframework.dao.DuplicateKeyException(
                        "Duplicate entry '413000001-1-60-...' for key 'uk_pes_pair'")),
                null);

        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(MMSI);

        assertEquals(3, outcome.newSamples(), "重复只影响落库，不影响内存里的统计");
        assertEquals(0, service.persistedSampleCount(), "一行都没真写进去");
        assertEquals(3, service.duplicateSkippedCount(), "三条都应记成「跳过」");
        assertEquals(0, service.consecutivePersistFailures(),
                "重复键恰好证明库通、表在，必须把连续失败计数清零——否则每次重启都会误报 error");
    }

    @Test
    @DisplayName("★ 连续落库失败按阈值计数（3 次），一次成功即清零")
    void consecutiveFailures_shouldReachThresholdThenReset() {
        // 阶段一：三条全失败 → 连续失败计数到达阈值 3
        PredictionCalibrationService failingService = PredictionCalibrationService.forTesting(
                mmsi -> List.of(
                        sample(T0, 60, 1.0, 1.0),
                        sample(T0 + 1000L, 60, 1.0, 1.0),
                        sample(T0 + 2000L, 60, 1.0, 1.0)),
                throwingMapper(new IllegalStateException("表不存在")),
                null);

        failingService.calibrate(MMSI);

        assertEquals(PredictionCalibrationService.PERSIST_FAILURE_ALERT_THRESHOLD, 3,
                "阈值是 C6 裁定的 3，改这里必须同步改 007 注释与 PR 描述");
        assertEquals(3, failingService.consecutivePersistFailures(), "三条连续失败应计数到 3（触发 error）");

        // 阶段二：前三条失败、第四条成功 → 计数清零，成功计数为 1
        AtomicInteger attempt = new AtomicInteger();
        PredErrorSampleMapper flaky = (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        if (attempt.incrementAndGet() <= 3) {
                            throw new IllegalStateException("表不存在");
                        }
                        return 1;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "flakyMapper";
                    }
                    return null;
                });

        PredictionCalibrationService recoveringService = PredictionCalibrationService.forTesting(
                mmsi -> List.of(
                        sample(T0, 60, 1.0, 1.0),
                        sample(T0 + 1000L, 60, 1.0, 1.0),
                        sample(T0 + 2000L, 60, 1.0, 1.0),
                        sample(T0 + 3000L, 60, 1.0, 1.0)),
                flaky, null);

        recoveringService.calibrate(MMSI);

        assertEquals(1L, recoveringService.persistedSampleCount());
        assertEquals(0, recoveringService.consecutivePersistFailures(),
                "一次成功必须把连续失败清零，否则「偶发失败」会永久累积成假告警");
    }

    @Test
    @DisplayName("★ v_along_ms 必须随样本落库（偏差校准器按船速分桶）")
    void vAlong_shouldBePersisted() {
        List<PredErrorSampleEntity> captured = new ArrayList<>();
        PredictionCalibrationService service = PredictionCalibrationService.forTesting(
                mmsi -> List.of(new ResidualSample(MMSI, 9L, 60, 12.0, -3.0, 1.5, 10.0, 10.0, 4.2, T0)),
                capturingMapper(captured), null);

        service.calibrate(MMSI);

        assertEquals(1, captured.size());
        PredErrorSampleEntity row = captured.get(0);
        assertEquals(4.2, row.getVAlongMs(), 1e-9, "船速是分桶键，丢了就重建不出分桶建议");
        assertEquals(9L, row.getPredVersion());
        assertEquals(60, row.getTauSec());
        assertEquals(12.0, row.getErrAlongM(), 1e-9);
        assertEquals(-3.0, row.getErrCrossM(), 1e-9);
        assertEquals(1.5, row.getErrCourseDeg(), 1e-9);
    }

    @Test
    @DisplayName("★ 判据 4 复验：连续第 3 次失败记 ERROR，且只在第 3 次记（4、5 次回落 warn）")
    void persistFailureThreshold_shouldLogErrorExactlyAtThirdTime() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(PredictionCalibrationService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 五条样本全失败：日志级别应依次是 warn、warn、error、warn、warn
            List<ResidualSample> five = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                five.add(sample(T0 + i * 1000L, 60, 1.0, 1.0));
            }
            PredictionCalibrationService service = PredictionCalibrationService.forTesting(
                    mmsi -> five, throwingMapper(new IllegalStateException("表不存在")), null);

            service.calibrate(MMSI);

            List<ch.qos.logback.classic.spi.ILoggingEvent> failures = appender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR
                            || event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("落库"))
                    .toList();
            long errors = failures.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .count();

            assertEquals(5, failures.size(), "每条失败都应有日志，实际：" + failures);
            assertEquals(1, errors,
                    "阈值 3 只应告警一次；每次失败都记 error 会让告警被自己的噪声淹掉");
            assertTrue(failures.get(2).getFormattedMessage().contains("连续 3 次"),
                    "第 3 次应为 error 且带「连续 3 次」字样，实际：" + failures.get(2).getFormattedMessage());
            assertEquals(ch.qos.logback.classic.Level.ERROR, failures.get(2).getLevel());
            assertEquals(ch.qos.logback.classic.Level.WARN, failures.get(3).getLevel());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("★ 判据 4 复验：跨重启重放同一批样本 —— 一行都不新增，全部记「跳过」")
    void restartReplay_shouldHitUniqueKeyAndNotInsertAgain() {
        // 内存版「带 uk_pes_pair 唯一键的表」：同一个四元组第二次 insert 抛 MySQL 1062 等价的异常。
        // 不依赖 MySQL 也能验「重启后不重复落库」这条判据，CI 上同样跑得动。
        Map<String, Integer> table = new java.util.LinkedHashMap<>();
        PredErrorSampleMapper uniqueKeyMapper = (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        PredErrorSampleEntity row = (PredErrorSampleEntity) args[0];
                        String key = row.getShipMmsi() + "|" + row.getPredVersion() + "|"
                                + row.getTauSec() + "|" + row.getSampleTs();
                        if (table.put(key, 1) != null) {
                            // 真实链路里 MyBatis 会把 SQLIntegrityConstraintViolationException 包三层
                            throw new IllegalStateException("落库失败",
                                    new java.sql.SQLIntegrityConstraintViolationException(
                                            "Duplicate entry '" + key + "' for key 'uk_pes_pair'",
                                            "23000", 1062));
                        }
                        return 1;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "uniqueKeyMapper";
                    }
                    return null;
                });

        List<ResidualSample> batch = List.of(
                sample(T0, 60, 1.0, 1.0),
                sample(T0 + 1000L, 120, 2.0, 1.0));

        PredictionCalibrationService before = PredictionCalibrationService.forTesting(
                mmsi -> batch, uniqueKeyMapper, null);
        before.calibrate(MMSI);
        assertEquals(2L, before.persistedSampleCount(), "首次应两条都进表");
        assertEquals(2, table.size());
        assertEquals(0L, before.duplicateSkippedCount());

        // 「重启」：新实例、内存态全空，样本源把同一批样本又吐一遍（水位是内存态，重启即丢）
        PredictionCalibrationService afterRestart = PredictionCalibrationService.forTesting(
                mmsi -> batch, uniqueKeyMapper, null);
        afterRestart.calibrate(MMSI);

        assertEquals(0L, afterRestart.persistedSampleCount(), "重启后不应再写进任何一行");
        assertEquals(2L, afterRestart.duplicateSkippedCount(), "两条都应记成「跳过」");
        assertEquals(0, afterRestart.consecutivePersistFailures(),
                "唯一键冲突不是故障，不能累进失败计数（否则重启后必报假告警）");
        assertEquals(2, table.size(), "表里仍是两行");
    }

    // ───────────── 库桩 ─────────────

    /** 无论 insert 什么都抛指定异常的 Mapper 桩。 */
    private static PredErrorSampleMapper throwingMapper(RuntimeException error) {
        return (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        throw error;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "throwingMapper";
                    }
                    return null;
                });
    }

    /** 截获落库载荷的 Mapper 桩（验列映射用）。 */
    private static PredErrorSampleMapper capturingMapper(List<PredErrorSampleEntity> sink) {
        return (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        sink.add((PredErrorSampleEntity) args[0]);
                        return 1;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "capturingMapper";
                    }
                    return null;
                });
    }
}
