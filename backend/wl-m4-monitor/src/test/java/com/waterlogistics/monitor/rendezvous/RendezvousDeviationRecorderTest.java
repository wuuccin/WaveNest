package com.waterlogistics.monitor.rendezvous;

import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会面偏差回流验证（G 组功能 19，16 号简化版）。
 *
 * <p>验证：B_HandoverConfirmation → 13 字段组装 → MQ 发送 → 内存留痕。
 * 未完成接近（无实际会面点）时不产生样本。
 * MQ 发送器用子类替身覆盖 publish 留痕，不引第三方 mock 框架。</p>
 */
class RendezvousDeviationRecorderTest {

    /** MQ 发送替身：覆盖 publish，记录已发样本，不碰 RabbitTemplate。 */
    private static final class RecordingPublisher extends RendezvousDeviationPublisher {
        final List<RendezvousDeviation> sent = new ArrayList<>();
        RecordingPublisher() {
            super(null);
        }
        @Override
        public void publish(RendezvousDeviation deviation) {
            sent.add(deviation);
        }
    }

    @Test
    void 交接成功_产生偏差样本() {
        RecordingPublisher publisher = new RecordingPublisher();
        RendezvousDeviationRecorder recorder = new RendezvousDeviationRecorder(publisher);

        // 计划会面点：113.20, 30.35；实际会面点：向东偏 ~50m
        B_HandoverConfirmation confirmation = new B_HandoverConfirmation(
                "task-001", "uav-001",
                113.20045, 30.35,  // actual: ~50m east
                113.20, 30.35,     // planned
                1700000000000L,
                1700000005000L,    // handoverDoneT: +5s
                120, 60,
                0.85,
                HandoverResult.SUCCESS
        );

        RendezvousDeviation deviation = recorder.record(
                confirmation, "order-001", "DJI-M350", "nest-001", "calm", "0-5kn");

        assertNotNull(deviation);
        // order_id 是订单实体标识，由调用方传入；confirmation.taskId（task-001）不得顶替
        assertEquals("order-001", deviation.orderId());
        assertEquals("uav-001", deviation.droneId());
        assertEquals("DJI-M350", deviation.model());
        assertEquals("nest-001", deviation.homeNestId());

        // 空间偏差 ≈50m（Haversine 计算）
        assertTrue(deviation.dSpaceM() > 40.0 && deviation.dSpaceM() < 60.0,
                "dSpaceM 应约 50m，实测 " + deviation.dSpaceM());

        // 时间偏差 = 5000ms - 0ms = 5s
        assertEquals(5.0, deviation.dTimeS(), 0.001);

        // 内存留痕 + MQ 发送
        assertEquals(1, recorder.deviationLog().size());
        assertEquals(1, publisher.sent.size(), "应发 1 次 MQ");
        assertEquals(deviation, publisher.sent.get(0));
    }

    @Test
    void 未完成接近_无实际会面点_不产生样本() {
        RecordingPublisher publisher = new RecordingPublisher();
        RendezvousDeviationRecorder recorder = new RendezvousDeviationRecorder(publisher);

        B_HandoverConfirmation confirmation = new B_HandoverConfirmation(
                "task-002", "uav-002",
                null, null,         // 无实际会面点
                113.20, 30.35,
                1700000000000L,
                null, null, null,
                null,
                HandoverResult.FAILED
        );

        RendezvousDeviation deviation = recorder.record(
                confirmation, "order-002", "DJI-M350", "nest-001", null, null);

        assertNull(deviation, "无实际会面点不应产生偏差样本");
        assertEquals(0, recorder.deviationLog().size());
        assertEquals(0, publisher.sent.size(), "无样本不得发 MQ");
    }

    @Test
    void 计划基准原样保留() {
        RecordingPublisher publisher = new RecordingPublisher();
        RendezvousDeviationRecorder recorder = new RendezvousDeviationRecorder(publisher);

        B_HandoverConfirmation confirmation = new B_HandoverConfirmation(
                "task-003", "uav-003",
                113.21, 30.36,
                113.20, 30.35,
                1700000000000L,
                1700000010000L,
                null, null, null,
                HandoverResult.SUCCESS
        );

        RendezvousDeviation deviation = recorder.record(
                confirmation, "order-003", "DJI-M350", "nest-001", null, null);

        assertNotNull(deviation);
        // 计划基准必须原样保留（22 §5：plannedMeetLon/Lat 是 d_space 比对基准）
        assertEquals(113.20, deviation.plannedRendezvous().lon());
        assertEquals(30.35, deviation.plannedRendezvous().lat());
        assertEquals(1700000000000L, deviation.plannedTime());
    }
}
