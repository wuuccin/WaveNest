package com.waterlogistics.dispatch.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T1-8（10-27 任务清单）：预测链路在南昌航段跑通，再收窄地理校验区间。
 *
 * <p>清单给的是两步、两条判据：①<b>跑通</b>——主故事预测帧在航段上正常出帧；
 * ②<b>收窄</b>——主故事预测帧<b>不被拦</b>，同时故意喂一个航段外坐标<b>必须被拦下</b>。
 * 本类把这两条判据落成可判定、可进 CI 的等价物。
 *
 * <h3>为什么不能把框设成严格的航段外包框（实测结论，不是推测）</h3>
 * <p>用 M1 仿真真值回放 900 拍（1 Hz、8 节＝4.115 m/s、航向 21°、噪声 0.00005°）量得：
 * <pre>
 *   船位包络    lon 115.8609 – 115.8746   lat 28.6639 – 28.6950   （15 min 走完 4.16 km 航段）
 *   预测点包络  lon 115.8610 – 115.8883   lat 28.6640 – 28.7262
 *   单帧首末点跨度 7.416 km
 * </pre>
 * 跨度为什么是 7.4 km：{@code sys_param.horizonSec = 1800 s}，4.115 m/s × 1800 s = 7.41 km，
 * 而朝阳—八一航段全长只有 4.16 km —— <b>预测尾巴必然越过八一端约 3.2 km</b>。
 * 若把框钉在航段外包框（约 28.658–28.705），每一帧都会被自己的网拦下、静默回落匀速版，
 * 正是清单警示的「先收窄、后切坐标的后果是切了等于没切」。
 *
 * <p>故本类采用的框是<b>航段走廊 ＋ 一个预测时域的前伸</b>：
 * {@code 115.850–115.910 × 28.655–28.770}。零点是航段两端，前伸量按 8 节 × 1800 s 的行程
 * （Δlon 0.0272°、Δlat 0.0619°）算，再留 0.5–0.8 km 余量。面积比原并集
 * （{@code 114.00–116.50 × 28.30–30.90}）小约三个数量级，仍能把原仿真点
 * （{@code 114.30E / 30.60N}，距航段 100 km 以上）钉在框外。
 *
 * <h3>值必须与配置同源</h3>
 * <p>本类的四个常量是「被测的框」，仓库存量真值在
 * {@code backend/wl-app/src/main/resources/application.yml} 的
 * {@code wavenest.m2.prediction.bbox.*}。T1-8 的配置改动按分工走吴可欣的装配区，
 * 未合入时本类第 3 条测试自动跳过；合入后若两处不同值则红——防止「配置改了、网没跟着改」。
 */
class PredictionBboxNanchangSegmentTest {

    // ───────── 航段真值（T1-4 仿真器代码默认值，Commits 0ff3cb7）─────────

    /** 朝阳端（航段西南端，1 号巢一带）。 */
    private static final double CHAOYANG_LON = 115.8610;
    private static final double CHAOYANG_LAT = 28.6640;

    /** 八一端（航段东北端，2 号巢一带）。 */
    private static final double BAYI_LON = 115.8760;
    private static final double BAYI_LAT = 28.6990;

    // ───────── T1-8 收窄后的校验框（须与 application.yml 同值）─────────

    private static final double MIN_LON = 115.850;
    private static final double MAX_LON = 115.910;
    private static final double MIN_LAT = 28.655;
    private static final double MAX_LAT = 28.770;

    // ───────── 仿真喂法真值（application-m1-sim.yml 与代码默认值）─────────

    private static final long T0 = 1_760_000_000_000L;
    private static final String MMSI = "123456789";
    private static final double COG_DEG = 21.0;
    private static final double SOG_KNOTS = 8.0;
    private static final double KNOT_TO_MS = 1852.0 / 3600.0;
    private static final double MPD_LAT = 111_320.0;

    /** 仿真 AIS 噪声：{@code noise: 0.00005} 度 ≈ 5.5 m。 */
    private static final double POSITION_NOISE_DEG = 0.00005;

    /** 回放拍数：1010 拍≈16.8 min，正好把 4.16 km 航段走完（4.16 km ÷ 4.115 m/s ≈ 1011 s）。 */
    private static final int TICKS = 1010;

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    /** 被测的网：收窄到南昌航段走廊。 */
    private final PredictionAssurance assurance =
            new PredictionAssurance(new PredictionAssurance.Bounds(MIN_LON, MAX_LON, MIN_LAT, MAX_LAT));

    private PredictionService service() {
        PredictionProfile profile = PredictionProfile.precise(settings, assurance,
                PrecisePredictionEngine.Params.defaults());
        return new PredictionService(settings, new LoggingRecomputeRequester(), event -> { },
                profile, () -> T0 + 3_600_000L);
    }

    // ───────────────────────── 判据 ① 跑通 + 判据 ② 不被拦 ─────────────────────────

    @Test
    @DisplayName("★ 收窄后：南昌航段回放 1010 拍，精算帧全程不被拦、点位全部留在框内")
    void narrowedBounds_shouldNotRejectAnyFrameOnSegment() {
        PredictionService service = service();
        Random random = new Random(20261027L);

        double maxShipLon = -999.0;
        double maxShipLat = -999.0;
        int preciseFrames = 0;
        PredictionSnapshot last = null;

        for (int tick = 0; tick <= TICKS; tick++) {
            double east = SOG_KNOTS * KNOT_TO_MS * tick * Math.sin(Math.toRadians(COG_DEG));
            double north = SOG_KNOTS * KNOT_TO_MS * tick * Math.cos(Math.toRadians(COG_DEG));
            double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(CHAOYANG_LAT));

            double lon = CHAOYANG_LON + east / metersPerDegLon + POSITION_NOISE_DEG * random.nextGaussian();
            double lat = CHAOYANG_LAT + north / MPD_LAT + POSITION_NOISE_DEG * random.nextGaussian();

            last = service.refresh(new AisMessage(MMSI, "SIM-SHIP-01", lon, lat, SOG_KNOTS,
                    COG_DEG, T0 + tick * 1000L));

            assertNotNull(last.points(), "第 " + tick + " 拍快照缺失");
            assertEquals(31, last.points().size(), "第 " + tick + " 拍点数不对（应为 1800/60＋1）");
            if (last.zSeries() != null) {
                preciseFrames++;
            }

            maxShipLon = Math.max(maxShipLon, lon);
            maxShipLat = Math.max(maxShipLat, lat);
            assertAllInside(last, tick);
        }

        assertEquals(0L, service.preciseRejectionCount(),
                "收窄后仍有帧被断言网拦下——框设得比预测时域可达域还紧，"
                        + "这类帧会静默回落匀速版，看起来就像「切了等于没切」");
        assertTrue(preciseFrames >= TICKS - 2,
                "精算帧数过少（" + preciseFrames + "/" + (TICKS + 1) + "），多数拍回落了匀速版；"
                        + "只有第 0 拍属冷启动（窗口不足两点）回落匀速版，本属预期");

        // 船确实走完了航段（否则「不被拦」可能只是回放太短没到边界）
        assertTrue(maxShipLon >= BAYI_LON - 0.002 && maxShipLat >= BAYI_LAT - 0.002,
                "回放没走到八一端（末船位 " + maxShipLon + "," + maxShipLat + "），本测试的覆盖不成立");

        // 末帧尾巴必须贴近但不出框：证明框是「刚好罩住预测时域」而不是无约束
        ShipTrajPoint tail = last.points().get(last.points().size() - 1);
        assertTrue(assurance.bounds().contains(tail.lon(), tail.lat()),
                "末点落在框外：" + tail.lon() + "," + tail.lat());
        assertTrue(MAX_LAT - tail.lat() < 0.05 && MAX_LON - tail.lon() < 0.05,
                "末点离框边过远，说明框放得太松（lat 余 " + (MAX_LAT - tail.lat())
                        + "，lon 余 " + (MAX_LON - tail.lon()) + "）");

        System.out.printf(java.util.Locale.ROOT,
                "%n[T1-8] 南昌段回放 %d 拍：精算帧 %d、拦截 %d、末帧版本 %d、有效期 %d s、"
                        + "末点 %.5f/%.5f（框余 lat %.4f / lon %.4f）%n",
                TICKS + 1, preciseFrames, service.preciseRejectionCount(), last.version(),
                last.validitySec(), tail.lat(), tail.lon(), MAX_LAT - tail.lat(), MAX_LON - tail.lon());
    }

    // ───────────────────────── 判据 ② 后半：航段外必被拦 ─────────────────────────

    @Test
    @DisplayName("★ 收窄后：喂原仿真点 114.30/30.60 必被拦下，本帧回落匀速版")
    void oldSimulatorPoint_mustBeRejected() {
        PredictionService service = service();

        // 原仿真域起点（M1 切南昌段之前的代码默认值），航向 90°（旧默认值）。
        // 喂三拍：第 0 拍冷启动未完成（窗口仅一点），第 1、2 拍才真产出精算点列，
        // 才能验到「精算输出被地理框拦下」这条判据。
        PredictionSnapshot last = null;
        for (int tick = 0; tick <= 2; tick++) {
            double east = SOG_KNOTS * KNOT_TO_MS * tick * Math.sin(Math.toRadians(90.0));
            double north = SOG_KNOTS * KNOT_TO_MS * tick * Math.cos(Math.toRadians(90.0));
            double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(30.60));
            last = service.refresh(new AisMessage("OLD-SIM", "OLD-SHIP",
                    114.3000 + east / metersPerDegLon, 30.6000 + north / MPD_LAT,
                    SOG_KNOTS, 90.0, T0 + tick * 1000L));
        }

        assertNotNull(last, "第三拍应有快照");
        assertTrue(service.preciseRejectionCount() >= 1L,
                "航段外的坐标没有被拦下——校验网形同虚设，这条是「网真在工作」的唯一证据");
        assertNull(last.zSeries(),
                "被拦的这一帧应回落匀速版（zSeries 为 null 是匀速版的占位口径）");

        // 直接对网判一次，把违约代码钉死为 OUT_OF_BOUNDS（不是别的违约凑巧触发）
        java.util.Optional<PredictionAssurance.Violation> violation = assurance.check(
                T0, T0 + 3_600_000L, 900L,
                List.of(new com.waterlogistics.algo.estimation.EstimatedPoint(
                        T0, 114.3000, 30.6000, 20.0, 15.0)));
        assertTrue(violation.isPresent(), "原仿真点必须判违约");
        assertEquals(PredictionAssurance.Code.OUT_OF_BOUNDS, violation.get().code(),
                "违约代码应为 OUT_OF_BOUNDS，实际 " + violation.get().code().code());

        System.out.printf(java.util.Locale.ROOT,
                "%n[T1-8] 原仿真点（114.30/30.60）判定：%s，连续拦截计数 %d%n",
                violation.get().code().code(), service.preciseRejectionCount());
    }

    // ───────────────────────── 值一致性守卫 ─────────────────────────

    @Test
    @DisplayName("配置守卫：application.yml 的 bbox 四界与本类常量同值（未落地则跳过）")
    void configuredBounds_mustMatchTestedBounds() throws IOException {
        Path yml = Path.of("..", "wl-app", "src", "main", "resources", "application.yml");
        Assumptions.assumeTrue(Files.exists(yml),
                "找不到 " + yml.toAbsolutePath() + "（非模块目录运行），跳过配置守卫");

        String text = Files.readString(yml, StandardCharsets.UTF_8);
        Double configuredMinLon = readDouble(text, "min-lon");
        Assumptions.assumeTrue(configuredMinLon != null,
                "application.yml 尚无 wavenest.m2.prediction.bbox 块（T1-8 的配置改动由吴可欣合入）");

        assertEquals(MIN_LON, configuredMinLon, 1e-9, "min-lon 与测试常量不同值");
        assertEquals(MAX_LON, readDouble(text, "max-lon"), 1e-9, "max-lon 与测试常量不同值");
        assertEquals(MIN_LAT, readDouble(text, "min-lat"), 1e-9, "min-lat 与测试常量不同值");
        assertEquals(MAX_LAT, readDouble(text, "max-lat"), 1e-9, "max-lat 与测试常量不同值");
    }

    /** 从 yml 文本里取 {@code wavenest.m2.prediction.bbox} 块下的某个键；不存在返回 null。 */
    private static Double readDouble(String text, String key) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*([-+0-9.eE]+)\\s*(?:#.*)?$")
                .matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return Double.parseDouble(matcher.group(1));
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private void assertAllInside(PredictionSnapshot snapshot, int tick) {
        for (ShipTrajPoint point : snapshot.points()) {
            assertTrue(assurance.bounds().contains(point.lon(), point.lat()),
                    "第 " + tick + " 拍有点落在收窄后的框外：lon=" + point.lon()
                            + " lat=" + point.lat());
        }
    }
}
