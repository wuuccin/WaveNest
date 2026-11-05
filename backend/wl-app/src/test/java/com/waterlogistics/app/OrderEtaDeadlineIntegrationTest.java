package com.waterlogistics.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.OrderAdmissionService;
import com.waterlogistics.dispatch.order.OrderSubmitRequest;
import com.waterlogistics.dispatch.order.OrderSubmitResponse;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.taskstate.FlightPhaseAdvancer;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 11-05 序号 2 端到端：系统 deadline 与 ETA 落库（真库，事务回滚）。
 *
 * <p>覆盖《11-05 任务清单》§八 硬判据 4 的可测部分：
 * <ul>
 *   <li>下单不填「期望送达时间」→ 正常受理，<b>不再转人工</b>；</li>
 *   <li>{@code order_task.expected_time} 落系统算出的 deadline，{@code eta_ts} 落未派单下界；</li>
 *   <li>一次完整飞行后 {@code eta_ts} ＝ 实际送达时刻（会面时刻 ＋ 交接时长）。</li>
 * </ul>
 *
 * <p>与 {@link SmokeM6M2Test} 同一套夹具口径：关仿真流、自喂船位，避免仿真覆盖快照。
 */
@SpringBootTest(properties = {"wavenest.m2.flight-advance-enabled=false",
        "wavenest.sim.enabled=false"})
@Transactional
class OrderEtaDeadlineIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 与冒烟测试不同的 MMSI，避免同一事务内与既有用例互相干扰。 */
    private static final String MMSI = "223456789";

    @Autowired
    private OrderAdmissionService admissionService;
    @Autowired
    private PredictionService predictionService;
    @Autowired
    private OrderTaskMapper orderTaskMapper;
    @Autowired
    private FlightPhaseAdvancer flightPhaseAdvancer;
    @Autowired
    private SysParamLoader sysParamLoader;

    @Test
    @DisplayName("★ 不填期望送达时间 → 受理；deadline 与 eta_ts 均由系统算出并落库")
    void submitWithoutExpectedTime_shouldComputeDeadlineAndEta() {
        feedShip();
        long before = System.currentTimeMillis();

        OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                MMSI, 5.5, "医疗物资", null, "URGENT"));

        assertThat(resp.accepted()).as("客户端不填期望时间不是缺字段").isTrue();
        assertThat(resp.etaTs()).as("受理响应即带预计送达").isNotNull();

        OrderTaskEntity row = orderTaskMapper.selectById(resp.orderId());
        assertThat(row).isNotNull();
        assertThat(row.getEtaTs()).as("order_task.eta_ts 未派单下界").isNotNull();
        assertThat(row.getExpectedTime()).as("order_task.expected_time 落系统 deadline").isNotNull();

        long deadlineMs = row.getExpectedTime().atZone(ZONE).toInstant().toEpochMilli();
        long etaMs = row.getEtaTs().atZone(ZONE).toInstant().toEpochMilli();
        assertThat(deadlineMs).as("deadline 不早于下单时刻").isGreaterThanOrEqualTo(before);
        assertThat(etaMs).as("预计送达不早于下单时刻").isGreaterThanOrEqualTo(before);
        // 系统 deadline 的上界是「下单 + maxDeliverySec」，客户端没填时不会被客户端值压低
        assertThat(deadlineMs).isLessThanOrEqualTo(before + 1_800_000L + 5_000L);
    }

    @Test
    @DisplayName("★ 一次完整飞行后 eta_ts ＝ 会面时刻 ＋ 交接时长（实际送达）")
    void etaTs_shouldEndAtDeliveredTime() {
        feedShip();
        OrderSubmitResponse resp = admissionService.submit(new OrderSubmitRequest(
                MMSI, 5.5, "医疗物资", null, "URGENT"));
        assertThat(resp.accepted()).isTrue();

        // 把会面时刻回拨到已过时刻，让飞行阶段推进器一次补记完整飞行
        OrderTaskEntity row = orderTaskMapper.selectById(resp.orderId());
        long meetMs = System.currentTimeMillis() - 3_600_000L;
        row.setMeetTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(meetMs), ZONE));
        orderTaskMapper.updateById(row);

        flightPhaseAdvancer.tick();

        OrderTaskEntity finished = orderTaskMapper.selectById(resp.orderId());
        assertThat(finished.getStatus()).isEqualTo(OrderStatus.FINISHED.getValue());

        int handoverSec = sysParamLoader.getInt("tHandoverMinSec", 120);
        // 会面时刻以库内读回的值为准：datetime 列无小数秒，写进去的毫秒会被取整
        long meetInDb = finished.getMeetTime().atZone(ZONE).toInstant().toEpochMilli();
        long etaMs = finished.getEtaTs().atZone(ZONE).toInstant().toEpochMilli();
        assertThat(etaMs)
                .as("末值＝实际送达：库内会面 %d + 交接 %d s", meetInDb, handoverSec)
                .isEqualTo(meetInDb + handoverSec * 1000L);
    }

    /** 喂一条 AIS，让该船有预测快照（候选距离过滤与会面求解都以真实船位为前提）。 */
    private void feedShip() {
        predictionService.refresh(new AisMessage(
                MMSI, "ETA集成测试船", 115.8590, 28.6630, 5.0, 90.0,
                System.currentTimeMillis()));
    }
}
