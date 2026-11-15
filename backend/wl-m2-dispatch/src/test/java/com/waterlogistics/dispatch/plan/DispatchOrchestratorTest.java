package com.waterlogistics.dispatch.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchOrchestratorTest {

    @Test
    void orchestrate_shouldRejectWhenOrderMissingAfterStartDispatch() {
        TaskQueueService queue = new TaskQueueService(null, null, null) {
            @Override
            public void startDispatch(String orderId) {
                // 组批成功，但随后按订单号回读时行已不存在（并发删除或调用方传错单号）
            }
        };
        OrderTaskMapper mapper = (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(), new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DispatchOrchestrator orchestrator = new DispatchOrchestrator(
                queue, null, mapper, null, null, null, null, null, null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> orchestrator.orchestrate("ORD-MISSING"));

        assertTrue(thrown.getMessage().contains("订单不存在"),
                "须点明订单不存在，而不是在取状态时空指针：" + thrown.getMessage());
    }

    /**
     * 错峰成功时「整帧与会面时刻同步时移」（11-14 序号 2-4）：
     * 三段全部计划点按同一 shiftMs 后移，分段顺序与点数不变，除 tMs 外坐标与速度原样保留。
     * 会面时刻的同步回填由编排器按同一 shiftMs 做（见 orchestrate 第 6 步），两者同源。
     */
    @Test
    void shiftFrame_shouldShiftEveryPointAndKeepStructure() {
        PlanTrackFrame frame = new PlanTrackFrame("ORD-1", "ROUTE-1", "C-1",
                PlanTrackFrame.OP_TYPE, List.of(
                segment(PlanTrackFrame.SEGMENT_TRANSFER, 1000L, 2),
                segment(PlanTrackFrame.SEGMENT_OUTBOUND, 5000L, 3),
                segment(PlanTrackFrame.SEGMENT_TERMINAL, 9000L, 2),
                segment(PlanTrackFrame.SEGMENT_RETURN, 12000L, 2)));

        long shiftMs = 3000L;
        PlanTrackFrame shifted = DispatchOrchestrator.shiftFrame(frame, shiftMs);

        assertEquals(frame.orderId(), shifted.orderId());
        assertEquals(frame.routeId(), shifted.routeId());
        assertEquals(frame.comboId(), shifted.comboId());
        assertEquals(frame.opType(), shifted.opType());
        assertEquals(frame.segments().size(), shifted.segments().size(), "分段数不得变化");

        for (int i = 0; i < frame.segments().size(); i++) {
            PlanTrackFrame.Segment before = frame.segments().get(i);
            PlanTrackFrame.Segment after = shifted.segments().get(i);
            assertEquals(before.kind(), after.kind(), "分段顺序不得变化");
            assertEquals(before.points().size(), after.points().size(), "点数不得变化");
            for (int j = 0; j < before.points().size(); j++) {
                PlanTrackFrame.Point p0 = before.points().get(j);
                PlanTrackFrame.Point p1 = after.points().get(j);
                assertEquals(p0.tMs() + shiftMs, p1.tMs(), "每个计划点须同量后移");
                assertEquals(p0.lon(), p1.lon());
                assertEquals(p0.lat(), p1.lat());
                assertEquals(p0.altM(), p1.altM());
                assertEquals(p0.speedMs(), p1.speedMs());
            }
        }
    }

    private static PlanTrackFrame.Segment segment(String kind, long baseTs, int count) {
        return new PlanTrackFrame.Segment(kind, java.util.stream.LongStream.range(0, count)
                .mapToObj(i -> new PlanTrackFrame.Point(115.9 + i * 1e-4, 29.7 + i * 1e-4,
                        60.0, 4.5, baseTs + i * 1000L))
                .toList());
    }
}
