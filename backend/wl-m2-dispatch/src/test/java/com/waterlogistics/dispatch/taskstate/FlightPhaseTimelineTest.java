package com.waterlogistics.dispatch.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlightPhaseTimelineTest {

    private static final long MEET = 1_760_000_000_000L;

    @Test
    @DisplayName("会面时刻倒推起飞，交接与返航按续航时长顺推")
    void schedule_shouldFollowMeetTimeAndDurations() {
        List<FlightPhaseTimeline.Transition> schedule =
                FlightPhaseTimeline.schedule(MEET, 960.0, 12.0, 120);

        assertEquals(4, schedule.size());
        assertEquals(MEET - 80_000L, schedule.get(0).dueMs(), "960 m ÷ 12 m/s＝80 s");
        assertEquals(MEET, schedule.get(1).dueMs());
        assertEquals(MEET + 120_000L, schedule.get(2).dueMs());
        assertEquals(MEET + 200_000L, schedule.get(3).dueMs());

        assertEquals(OrderStatus.DISPATCHED, schedule.get(0).from());
        assertEquals(OrderStatus.OUTBOUND, schedule.get(0).to());
        assertEquals(OrderStatus.HANDOVER, schedule.get(1).to());
        assertEquals(OrderStatus.RETURNING, schedule.get(2).to());
        assertEquals(OrderStatus.FINISHED, schedule.get(3).to());

        assertEquals(List.of("起飞确认", "进入末段交接", "交接完成", "落巢完成"),
                schedule.stream().map(FlightPhaseTimeline.Transition::opType).toList());
        assertTrue(schedule.get(0).dueMs() < schedule.get(3).dueMs());
    }

    @Test
    @DisplayName("审计时刻钳制：到期时刻早于下界时顺延，保证日志时间不倒挂")
    void auditTs_shouldNeverGoBackwards() {
        assertEquals(MEET, FlightPhaseTimeline.auditTs(MEET, MEET - 1_000L), "到期更晚时取到期时刻");
        assertEquals(MEET, FlightPhaseTimeline.auditTs(MEET, 0L), "无下界时取到期时刻");
        assertEquals(MEET + 1L, FlightPhaseTimeline.auditTs(MEET, MEET), "与下界相等也必须前进一格");
        assertEquals(MEET + 1L, FlightPhaseTimeline.auditTs(MEET - 5_000L, MEET),
                "已晚写过的时刻不得被更早的理论到期值覆盖");
    }

    @Test
    @DisplayName("输入非法时拒绝，不生成负时长时间表")
    void schedule_shouldRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> FlightPhaseTimeline.schedule(0L, 960.0, 12.0, 120));
        assertThrows(IllegalArgumentException.class,
                () -> FlightPhaseTimeline.schedule(MEET, -1.0, 12.0, 120));
        assertThrows(IllegalArgumentException.class,
                () -> FlightPhaseTimeline.schedule(MEET, 960.0, 0.0, 120));
        assertThrows(IllegalArgumentException.class,
                () -> FlightPhaseTimeline.schedule(MEET, 960.0, 12.0, -1));
    }
}
