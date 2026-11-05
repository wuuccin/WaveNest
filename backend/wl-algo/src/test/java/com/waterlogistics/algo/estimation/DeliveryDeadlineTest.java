package com.waterlogistics.algo.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DeliveryDeadline} 单测（11-05 序号 2「deadline 系统计算」）。
 *
 * <p>验证点：两者取小、驶出时刻不可得时退化、量纲写错被拦、预测缺失不抛异常。
 */
class DeliveryDeadlineTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    @DisplayName("船驶出航段早于业务上限 → 取驶出时刻")
    void shipExitsFirst_shouldTakeExit() {
        long shipExit = NOW + 750_000L;      // 750 s 后驶出

        long deadline = DeliveryDeadline.compute(NOW, shipExit, 1800L);

        assertEquals(shipExit, deadline);
    }

    @Test
    @DisplayName("业务上限早于船驶出 → 取上限（船长期停在段内也不会无限放宽）")
    void capEarlier_shouldTakeCap() {
        long shipExit = NOW + 3_600_000L;    // 1 h 后才驶出

        long deadline = DeliveryDeadline.compute(NOW, shipExit, 1800L);

        assertEquals(NOW + 1_800_000L, deadline, "上限 1800 s 生效");
    }

    @Test
    @DisplayName("驶出时刻不可得（预测时域内不驶出/无预测） → 退化为下单+上限，不抛异常")
    void unknownExit_shouldFallBackToCap() {
        assertEquals(NOW + 1_800_000L, DeliveryDeadline.compute(NOW, null, 1800L));
    }

    @Test
    @DisplayName("时限非法（0/负/超一天） → 抛异常，不静默产生离谱 deadline")
    void illegalMaxDelivery_shouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryDeadline.compute(NOW, null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryDeadline.compute(NOW, null, -1L));
        // 把毫秒当秒填的典型错法：1_800_000 秒 ≈ 20 天
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryDeadline.compute(NOW, null, 1_800_000L));
    }

    @Test
    @DisplayName("shipExitTsMs：预测缺失/为空 → null；船驶出 → 返回驶出时刻")
    void shipExitTsMs_fromPrediction() {
        assertNull(DeliveryDeadline.shipExitTsMs(null, straightNorth(), 1000.0, 3000.0));
        assertNull(DeliveryDeadline.shipExitTsMs(List.of(), straightNorth(), 1000.0, 3000.0));
        assertNull(DeliveryDeadline.shipExitTsMs(track(1500.0, 2500.0), straightNorth(),
                1000.0, 3000.0), "时域内不驶出");

        Long exitTs = DeliveryDeadline.shipExitTsMs(track(2000.0, 4000.0), straightNorth(),
                1000.0, 3000.0);
        assertEquals(30_000L, exitTs, "2000→4000 跨 3000，两点中点 → 30 s");
    }

    /** 正北向直线航道：纬度 28.60 → 28.70，s 与纬度线性对应（同 {@code ServiceSegmentGateTest} 夹具）。 */
    private static WaterwayGeometry straightNorth() {
        return new WaterwayGeometry(List.of(
                new WaterwayGeometry.Vertex(115.85, 28.60),
                new WaterwayGeometry.Vertex(115.85, 28.70)));
    }

    private static List<EstimatedPoint> track(double... sValues) {
        List<EstimatedPoint> points = new ArrayList<>(sValues.length);
        for (int i = 0; i < sValues.length; i++) {
            double lat = 28.60 + sValues[i] / ConstantVelocityPredictor.METERS_PER_DEG_LAT;
            points.add(new EstimatedPoint(i * 60_000L, 115.85, lat, 20.0, 15.0));
        }
        return points;
    }
}
