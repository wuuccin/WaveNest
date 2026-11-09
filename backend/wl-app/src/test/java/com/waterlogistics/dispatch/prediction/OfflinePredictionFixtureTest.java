package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.app.WaveNestApplication;
import com.waterlogistics.storage.entity.PredErrorSampleEntity;
import com.waterlogistics.storage.repository.PredErrorSampleMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 离线预测夹具的真库验收（契约 §10，2026-11-09 模拟数据批次 · 卢肄文）。
 *
 * <h3>判据（执行计划 §3 卢肄文行）</h3>
 * <ul>
 *   <li>固定 seed 在本人数据库写出 {@code pred_error_sample} 行；</li>
 *   <li>{@code sys_param} 零回写；</li>
 *   <li>装配按契约 §10（{@code forTesting} 第三参 {@code null}）。</li>
 * </ul>
 *
 * <h3>为什么必须走真库</h3>
 * <p>落库这一步的坑全在「实体 ↔ DDL」的边界上：列名映射错了、{@code DATETIME(3)} 的毫秒被截断、
 * 四元组唯一键的列顺序不对，用桩都验不出来。契约 §9 明确要求「复跑前先清表或换号」，
 * 这条也只能在真库上验。
 *
 * <h3>环境变量</h3>
 * <pre>
 * FIXTURE_SEED          默认 1        随机种子，决定噪声序列
 * FIXTURE_PRED_VERSION  默认 1        pred_version，复跑须换新值
 * FIXTURE_PATH_START_M  默认 1000     起点弧长，m
 * FIXTURE_DURATION_SEC  默认 1200     时长，s
 * </pre>
 *
 * <p>复跑命令（本机 Docker 把 MySQL 映射在 3307）：
 * <pre>
 * MYSQL_PORT=3307 REDIS_PORT=6380 FIXTURE_SEED=1 \
 *   ./gradlew :wl-app:test --tests "*OfflinePredictionFixtureTest" --console=plain
 * </pre>
 */
@SpringBootTest(classes = WaveNestApplication.class, properties = {
        "server.port=0",
        "wavenest.sim.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class OfflinePredictionFixtureTest {

    @Autowired
    private PredErrorSampleMapper sampleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("固定 seed 跑一次 20 min 子段：pred_error_sample 有行、τ 网格非空、sys_param 零回写")
    void subset_shouldPersistSamplesAndKeepSysParamUntouched() {
        int seed = intEnv("FIXTURE_SEED", 1);
        long predVersion = longEnv("FIXTURE_PRED_VERSION", 1L);
        double pathStartM = doubleEnv("FIXTURE_PATH_START_M", OfflinePredictionFixture.SUBSET_START_M);
        int durationSec = intEnv("FIXTURE_DURATION_SEC", OfflinePredictionFixture.SUBSET_DURATION_SEC);

        OfflinePredictionFixture.Spec spec = new OfflinePredictionFixture.Spec(
                seed, "T" + seed, predVersion, 1_760_000_000_000L, pathStartM, durationSec);

        Map<String, String> sysParamBefore = snapshotSysParam();
        LambdaQueryWrapper<PredErrorSampleEntity> mine =
                new LambdaQueryWrapper<PredErrorSampleEntity>()
                        .eq(PredErrorSampleEntity::getShipMmsi, spec.mmsi());

        // 上一次跑若异常退出会留下同版本同 ts 的行，撞 uk_pes_pair 后本次 0 行。
        // 先清，把「复跑撞唯一键」这个已知现象与本用例要验的行为分开（契约 §9）。
        sampleMapper.delete(mine);

        try {
            OfflinePredictionFixture.Result result =
                    OfflinePredictionFixture.run(spec, sampleMapper);

            // ── 1. 真值口径：§2 三段积分，终点不得越过全长 ──
            assertEquals(888.889, OfflinePredictionFixture.T_TO_SECOND_SWITCH_SEC, 0.001,
                    "s=0→4000 用时须为 888.889 s");
            assertEquals(801.340, OfflinePredictionFixture.T_TO_END_SEC, 0.001,
                    "4000→6003.351 用时须为 801.340 s");
            assertEquals(1690.229, OfflinePredictionFixture.T_TOTAL_SEC, 0.001,
                    "全程用时须为 1690.229 s");
            assertTrue(result.endS() <= OfflinePredictionFixture.CENTERLINE_TOTAL_M + 1e-9,
                    "真值终点 " + result.endS() + " m 越过中心线全长 "
                            + OfflinePredictionFixture.CENTERLINE_TOTAL_M + " m（契约 §12 失败判据）");

            // ── 2. 落库：必须有行 ──
            assertTrue(result.samples() > 0,
                    "引擎没产出任何残差样本：先查冷却拍数与探针间隔（30 s 一支），"
                            + "再查 duration_sec 是否短于最小 τ=60 s");
            List<PredErrorSampleEntity> rows = sampleMapper.selectList(mine);
            assertEquals(result.rows(), rows.size(),
                    "落库行数须与 persistedSampleCount 一致");
            assertTrue(rows.size() > 0, "pred_error_sample 必须有行（契约 §12：0 行属失败）");
            assertEquals(result.samples(), result.rows() + (int) result.duplicated(),
                    "每条样本要么落库要么被唯一键挡下，不应静默丢失");

            // ── 3. 字段口径：版本、τ、σ 都得对得上 ──
            PredErrorSampleEntity first = rows.get(0);
            assertEquals(predVersion, first.getPredVersion(),
                    "pred_version 必须等于本 run 登记的版本；不等说明 recordVersion 没在 predict 前调用");
            assertNotNull(first.getSigmaAlongM(), "sigma_along_m 不得为空");
            assertTrue(first.getSigmaAlongM() > 0.0, "sigma_along_m 必须为正");
            assertTrue(first.getSigmaCrossM() > 0.0, "sigma_cross_m 必须为正");
            assertNotNull(first.getSampleTs(), "sample_ts 不得为空");
            assertTrue(result.tauGridSec().size() >= 2,
                    "τ 网格至少要有两档才有统计意义，实得 " + result.tauGridSec());
            for (int tau : result.tauGridSec()) {
                assertTrue(tau > 0, "τ 必须为正，实得 " + tau);
                assertTrue(tau <= OfflinePredictionFixture.HORIZON_SEC,
                        "τ 不得超过 horizonSec=" + OfflinePredictionFixture.HORIZON_SEC + "，实得 " + tau);
            }
            assertEquals(0, result.offGridSamples(),
                    "有 " + result.offGridSamples() + " 条样本的 τ 不在设计网格 "
                            + OfflinePredictionFixture.DESIGN_TAU_GRID + " 内，实测网格 "
                            + result.tauGridSec()
                            + "：说明探针配对错位（队列未按评估时刻排序），"
                            + "这类样本的 τ 不能当作分档依据");

            // ── 4. sys_param 零回写（契约 §10 第三参 null 的验收） ──
            Map<String, String> sysParamAfter = snapshotSysParam();
            assertEquals(sysParamBefore, sysParamAfter,
                    "离线夹具不得回写 sys_param；出现差异说明 forTesting 第三参没传 null，"
                            + "或某处绕过了空值守卫");

            // ── 5. 自检摘要：交给编排器与验收人看 ──
            System.out.printf("[FIXTURE] seed=%d mmsi=%s pred_version=%d path_start=%.1f m "
                            + "duration=%d s%n",
                    seed, spec.mmsi(), predVersion, pathStartM, durationSec);
            System.out.printf("[FIXTURE] truth_end_s=%.4f m  samples=%d  rows=%d  duplicated=%d  off_grid=%d%n",
                    result.endS(), result.samples(), result.rows(), result.duplicated(),
                    result.offGridSamples());
            System.out.printf("[FIXTURE] tau_grid=%s%n", result.tauGridSec());
            System.out.printf("[FIXTURE] sys_param unchanged (keys=%d)%n", sysParamAfter.size());
        } finally {
            sampleMapper.delete(mine);
        }
    }

    @Test
    @DisplayName("真值序列：1 Hz、终点不越全长、末段航速降到 2.5 m/s")
    void truthSeries_shouldBeIntegralAndWithinTotal() {
        OfflinePredictionFixture.Spec spec = OfflinePredictionFixture.Spec.subset(1);
        var geo = OfflinePredictionFixture.v3Centerline();
        List<OfflinePredictionFixture.TruthPoint> series =
                OfflinePredictionFixture.truthSeries(spec, geo);

        assertEquals(spec.durationSec() + 1, series.size(),
                "1 Hz 下应产出 duration+1 个点（含首点）");
        assertEquals(spec.pathStartM(), series.get(0).sM(), 1e-9,
                "首点应等于 path_start_m（t=0 时未推进）");

        // 20 min 子段从 1000 m 起：前 666.67 s 走 4.5 m/s，其后 2.5 m/s，
        // 终点 = 4000 + 533.333 × 2.5 = 5333.333 m（契约 §2 给的 5333.3）
        double endS = series.get(series.size() - 1).sM();
        assertEquals(5333.333, endS, 0.01, "20 min 子段终点应为 5333.3 m");

        for (OfflinePredictionFixture.TruthPoint point : series) {
            assertTrue(point.sM() <= OfflinePredictionFixture.CENTERLINE_TOTAL_M + 1e-9,
                    "真值点越界：" + point.sM());
            assertTrue(point.sogMs() == 0.0
                            || Math.abs(point.sogMs() - 4.5) < 1e-9
                            || Math.abs(point.sogMs() - 2.5) < 1e-9,
                    "真值航速只允许 4.5／2.5／0 三档，实得 " + point.sogMs());
        }

        // 跨切换点之后必须已经是 2.5 m/s：抓「用 4.12 恒定值当真值」这个错误
        OfflinePredictionFixture.TruthPoint afterSwitch =
                series.get((int) Math.ceil(667.0));
        assertEquals(2.5, afterSwitch.sogMs(), 1e-9,
                "跨过 4000 m 后真值航速必须降到 2.5 m/s");
    }

    private Map<String, String> snapshotSysParam() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        jdbcTemplate.query("select param_key, param_value from sys_param order by param_key",
                rs -> {
                    snapshot.put(rs.getString("param_key"), rs.getString("param_value"));
                });
        return snapshot;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static long longEnv(String name, long fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
    }

    private static double doubleEnv(String name, double fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
    }
}
