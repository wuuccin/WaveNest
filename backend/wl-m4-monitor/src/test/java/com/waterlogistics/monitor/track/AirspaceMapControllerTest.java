package com.waterlogistics.monitor.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.waterlogistics.storage.entity.AirspaceRestrictionEntity;
import org.junit.jupiter.api.Test;

class AirspaceMapControllerTest {

    @Test
    void bridgeScope_shouldBecomeMapFeatureWithoutInventedGeometry() {
        AirspaceRestrictionEntity row = row("BRIDGE-NANCHANG",
                "{\"kind\":\"bridge\",\"center\":{\"lon\":115.859,\"lat\":28.663},"
                        + "\"clearanceHeightM\":10.0,\"spanWidthM\":60.0}");

        AirspaceMapController.MapFeature feature = AirspaceMapController.bridgeFeature(row);

        assertEquals("南昌大桥", feature.name());
        assertEquals("bridge", feature.kind());
        assertEquals("115.859", feature.center().lon().toPlainString());
        assertEquals("28.663", feature.center().lat().toPlainString());
        assertEquals("10.0", feature.clearanceHeightM().toPlainString());
        assertEquals("60.0", feature.spanWidthM().toPlainString());
    }

    @Test
    void unknownScope_shouldBeSkippedInsteadOfRenderedAsPlaceholder() {
        assertNull(AirspaceMapController.bridgeFeature(row("OTHER-1", "任意文本")));
    }

    private static AirspaceRestrictionEntity row(String id, String scope) {
        AirspaceRestrictionEntity row = new AirspaceRestrictionEntity();
        row.setRestrictionId(id);
        row.setType("限高");
        row.setScope(scope);
        row.setSource("测试台账");
        row.setStatus("生效");
        return row;
    }
}
