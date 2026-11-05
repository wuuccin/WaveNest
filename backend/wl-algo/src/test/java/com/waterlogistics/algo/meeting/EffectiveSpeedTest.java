package com.waterlogistics.algo.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waterlogistics.algo.meeting.model.Wind;
import org.junit.jupiter.api.Test;

/**
 * 有效速度的风向约定——钉死「来向」语义：风向与航向同向为逆风、反向为顺风。
 */
class EffectiveSpeedTest {

    /** 风向＝航向：纯逆风，地速＝巡航−风速。 */
    @Test
    void alongBearing_shouldSlowDownWhenWindFromBearing() {
        assertEquals(12.0, EffectiveSpeed.alongBearing(15.0, new Wind(3.0, 90.0, 0.0), 90.0), 1e-9,
                "来向＝航向为逆风");
    }

    /** 风向＝航向＋180°：纯顺风，地速＝巡航＋风速。 */
    @Test
    void alongBearing_shouldSpeedUpWhenWindFromOpposite() {
        assertEquals(18.0, EffectiveSpeed.alongBearing(15.0, new Wind(3.0, 270.0, 0.0), 90.0), 1e-9,
                "来向＝航向反侧为顺风");
    }

    /** 未归一化方位（450°）与归一化方位（90°）结果一致。 */
    @Test
    void alongBearing_shouldNormalizeBearing() {
        double normalized = EffectiveSpeed.alongBearing(15.0, new Wind(3.0, 90.0, 0.0), 90.0);
        assertEquals(normalized, EffectiveSpeed.alongBearing(15.0, new Wind(3.0, 90.0, 0.0), 450.0), 1e-12);
    }

    /** 入参非法：空风况、非正巡航速度、非有限方位一律拒绝。 */
    @Test
    void alongBearing_shouldRejectInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> EffectiveSpeed.alongBearing(15.0, null, 90.0));
        assertThrows(IllegalArgumentException.class,
                () -> EffectiveSpeed.alongBearing(0.0, new Wind(0.0, 0.0, 0.0), 90.0));
        assertThrows(IllegalArgumentException.class,
                () -> EffectiveSpeed.alongBearing(15.0, new Wind(0.0, 0.0, 0.0), Double.NaN));
    }
}
