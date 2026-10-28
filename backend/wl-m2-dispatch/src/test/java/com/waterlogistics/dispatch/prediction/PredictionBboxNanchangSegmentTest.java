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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T2-6（10-28 任务清单）：预测校验框<b>定稿</b>——测试常量与装配配置一次改完、同值。
 *
 * <h3>两处定稿输入</h3>
 * <pre>
 *   航段（《73-赣江场景说明》§3.1 三桥坐标表）
 *     朝阳大桥 28.646 / 115.852  →  八一大桥 28.692 / 115.879，直线全长 5.78 km
 *   船起点（T2-2 彭紫情 10-28 序号 2 图上放样定稿，T1-5 灌库通报同值）
 *     115.863 / 28.668，T2-7 由 m1 仿真器侧生效
 * </pre>
 * T1-8 当时用的航段是 M1 仿真器的代码默认值（{@code 115.8610/28.6640 → 115.8760/28.6990}，
 * 4.16 km）——那个起点其实落在<b>南昌大桥</b>（28.663/115.859）旁边，既不是朝阳端也不是定稿起点。
 *
 * <h3>框为什么不是四向对称</h3>
 * <p>预测<b>沿航向单向前伸</b>：{@code sys_param.horizonSec = 1800 s} × 8 节（4.116 m/s）
 * ⇒ 尾巴比船头远 <b>7.408 km</b>，而航段只有 5.78 km。这 7.4 km 只加在东北侧；西南侧
 * 只需罩住起点＋噪声。四向对称外扩会把面积放大好几倍，等于白收窄。
 *
 * <p>东北界取两条航向的包络（航段方位角 27.24°、仿真器当前默认 21°；T2-7 只改起点、
 * 未要求改航向，两条都得罩住），实测（起点 115.863/28.668、回放 1401 拍）：
 * <pre>
 *   cog 27.24°：预测包络 lon 115.862983–115.916088  lat 28.668050–28.758476
 *   cog 21.00°：预测包络 lon 115.862979–115.904566  lat 28.668050–28.763004
 *   各留 0.5 km（lat 0.0045°、lon 0.005°）⇒ 115.857–115.921 × 28.660–28.767
 * </pre>
 * 西南界多留一档（0.006–0.008°）是为了同时罩住<b>旧起点</b> {@code 115.861/28.664}：
 * T2-7 尚未合并时 m1 仿真器仍发旧值，若框跟着定稿起点一起北移、把旧起点甩在外面，
 * 合并前所有人跑链路都会被我这个框拦成匀速版。
 *
 * <p><b>包络上界低于理论尾巴终值</b>，是「参考线 945 s 后被夹」的已知限制造成的
 * （《10-28 任务清单》§六 第 2 条，归 29 号 T3）。框按 1400 拍（≈船过八一端再走 700 s）
 * 的实测上界 ＋ 0.5 km 定，不按无限远外推——否则框会宽到失去收窄意义。
 *
 * <h3>值必须与配置同源</h3>
 * <p>四个常量是「被测的框」，仓库存量真值是
 * {@code backend/wl-app/src/main/resources/application.yml} 的
 * {@code wavenest.m2.prediction.bbox.*}。T2-6 起两处<b>由同一人一次改完</b>（清单 §三
 * 已把 {@code application.yml} 划给本人），故配置守卫不「未落地则跳过」，而是
 * 缺失即红——配置被删或被改成别的值都会红。
 */
class PredictionBboxNanchangSegmentTest {

    // ───────── 航段真值（《73-赣江场景说明》§3.1 三桥坐标表）─────────

    /** 朝阳大桥，航段西南端（服务航段起点）。 */
    private static final double CHAOYANG_LON = 115.852;
    private static final double CHAOYANG_LAT = 28.646;

    /** 八一大桥，航段东北端。 */
    private static final double BAYI_LON = 115.879;
    private static final double BAYI_LAT = 28.692;

    // ───────── 船起点定稿（T2-2 图上放样 / T1-5 灌库通报）─────────

    /** 定稿船起点：T2-7 由 m1 仿真器侧生效。 */
    private static final double START_LON = 115.863;
    private static final double START_LAT = 28.668;

    /** T2-7 合并前仿真器仍在发的旧起点（代码默认值）。框要同时罩住它。 */
    private static final double LEGACY_START_LON = 115.861;
    private static final double LEGACY_START_LAT = 28.664;

    // ───────── T2-6 定稿的校验框（须与 application.yml 同值）─────────

    private static final double MIN_LON = 115.857;
    private static final double MAX_LON = 115.921;
    private static final double MIN_LAT = 28.660;
    private static final double MAX_LAT = 28.767;

    // ───────── 仿真喂法真值（SimAisConnector 的代码默认值）─────────

    private static final long T0 = 1_760_000_000_000L;
    private static final String MMSI = "123456789";
    private static final double SOG_KNOTS = 8.0;
    private static final double KNOT_TO_MS = 1852.0 / 3600.0;
    private static final double MPD_LAT = 111_320.0;

    /** 定稿航段的方位角（朝阳→八一，大圆方位 27.24°）。 */
    private static final double COG_LEG_DEG = 27.24;

    /** M1 仿真器当前的代码默认航向（T2-7 只改起点，航向未定是否同步）。 */
    private static final double COG_LEGACY_DEG = 21.0;

    /** 仿真 AIS 噪声：{@code noise: 0.00005} 度 ≈ 5.5 m。 */
    private static final double POSITION_NOISE_DEG = 0.00005;

    /** 主回放拍数：1400 拍 ≈ 24 min，船过八一端后再走 700 s，覆盖演示 15 min 之后的一段。 */
    private static final int TICKS = 1400;

    private final PredictionSettings settings =
            new PredictionSettings(20.0, 0.05, 15.0, 0.03, 1800L, 60L, 900L);

    /** 被测的网：定稿到南昌航段走廊 ＋ 航向侧一个预测时域。 */
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
    @DisplayName("★ 定稿框：定稿起点按航段方位角 27.24° 走，全程不被拦、点位全在框内")
    void narrowedBounds_shouldNotRejectAnyFrameOnSegment() {
        replayAndAssert("定稿起点/航段方位角", START_LON, START_LAT, COG_LEG_DEG, TICKS, 20261030L);
    }

    @Test
    @DisplayName("★ 定稿框：定稿起点按仿真器默认航向 21°，同样全程不被拦")
    void narrowedBounds_shouldAlsoCoverLegacyCog() {
        // T2-7 只要求改船起点，未要求改航向；仿真器代码里 cog 仍是 21°。
        // 两条航向都要罩住，否则改完起点、没改航向的那一版会被框悄悄拦成匀速版。
        replayAndAssert("定稿起点/默认航向", START_LON, START_LAT, COG_LEGACY_DEG, TICKS, 20261031L);
    }

    @Test
    @DisplayName("★ 定稿框：兼容 T2-7 合并前的旧起点 115.861/28.664，不被拦")
    void narrowedBounds_shouldAlsoCoverLegacyStart() {
        // 我的 PR 可能先合、T2-7 后合。这期间 m1 仿真器仍发旧起点 28.664，
        // 若框跟着定稿起点一起北移、把旧起点甩在外面，别人跑链路会全线回落匀速版。
        // 旧起点比定稿起点南 445 m，走到八一端需 ≈810 s，故跑 900 拍而非 700。
        replayAndAssert("旧起点/默认航向", LEGACY_START_LON, LEGACY_START_LAT, COG_LEGACY_DEG,
                900, 20261101L);
    }

    /** 从给定起点按给定航向回放，断言零拦截、点位全在框内、确实走进了航段。 */
    private void replayAndAssert(String label, double startLon, double startLat, double cogDeg,
            int ticks, long seed) {
        PredictionService service = service();
        Random random = new Random(seed);

        double maxShipLon = -999.0;
        double maxShipLat = -999.0;
        int preciseFrames = 0;
        PredictionSnapshot last = null;

        for (int tick = 0; tick <= ticks; tick++) {
            double east = SOG_KNOTS * KNOT_TO_MS * tick * Math.sin(Math.toRadians(cogDeg));
            double north = SOG_KNOTS * KNOT_TO_MS * tick * Math.cos(Math.toRadians(cogDeg));
            double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(startLat));

            double lon = startLon + east / metersPerDegLon
                    + POSITION_NOISE_DEG * random.nextGaussian();
            double lat = startLat + north / MPD_LAT
                    + POSITION_NOISE_DEG * random.nextGaussian();

            last = service.refresh(new AisMessage(MMSI, "SIM-SHIP-01", lon, lat, SOG_KNOTS,
                    cogDeg, T0 + tick * 1000L));

            assertNotNull(last.points(), label + "：第 " + tick + " 拍快照缺失");
            assertEquals(31, last.points().size(),
                    label + "：第 " + tick + " 拍点数不对（应为 1800/60＋1）");
            if (last.zSeries() != null) {
                preciseFrames++;
            }

            maxShipLon = Math.max(maxShipLon, lon);
            maxShipLat = Math.max(maxShipLat, lat);
            assertAllInside(last, label, tick);
        }

        assertEquals(0L, service.preciseRejectionCount(),
                label + "：定稿后仍有帧被断言网拦下——框设得比预测时域可达域还紧，"
                        + "这类帧会静默回落匀速版，看起来就像「切了等于没切」");
        assertTrue(preciseFrames >= ticks - 1,
                label + "：精算帧数过少（" + preciseFrames + "/" + (ticks + 1) + "），多数拍回落了匀速版；"
                        + "只有第 0 拍属冷启动（窗口不足两点）回落匀速版，本属预期");

        // 船确实走完了航段（否则「不被拦」可能只是回放太短没到边界）
        assertTrue(maxShipLat >= BAYI_LAT - 0.002,
                label + "：回放没走到八一端（末船位 " + maxShipLon + "," + maxShipLat + "），"
                        + "本测试的覆盖不成立");

        // 末帧尾巴必须仍在框内，且不离框边太远——证明框「刚好罩住预测时域」而不是无约束
        ShipTrajPoint tail = last.points().get(last.points().size() - 1);
        assertTrue(assurance.bounds().contains(tail.lon(), tail.lat()),
                label + "：末点落在框外：" + tail.lon() + "," + tail.lat());
        assertTrue(MAX_LAT - tail.lat() < 0.05 && MAX_LON - tail.lon() < 0.05,
                label + "：末点离框边过远，说明框放得太松（lat 余 " + (MAX_LAT - tail.lat())
                        + "，lon 余 " + (MAX_LON - tail.lon()) + "）");

        System.out.printf(java.util.Locale.ROOT,
                "%n[T2-6] %s cog %.2f° 回放 %d 拍：精算 %d、拦截 %d、末帧版本 %d、"
                        + "末点 %.5f/%.5f（框余 lat %.4f / lon %.4f）%n",
                label, cogDeg, ticks + 1, preciseFrames, service.preciseRejectionCount(),
                last.version(), tail.lat(), tail.lon(),
                MAX_LAT - tail.lat(), MAX_LON - tail.lon());
    }

    // ───────────────────────── 判据 ② 后半：框外必被拦 ─────────────────────────

    @Test
    @DisplayName("★ 定稿框：喂原仿真点 114.30/30.60 必被拦下，本帧回落匀速版")
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
                "%n[T2-6] 原仿真点（114.30/30.60）判定：%s，连续拦截计数 %d%n",
                violation.get().code().code(), service.preciseRejectionCount());
    }

    @Test
    @DisplayName("★ 定稿框：落在 T1-8 旧框内、但已出新框北界的点必须被拦（证明框真收窄了）")
    void tailBeyondNewBounds_mustBeRejected() {
        // 115.895 / 28.770：在 T1-8 旧框（115.850–115.910 × 28.655–28.770）之内，
        // 但在 T2-6 定稿框北界（28.767）之外。若仍放行，说明收窄没落地。
        java.util.Optional<PredictionAssurance.Violation> violation = assurance.check(
                T0, T0 + 3_600_000L, 900L,
                List.of(new com.waterlogistics.algo.estimation.EstimatedPoint(
                        T0, 115.895, 28.770, 20.0, 15.0)));
        assertTrue(violation.isPresent(), "28.770 已在新框北界（28.767）之外，必须判违约");
        assertEquals(PredictionAssurance.Code.OUT_OF_BOUNDS, violation.get().code());
    }

    // ───────────────────────── 值一致性守卫 ─────────────────────────

    @Test
    @DisplayName("配置守卫：application.yml 的 bbox 四界必须与本类常量同值（缺失即红）")
    void configuredBounds_mustMatchTestedBounds() throws IOException {
        Path yml = Path.of("..", "wl-app", "src", "main", "resources", "application.yml");
        assertTrue(Files.exists(yml), "找不到 " + yml.toAbsolutePath() + "（请在模块目录运行测试）");

        String text = Files.readString(yml, StandardCharsets.UTF_8);
        Double configuredMinLon = readDouble(text, "min-lon");
        assertNotNull(configuredMinLon,
                "application.yml 缺 wavenest.m2.prediction.bbox 块——T2-6 已定稿，配置必须落地；"
                        + "缺失时断言网会回落到 PredictionAssurance.Bounds.demoArea() 的宽框，收窄等于没做");

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

    private void assertAllInside(PredictionSnapshot snapshot, String label, int tick) {
        for (ShipTrajPoint point : snapshot.points()) {
            assertTrue(assurance.bounds().contains(point.lon(), point.lat()),
                    label + "：第 " + tick + " 拍有点落在定稿框外：lon=" + point.lon()
                            + " lat=" + point.lat());
        }
    }
}
