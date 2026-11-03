package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.app.WaveNestApplication;
import com.waterlogistics.storage.entity.PredErrorSampleEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.PredErrorSampleMapper;
import java.lang.reflect.Proxy;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@code pred_error_sample} 写入通道的真实库回归（11-02 阶段二 5 的验收判据）。
 *
 * <h3>为什么这条必须走真库，单测替不了</h3>
 * <p>单测用计数桩验的是「调了几次 insert」，验不了：
 * <ul>
 *   <li><b>列名对不对</b>——实体字段与 DDL 的驼峰↔下划线映射错了，桩照样返回成功，
 *       真库直接报 {@code Unknown column}；</li>
 *   <li><b>{@code DATETIME(3)} 的毫秒有没有被截断</b>——这是样本去重水位与分桶切窗的基准，
 *       掉到秒级会让同一秒内多条样本水位打平，去重逻辑静默失效；</li>
 *   <li><b>时区往返</b>——写入用固定 {@code Asia/Shanghai}，连接串也是，两者不一致会整体偏 8 小时，
 *       而这种偏差在「样本时刻看起来都对」时根本发现不了；</li>
 *   <li><b>{@code create_time} 的库侧默认值是否生效</b>——实体不写该字段，靠 DDL 的
 *       {@code DEFAULT CURRENT_TIMESTAMP(3)}；若插入策略改成全字段写入，它会被写成 1970 或 NULL。</li>
 * </ul>
 *
 * <h3>为什么放在 {@code com.waterlogistics.dispatch.prediction} 包下</h3>
 * <p>本类要调 {@link PredictionCalibrationService#forTesting}（包级可见的单测装配口，
 * 用来直接注入样本源绕开 Spring 的 {@code ObjectProvider}）。Java 的包级访问只看包名、
 * 不看模块边界，所以测试类放在 wl-app 模块里也能访问——这样既复用了 wl-app 的
 * Spring 上下文与真实数据源，又不用为了测试把生产构造器改成 public。
 *
 * <h3>不污染全局状态</h3>
 * <p>① MMSI 用 {@code T} 前缀 + 时间戳，天然与真实 AIS 的 9 位数字 MMSI 隔开；
 * ② 样本只有 2 条，远低于偏差校准器出建议的门槛（{@code calMinSamples} 缺省 30），
 * 故 {@code calibrate} 不会回写 {@code sys_param}；③ 清理在 {@code finally} 里，
 * 断言失败也删。故意<b>不用</b> {@code @Transactional} 回滚：被测代码自己不开事务，
 * 外面套一层回滚并不能保证它写入的行消失，反而会让「表里真的有行」这件事变得不可观测。
 */
@SpringBootTest(classes = WaveNestApplication.class, properties = {
        "server.port=0",
        "wavenest.sim.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class PredErrorSampleIntegrationTest {

    /** 与 DDL 注释、校准服务常量保持一致的库时区。 */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 样本基准时刻。
     *
     * <p>刻意带一个非零毫秒（{@code 123}）——若 {@code DATETIME(3)} 的毫秒被截断，
     * 这条断言就会红；用整秒时间戳则截断与否都看不出来。
     */
    private static final long BASE_TS_MS = 1_760_000_000_123L;

    @Autowired
    private PredErrorSampleMapper sampleMapper;

    @Autowired
    private SysParamLoader sysParamLoader;

    @Test
    @DisplayName("calibrate 一次后 pred_error_sample 应有行，且字段/毫秒/时区往返无损")
    void calibrate_shouldPersistSamplesIntoRealTable() {
        String mmsi = "T" + (System.nanoTime() % 1_000_000_000L);
        List<ResidualSample> samples = List.of(
                // τ=0：只落库留痕，不进校准器（残差恒 0，对统计是纯噪声）
                new ResidualSample(mmsi, 42L, 0, 0.0, 0.0, null, 5.0, 2.0, 1.2,
                        BASE_TS_MS),
                // τ=60：进 NEES 与偏差校准器；航向误差给最短弧内的负值
                new ResidualSample(mmsi, 42L, 60, 12.5, -3.25, -7.5, 6.0, 2.2, 1.2,
                        BASE_TS_MS + 60_000L));

        PredictionCalibrationService service =
                PredictionCalibrationService.forTesting(ignored -> samples, sampleMapper, sysParamLoader);

        LambdaQueryWrapper<PredErrorSampleEntity> mine = new LambdaQueryWrapper<PredErrorSampleEntity>()
                .eq(PredErrorSampleEntity::getShipMmsi, mmsi)
                .orderByAsc(PredErrorSampleEntity::getSampleTs);
        try {
            PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(mmsi);
            assertEquals(1, outcome.newSamples(), "τ=0 的样本落库但不计入校准样本数");

            List<PredErrorSampleEntity> rows = sampleMapper.selectList(mine);
            assertEquals(2, rows.size(), "两条样本都要落库（含 τ=0 的配对留痕）");

            PredErrorSampleEntity zero = rows.get(0);
            assertEquals(0, zero.getTauSec());
            assertEquals(42L, zero.getPredVersion());
            assertEquals(0.0, zero.getErrAlongM(), 1e-9);
            assertNull(zero.getErrCourseDeg(), "无有效实测航向时应留 NULL 而不是 0");
            assertNotNull(zero.getCreateTime(), "create_time 须由 DDL 默认值填充");

            PredErrorSampleEntity sixty = rows.get(1);
            assertEquals(60, sixty.getTauSec());
            assertEquals(12.5, sixty.getErrAlongM(), 1e-9);
            assertEquals(-3.25, sixty.getErrCrossM(), 1e-9);
            assertEquals(-7.5, sixty.getErrCourseDeg(), 1e-9);
            assertEquals(6.0, sixty.getSigmaAlongM(), 1e-9);
            assertEquals(2.2, sixty.getSigmaCrossM(), 1e-9);
            assertEquals(1.2, sixty.getVAlongMs(), 1e-9,
                    "v_along_ms 是偏差校准器的分桶键，丢了就重建不出分桶建议");

            // 毫秒精度 + 时区往返：写入用 Asia/Shanghai，读回按同一时区还原成 epoch 毫秒
            assertEquals(BASE_TS_MS + 60_000L,
                    sixty.getSampleTs().atZone(DB_ZONE).toInstant().toEpochMilli(),
                    "DATETIME(3) 的毫秒不能被截断，否则同秒内多条样本的水位会打平");

            // 水位去重：同一批样本再喂一遍不应重复消费
            PredictionCalibrationService.CalibrationOutcome again = service.calibrate(mmsi);
            assertEquals(0, again.newSamples(), "sampleTsMs 水位未推进，不应重复消费");
            assertEquals(2, sampleMapper.selectCount(mine), "去重失效会让样本数虚增、区间宽虚假收窄");

            // ── 跨重启不重复落库（11-03 D2 的四元组唯一键）──
            // 新建一个服务实例 = 模拟重启：内存水位清零，同一批样本会被重新吐出。
            // 此时唯一键 uk_pes_pair 必须挡住，且记「跳过」而不是「失败」。
            PredictionCalibrationService afterRestart =
                    PredictionCalibrationService.forTesting(ignored -> samples, sampleMapper, sysParamLoader);
            PredictionCalibrationService.CalibrationOutcome replayed = afterRestart.calibrate(mmsi);

            assertEquals(1, replayed.newSamples(),
                    "重启后同一批样本仍会进内存统计（1 = 只剩 τ=60 那条；τ=0 只落库不计数）");
            assertEquals(0L, afterRestart.persistedSampleCount(), "但一行都不该再写进去");
            assertEquals(2L, afterRestart.duplicateSkippedCount(), "两条都应记成「跳过」");
            assertEquals(0, afterRestart.consecutivePersistFailures(),
                    "重复键不是失败：库通、表在，连续失败计数必须保持 0");
            assertEquals(2, sampleMapper.selectCount(mine), "表里仍应只有两行");
        } finally {
            sampleMapper.delete(mine);
        }
    }

    /**
     * 落库失败不得上抛（校准跑在预测热路径上）。
     *
     * <p>这条与上一条互补：上一条验「能写进去」，这条验「写不进去也不能拖垮预测」。
     * 用一张不存在的表触发真实 SQL 异常，比桩异常更接近现场（缺 007 脚本就是这个后果）。
     */
    @Test
    @DisplayName("表不可写时 calibrate 应吞掉异常，不打断预测链")
    void persistFailure_shouldBeSwallowed() {
        String mmsi = "T" + (System.nanoTime() % 1_000_000_000L);
        List<ResidualSample> samples = List.of(
                new ResidualSample(mmsi, 7L, 30, 1.0, 1.0, 1.0, 5.0, 2.0, 1.0, BASE_TS_MS));

        // 用动态代理让 insert 抛真 SQL 异常。
        // 为什么不用匿名类：PredErrorSampleMapper 继承 BaseMapper，抽象方法有十几个，
        // 匿名实现得把它们全写一遍；而本用例只碰 insert 一个方法。
        PredErrorSampleMapper broken = (PredErrorSampleMapper) Proxy.newProxyInstance(
                PredErrorSampleMapper.class.getClassLoader(),
                new Class<?>[] {PredErrorSampleMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        throw new DataIntegrityViolationException(
                                "Table 'wavenest.pred_error_sample' doesn't exist");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        PredictionCalibrationService service =
                PredictionCalibrationService.forTesting(ignored -> samples, broken, sysParamLoader);

        // 不抛异常即通过；返回值仍是「1 条新样本、但因子不可采信」
        PredictionCalibrationService.CalibrationOutcome outcome = service.calibrate(mmsi);
        assertEquals(1, outcome.newSamples(), "样本已进入校准器，只是没落成行");
        assertEquals(0L, service.persistedSampleCount(), "落库失败不应计入成功条数");
        assertNull(outcome.calibrationFactor(), "2 条样本远未达 200 的门槛，因子必须是 null 而非 1.0");
    }
}
