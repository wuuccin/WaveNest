package com.waterlogistics.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 规划航迹帧 → plan_track 实体映射与行数核对（接口约定 §2.2）。 */
class PlanTrackWriterTest {

    private static final long T0 = 1_790_000_000_000L;

    @Test
    void write_shouldMapEveryPointAndVerifyRowCount() {
        List<List<PlanTrackEntity>> captured = new ArrayList<>();
        PlanTrackWriter writer = new PlanTrackWriter(mapper(captured, true));

        int rows = writer.write(frame());
        assertEquals(4, rows);
        List<PlanTrackEntity> points = captured.get(0);
        assertEquals(4, points.size());

        PlanTrackEntity first = points.get(0);
        assertEquals("ORD-1", first.getOrderId());
        assertEquals("RT-1", first.getRouteId());
        assertEquals("CB-1", first.getComboId());
        assertEquals(PlanTrackFrame.SEGMENT_OUTBOUND, first.getSegmentKind());
        assertEquals(1, first.getSeq());
        assertEquals(115.86, first.getLon().doubleValue(), 1e-9);
        assertEquals(28.66, first.getLat().doubleValue(), 1e-9);
        assertEquals(60.0, first.getAltM().doubleValue(), 1e-9);
        assertEquals(12.0, first.getSpeedMs().doubleValue(), 1e-9);
        assertEquals(T0, first.getTMs());

        assertEquals(1, points.get(2).getSeq(), "段内 seq 从 1 重新计数");
        assertEquals(PlanTrackFrame.SEGMENT_RETURN, points.get(2).getSegmentKind());
        assertEquals(T0 + 3_000L, points.get(3).getTMs());
    }

    @Test
    void write_shouldRollbackWhenRowCountDiffers() {
        PlanTrackWriter writer = new PlanTrackWriter(mapper(new ArrayList<>(), false));
        assertThrows(IllegalStateException.class, () -> writer.write(frame()));
    }

    @Test
    void write_shouldRejectEmptyFrame() {
        PlanTrackWriter writer = new PlanTrackWriter(mapper(new ArrayList<>(), true));
        PlanTrackFrame empty = new PlanTrackFrame("ORD-1", "RT-1", "CB-1",
                PlanTrackFrame.OP_TYPE, List.of());
        assertThrows(IllegalStateException.class, () -> writer.write(empty));
    }

    private static PlanTrackFrame frame() {
        return new PlanTrackFrame("ORD-1", "RT-1", "CB-1", PlanTrackFrame.OP_TYPE,
                List.of(
                        new PlanTrackFrame.Segment(PlanTrackFrame.SEGMENT_OUTBOUND,
                                List.of(point(115.86, T0), point(115.861, T0 + 1_000L))),
                        new PlanTrackFrame.Segment(PlanTrackFrame.SEGMENT_RETURN,
                                List.of(point(115.862, T0 + 2_000L), point(115.863, T0 + 3_000L)))));
    }

    private static PlanTrackFrame.Point point(double lon, long tMs) {
        return new PlanTrackFrame.Point(lon, 28.66, 60.0, 12.0, tMs);
    }

    private static PlanTrackMapper mapper(List<List<PlanTrackEntity>> captured, boolean exactRows) {
        return (PlanTrackMapper) Proxy.newProxyInstance(
                PlanTrackMapper.class.getClassLoader(), new Class<?>[]{PlanTrackMapper.class},
                (proxy, method, args) -> {
                    if ("insertBatch".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        List<PlanTrackEntity> points = (List<PlanTrackEntity>) args[0];
                        captured.add(points);
                        return exactRows ? points.size() : points.size() - 1;
                    }
                    return null;
                });
    }
}
