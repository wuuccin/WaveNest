package com.waterlogistics.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.dispatch.prediction.CenterlineSpeedLimitLookup;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分段限速配置的「真的生效」校验（11-05 序号 1 · 吴可欣）。
 *
 * <p>硬判据 7 的前半段：起服务后 {@code CenterlineSpeedLimitLookup.parse} 必须返回非
 * {@code UNBOUNDED}，且末段（4000–5780 m）限速低于实测 sog。
 *
 * <p>本测试直接读 classpath 里的 {@code application.yml}：配置漏改、串写错、末段限速又调回
 * 高于实测 sog，这里立刻红——不必等到起服务才发现「限速没生效」。
 */
class SegmentSpeedLimitConfigTest {

    /** 末段（4000–5780 m）的限速，m/s。 */
    private static final double LAST_SEGMENT_LIMIT_MS = 2.5;

    /** 实测 sog，m/s（11-05 现场值）。 */
    private static final double MEASURED_SOG_MS = 4.12;

    @Test
    @DisplayName("配置串已填，且末段限速低于实测 sog（否则无法自证限速生效）")
    void config_shouldBeFilledAndLastSegmentBelowSog() throws Exception {
        String spec = readSegmentSpeedLimits();

        assertTrue(!spec.isBlank(), "segment-speed-limits 不得为空（空＝全航段不限速）");
        assertTrue(Pattern.compile("4000,\\s*5780,\\s*" + LAST_SEGMENT_LIMIT_MS).matcher(spec).find(),
                "末段（4000–5780 m）限速必须是 " + LAST_SEGMENT_LIMIT_MS + " m/s，实读：" + spec);
        assertTrue(LAST_SEGMENT_LIMIT_MS < MEASURED_SOG_MS,
                "末段限速必须低于实测 sog " + MEASURED_SOG_MS + " m/s");
    }

    @Test
    @DisplayName("解析结果非不限速：前两段 4.5、末段 2.5、四个切换点齐全")
    void parsedLookup_shouldBoundLastSegment() throws Exception {
        CenterlineSpeedLimitLookup lookup = CenterlineSpeedLimitLookup.parse(readSegmentSpeedLimits());

        assertEquals(4.5, lookup.speedLimitMs(1000.0), 1e-9, "首段限速");
        assertEquals(4.5, lookup.speedLimitMs(2500.0), 1e-9, "第二段限速");
        assertEquals(LAST_SEGMENT_LIMIT_MS, lookup.speedLimitMs(4500.0), 1e-9, "末段限速");
        assertEquals(Double.MAX_VALUE, lookup.speedLimitMs(5780.0), 0.0,
                "末段右开，末段之外不限速");
        assertArrayEquals(new double[]{0.0, 2000.0, 4000.0, 5780.0},
                lookup.switchPointsM(), 1e-9, "限速切换点必须齐全，预测器据此切子步");
    }

    /** 从 classpath 的 {@code application.yml} 读出配置串。 */
    private static String readSegmentSpeedLimits() throws Exception {
        try (InputStream in = SegmentSpeedLimitConfigTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath 里找不到 application.yml");
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("segment-speed-limits:\\s*\"?([^\"\\n]*)\"?")
                    .matcher(yaml);
            assertTrue(matcher.find(), "application.yml 里没有 segment-speed-limits 配置项");
            return matcher.group(1).trim();
        }
    }
}
