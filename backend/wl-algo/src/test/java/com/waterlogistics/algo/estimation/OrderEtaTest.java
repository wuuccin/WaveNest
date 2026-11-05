package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderEta} 单测（11-05 序号 2「ETA 计算」）。
 *
 * <p>验证点：三阶段三套口径各自的取值、飞中位置缺失时的回落、单调收敛。
 */
class OrderEtaTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    @DisplayName("未派单：下单时刻 + 时长下界（下界偏早，不对外承诺）")
    void beforeDispatch_shouldBeNowPlusLowerBound() {
        assertEquals(NOW + 570_000L, OrderEta.beforeDispatch(NOW, 570.0));
    }

    @Test
    @DisplayName("已派单：会面时刻 + 交接时长，与本次派发周期无关地固定")
    void dispatched_shouldBeMeetPlusHandover() {
        long meet = NOW + 300_000L;

        assertEquals(meet + 120_000L, OrderEta.dispatched(meet, 120));
        assertEquals(meet, OrderEta.dispatched(meet, 0), "交接时长 0 → 送达即会面");
    }

    @Test
    @DisplayName("飞中：当前时刻 + 剩余距离 ÷ 上限速度 + 交接时长")
    void inFlight_shouldBeNowPlusRemainingOverSpeed() {
        // 1000 m ÷ 20 m/s = 50 s，加交接 120 s
        long eta = OrderEta.inFlightOr(NOW, 1000.0, 20.0, 120, -1L);

        assertEquals(NOW + 50_000L + 120_000L, eta);
    }

    @Test
    @DisplayName("★ 飞中位置缺失（NaN/负值） → 回落已派单值，不抛异常")
    void inFlight_withoutPosition_shouldFallBack() {
        long fallback = NOW + 500_000L;

        assertEquals(fallback, OrderEta.inFlightOr(NOW, Double.NaN, 20.0, 120, fallback));
        assertEquals(fallback, OrderEta.inFlightOr(NOW, -1.0, 20.0, 120, fallback));
        assertEquals(fallback, OrderEta.inFlightOr(NOW, Double.POSITIVE_INFINITY, 20.0, 120,
                fallback));
    }

    @Test
    @DisplayName("入参校验：会面时刻非正、交接时长为负、速度非正 → 抛异常")
    void illegalInput_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> OrderEta.dispatched(0L, 120));
        assertThrows(IllegalArgumentException.class, () -> OrderEta.dispatched(NOW, -1));
        assertThrows(IllegalArgumentException.class,
                () -> OrderEta.inFlightOr(NOW, 100.0, 0.0, 120, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> OrderEta.inFlightOr(NOW, 100.0, Double.NaN, 120, -1L));
    }

    @Test
    @DisplayName("单调收敛：已发布值更晚时保留旧值，更早时采用新值")
    void notEarlierThan_shouldKeepTheLaterOne() {
        assertEquals(NOW, OrderEta.notEarlierThan(NOW, null), "首次发布直接用候选值");
        assertEquals(NOW + 1000L, OrderEta.notEarlierThan(NOW, NOW + 1000L), "候选更早 → 保留旧值");
        assertEquals(NOW + 2000L, OrderEta.notEarlierThan(NOW + 2000L, NOW + 1000L), "候选更晚 → 采用新值");
    }
}
