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
}
