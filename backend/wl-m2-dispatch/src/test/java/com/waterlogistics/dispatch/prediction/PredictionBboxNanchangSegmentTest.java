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
 *   船起点（T2-2 彭紫情 10-28 图上放样定稿；10-29 序号 2 拍板沿程 2.40 km）
 *     115.8611 / 28.6660，T2-7 已在 m1 仿真器侧生效
 * </pre>
 * T1-8 当时用的航段是 M1 仿真器的代码默认值（{@code 115.8610/28.6640 → 115.8760/28.6990}，
 * 4.16 km）——那个起点其实落在<b>南昌大桥</b>（28.663/115.859）旁边，既不是朝阳端也不是定稿起点。
 *
 * <h3>框为什么不是四向对称</h3>
 * <p>预测<b>沿航向单向前伸</b>：{@code sys_param.horizonSec = 1800 s} × 8 节（4.116 m/s）
 * ⇒ 尾巴比船头远 <b>7.408 km</b>，而航段只有 5.78 km。这 7.4 km 只加在东北侧；西南侧
 * 只需罩住起点＋噪声。四向对称外扩会把面积放大好几倍，等于白收窄。
 *
 * <p>东北界取两条航向的包络（航段方位角 27.24°、仿真器现值 31.2°——10-29 序号 1 已由
 * 6.7° 改为 31.2°，见《船航向错误修复方案》§三），实测（定稿起点 115.8611/28.6660、
 * 回放 1400 拍）：
 * <pre>
 *   cog 27.24°：预测包络 lon 115.861144–115.914173  lat 28.666066–28.756451
 *   cog 31.20°：预测包络 lon 115.861149–115.921164  lat 28.666064–28.753018
 *   各留 0.5 km（lat 0.0045°、lon 0.005°）⇒ 115.857–115.921 × 28.660–28.767
 * </pre>
 * 西南界留 0.006°（≈666 m）不贴到起点 28.6660：位置噪声是 ±0.00005°（5.5 m）级，
 * 留三个数量级的余量，避免航向或起点做微小调整时南界立刻卡住。
 *
 * <p><b>★ 31.2° 的 lon 上界 115.921164 比框东界 115.921 超出约 16 m</b>，这是实测事实，
 * 不是笔误。它导致一个<b>精确可复现</b>的边界：船跑到 <b>945 s</b> 时整帧判越界、回落匀速版。
 *
 * <p>945 s 的来历（不是代码里的字面量，是三个参数的必然结果）：
 * <pre>
 *   参考线总长 L = 冷启动实测段 ＋ extensionM(1800 s × vMaxCraftMs 6.0 ＋ 500) = 11310 m
 *   预测末点需要  horizonSec × v = 1800 × 4.12 = 7416 m
 *   末点贴到线尾： s + 7416 ≥ 11310  ⇒  t ≥ (11310 − 7416) / 4.12 ≈ 945 s
 * </pre>
 * 末点被 {@code MultiStepPredictor.clampToWaterway} 夹在参考线端点后固定在 115.921162，
 * 比框东界多一点，于是整帧越界。此后尾部 9 个点会逐渐重合（实测 1500 拍时 zeroGap = 9）。
 *
 * <p><b>框不为这种尾巴放宽</b>：越界恰好把「9 点重合」的坏轨迹拦成匀速版，比放行一根
 * 棒槌更好。真缺陷是参考线只在冷启动建一次、不在船逼近线尾时提前重建（《10-29 台账》
 * §六 第 2 条归 T3），不在本框职责内。演示口径是 15 分钟内（台账 §四），900 s 时末点
 * 115.920032 仍在框内、余约 97 m，故演示不触发；{@code t945IsTheRealBoundary()} 把这条
 * 边界钉成可测事实，任何人改了航速／起点／horizon 导致边界移动，该用例会红。
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

    // ───────── 船起点定稿（10-29 序号 2 拍板：沿程 2.40 km）─────────

    /** 定稿船起点：T2-7 已在 m1 仿真器侧生效（{@code SimAisConnector} 代码默认值同值）。 */
    private static final double START_LON = 115.8611;
    private static final double START_LAT = 28.6660;

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

    /** M1 仿真器当前航向（10-29 序号 1 已由 6.7° 改为 31.2°）。 */
    private static final double COG_SIM_DEG = 31.2;

    /** 仿真 AIS 噪声：{@code noise: 0.00005} 度 ≈ 5.5 m。 */
    private static final double POSITION_NOISE_DEG = 0.00005;

    /** 主回放拍数：1400 拍 ≈ 24 min，船过八一端后再走 700 s，覆盖演示 15 min 之后的一段。 */
    private static final int TICKS = 1400;

    /** 演示窗口：台账 §四「演示时长 15 分钟内」，900 拍是判据要守住的那一段。 */
    private static final int DEMO_TICKS = 900;

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
    @DisplayName("★ 定稿框：定稿起点按仿真器现值 31.2° 走完演示窗口 900 拍，不被拦")
    void narrowedBounds_shouldCoverSimulatorCogWithinDemoWindow() {
        // 10-29 序号 1 把仿真器航向由 6.7° 改为 31.2°。这是**链路上真正会跑的航向**，
        // 所以它必须在演示窗口内零拦截。900 拍＝台账 §四的「演示 15 分钟内」。
        // 不跑到 1400 拍是因为 945 s 起末点会越出东界（见类注释的 945 s 推导与下面的边界用例）：
        // 那不是框设错，是参考线被夹坏后的坏尾巴被正确拦下。
        replayAndAssert("定稿起点/仿真器航向", START_LON, START_LAT, COG_SIM_DEG, DEMO_TICKS,
                20261031L);
    }

    @Test
    @DisplayName("★ 边界：31.2° 下第 945 拍起整帧越界回落匀速版（把边界钉成可测事实）")
    void t945IsTheRealBoundary() {
        // 这条不是「框错了」，是「边界在哪」的实测记录：
        //   参考线总长 L = 11310 m（冷启动段 ＋ 1800 s×6 m/s ＋ 500 m 延伸）
        //   末点需要 1800×4.12 = 7416 m 弧长 ⇒ t ≥ (11310−7416)/4.12 ≈ 945 s 时末点贴到线尾
        //   被 clampToWaterway 夹住的末点固定在 115.921162，比框东界 115.921 多约 16 m ⇒ 判越界
        // 任何人改了航速／起点／horizonSec／vMaxCraftMs 让这条边界移动，本用例会红，
        // 应重新量一遍而不是把区间放宽了事。
        int tick = firstRejectionTick(COG_SIM_DEG, 1000, 20261032L);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[T2-6] 边界：31.2° 首次整帧越界 tick=%d（推导区间 900–990，理论 945）", tick));
        assertTrue(tick >= 900 && tick <= 990,
                "首次越界的拍数落在 " + tick + "，与 945±45 的推导不符："
                        + "参考线长度／预测时域／航速任一改动都会移动这条边界，请重新量包络");
    }

    /** 逐拍回放，返回「首次整帧被判越界」的拍数；不出现则返回 -1。 */
    private int firstRejectionTick(double cogDeg, int ticks, long seed) {
        PredictionService service = service();
        Random random = new Random(seed);
        for (int tick = 0; tick <= ticks; tick++) {
            double east = SOG_KNOTS * KNOT_TO_MS * tick * Math.sin(Math.toRadians(cogDeg));
            double north = SOG_KNOTS * KNOT_TO_MS * tick * Math.cos(Math.toRadians(cogDeg));
            double metersPerDegLon = MPD_LAT * Math.cos(Math.toRadians(START_LAT));
            double lon = START_LON + east / metersPerDegLon
                    + POSITION_NOISE_DEG * (2 * random.nextDouble() - 1.0);
            double lat = START_LAT + north / MPD_LAT
                    + POSITION_NOISE_DEG * (2 * random.nextDouble() - 1.0);

            long before = service.preciseRejectionCount();
            service.refresh(new AisMessage(MMSI, "SIM-SHIP-01", lon, lat, SOG_KNOTS, cogDeg,
                    T0 + tick * 1000L));
            if (service.preciseRejectionCount() > before) {
                return tick;
            }
        }
        return -1;
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
