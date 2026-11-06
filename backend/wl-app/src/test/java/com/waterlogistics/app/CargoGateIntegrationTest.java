package com.waterlogistics.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.dispatch.order.CargoReadyService;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.queue.CargoGateWaker;
import com.waterlogistics.dispatch.queue.CargoNestGate;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OccupancySlotEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OccupancySlotMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 11-05 序号 2（张睿哲）货到巢关与队列选择的集成判据（真实 MySQL + RabbitMQ）：
 *
 * <ol>
 *   <li><b>selectNext 取单序</b>（硬判据 5 前半）：同批 EMERGENCY/URGENT/NORMAL
 *       依 应急→加急→常规 出队，同优先级 expected_time 早者在前；</li>
 *   <li><b>货后到链路</b>（硬判据 2）：货未就绪 → startDispatch 停在「已受理待唤醒」
 *       且 wait_reason=WAIT_CARGO 并持有巢时隙锁；cargo-ready 后 waker 放行，
 *       离开等待态且 wait_reason 清空；</li>
 *   <li><b>锁超时链路</b>（硬判据 3 张睿哲侧）：锁龄超过 cargoLockTimeoutSec →
 *       wait_reason=WAIT_RESOURCE 且锁已释放（alert 行由宋冰鑫序号 2 负责）；</li>
 *   <li><b>毒帧死信</b>：坏字节帧进 AIS 队列一次即落 DLQ，不重投。</li>
 * </ol>
 *
 * <p><b>11-06 复核（张睿哲）</b>：把「同批两单的优先级序与紧期限优先」「两个
 * {@code sys_param} 取值与 012 登记一致」两条判据各自固化为独立用例，
 * 队列逻辑本身未改。</p>
 *
 * <p>DB 侧测试事务回滚；MQ 侧毒帧落 DLQ 不清理（死信本就是给人看的）。
 * 自带轮询关掉（cargo-gate-poll-enabled=false），放行/超时都由测试手动 tick 驱动。</p>
 */
@SpringBootTest(properties = {
        "wavenest.m2.flight-advance-enabled=false",
        // 仿真 AIS 流会覆盖手喂船位，关掉后本用例独占喂数
        "wavenest.sim.enabled=false",
        // 自带轮询关闭，避免后台拍与测试断言竞争
        "wavenest.m2.cargo-gate-poll-enabled=false"})
@Transactional
class CargoGateIntegrationTest {

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SHIP = "223456789";
    private static final String NEST = "NEST-CARGO-T1";

    @Autowired
    private TaskQueueService taskQueueService;
    @Autowired
    private CargoGateWaker cargoGateWaker;
    @Autowired
    private CargoReadyService cargoReadyService;
    @Autowired
    private PredictionService predictionService;
    @Autowired
    private OrderTaskMapper orderTaskMapper;
    @Autowired
    private GoodsMapper goodsMapper;
    @Autowired
    private NestMapper nestMapper;
    @Autowired
    private OccupancySlotMapper occupancySlotMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private CargoNestGate cargoNestGate;
    @Autowired
    private SysParamLoader sysParamLoader;

    // ─────────────────────── selectNext 取单序（硬判据 5 前半） ───────────────────────

    @Test
    @DisplayName("selectNext：EMERGENCY 先于 URGENT 先于 NORMAL；同优先级 expected_time 早者先")
    void selectNext_ordersByPriorityThenExpectedTime() {
        LocalDateTime now = LocalDateTime.now();
        String normalEarly = insertWaitingOrder("NORMAL", now.plusHours(1), now.minusMinutes(3));
        String normalLate = insertWaitingOrder("NORMAL", now.plusHours(2), now.minusMinutes(2));
        String urgent = insertWaitingOrder("URGENT", now.plusHours(3), now.minusMinutes(1));
        // 应急单建单最晚、期望最晚，仍应最先被取到
        String emergency = insertWaitingOrder("EMERGENCY", now.plusHours(4), now);

        List<String> picked = taskQueueService.selectNext(500).stream()
                .map(OrderTaskEntity::getOrderId).toList();

        assertThat(picked.indexOf(emergency)).isLessThan(picked.indexOf(urgent));
        assertThat(picked.indexOf(urgent)).isLessThan(picked.indexOf(normalEarly));
        assertThat(picked.indexOf(normalEarly)).isLessThan(picked.indexOf(normalLate));
    }

    // ─────────────── 11-06 张睿哲：同批两单的优先级序与紧期限优先 ───────────────

    @Test
    @DisplayName("同批两单：应急单期限更晚仍先于常规单（优先级压过期限）")
    void selectNext_twoOrders_priorityBeatsDeadline() {
        LocalDateTime now = LocalDateTime.now();
        String normalTight = insertWaitingOrder("NORMAL", now.plusMinutes(30), now.minusMinutes(2));
        String emergencyLoose = insertWaitingOrder("EMERGENCY", now.plusHours(3), now.minusMinutes(1));

        List<String> picked = taskQueueService.selectNext(500).stream()
                .map(OrderTaskEntity::getOrderId).toList();

        assertThat(picked.indexOf(emergencyLoose)).isLessThan(picked.indexOf(normalTight));
    }

    @Test
    @DisplayName("同批两单：同优先级紧期限先取（expected_time 早者先）")
    void selectNext_twoOrders_tighterDeadlineFirst() {
        LocalDateTime now = LocalDateTime.now();
        String loose = insertWaitingOrder("URGENT", now.plusHours(3), now.minusMinutes(2));
        String tight = insertWaitingOrder("URGENT", now.plusMinutes(20), now.minusMinutes(1));

        List<String> picked = taskQueueService.selectNext(500).stream()
                .map(OrderTaskEntity::getOrderId).toList();

        assertThat(picked.indexOf(tight)).isLessThan(picked.indexOf(loose));
    }

    // ─────────────── 11-06 张睿哲：两个参数在 sys_param 的取值与代码缺省一致 ───────────────

    @Test
    @DisplayName("sys_param：cargoLockTimeoutSec=300、delayPenaltyWeight=1.0，与 012 登记一致并被实际读取")
    void cargoGateParams_match012Registration() {
        assertThat(sysParamLoader.getInt(CargoNestGate.LOCK_TIMEOUT_KEY, -1))
                .as("cargoLockTimeoutSec 须与 deploy/sql/012_cargo_gate_params.sql 的 300 一致")
                .isEqualTo(300);
        assertThat(cargoNestGate.lockTimeoutSec())
                .as("门关与轮询实际读取到的锁超时须与库内参数一致")
                .isEqualTo(300);
        assertThat(sysParamLoader.getDouble("delayPenaltyWeight", -1.0))
                .as("delayPenaltyWeight 须与 deploy/sql/012_cargo_gate_params.sql 的 1.0 一致")
                .isEqualTo(1.0);
    }

    // ─────────────────────── 货后到链路（硬判据 2） ───────────────────────

    @Test
    @DisplayName("货未到巢 → 已受理待唤醒+WAIT_CARGO+持锁；cargo-ready 后 waker 放行")
    void cargoGate_lateCargo_waitsThenReleases() {
        predictionService.refresh(new AisMessage(
                SHIP, "货到巢关测试船", 115.8590, 28.6630, 5.0, 90.0,
                System.currentTimeMillis()));
        insertNest();
        String orderId = insertWaitingOrder("NORMAL", LocalDateTime.now().plusHours(2),
                LocalDateTime.now());
        orderTaskMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<OrderTaskEntity>lambdaUpdate()
                .eq(OrderTaskEntity::getOrderId, orderId)
                .set(OrderTaskEntity::getCargoNestId, NEST));
        insertGoods(orderId, "未就绪");

        // 派单前门关：货未就绪 → 转等货
        taskQueueService.startDispatch(orderId);
        OrderTaskEntity waiting = orderTaskMapper.selectById(orderId);
        assertThat(waiting.getStatus()).isEqualTo("已受理待唤醒");
        assertThat(waiting.getWaitReason()).isEqualTo("WAIT_CARGO");
        assertThat(activeSlotOf(orderId)).as("等货期间应持有巢时隙锁").isNotNull();

        // cargo-ready 到达（卢肄文序号 1 端点的 service 入口）→ 轮询放行
        cargoReadyService.markReady(orderId);
        cargoGateWaker.tick();

        OrderTaskEntity released = orderTaskMapper.selectById(orderId);
        assertThat(released.getStatus()).isNotEqualTo("已受理待唤醒");
        assertThat(released.getWaitReason()).as("离开等待态后 wait_reason 必须清空").isNull();
    }

    // ─────────────────────── 锁超时链路（硬判据 3 张睿哲侧） ───────────────────────

    @Test
    @DisplayName("锁超时：锁龄超 cargoLockTimeoutSec → WAIT_RESOURCE 且锁已释放")
    void cargoGate_lockTimeout_marksWaitResource() {
        insertNest();
        String orderId = insertWaitingOrder("NORMAL", LocalDateTime.now().plusHours(2),
                LocalDateTime.now());
        orderTaskMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<OrderTaskEntity>lambdaUpdate()
                .eq(OrderTaskEntity::getOrderId, orderId)
                .set(OrderTaskEntity::getCargoNestId, NEST));
        insertGoods(orderId, "未就绪");

        taskQueueService.startDispatch(orderId);
        assertThat(orderTaskMapper.selectById(orderId).getWaitReason()).isEqualTo("WAIT_CARGO");
        OccupancySlotEntity slot = activeSlotOf(orderId);
        assertThat(slot).isNotNull();

        // 把锁的获得时间拨到超时之前（默认 300s，拨回 1 小时稳稳超时）
        slot.setT1(LocalDateTime.now().minusHours(1));
        occupancySlotMapper.updateById(slot);

        cargoGateWaker.tick();

        OrderTaskEntity timedOut = orderTaskMapper.selectById(orderId);
        assertThat(timedOut.getStatus()).isEqualTo("已受理待唤醒");
        assertThat(timedOut.getWaitReason()).isEqualTo("WAIT_RESOURCE");
        assertThat(activeSlotOf(orderId)).as("锁超时后锁必须释放").isNull();
    }

    // ─────────────────────── 毒帧死信（MQ 装配判据） ───────────────────────

    @Test
    @DisplayName("毒帧：坏字节帧进 AIS 队列一次即落 DLQ，源队列不重投")
    void poisonFrame_landsInDlqOnce() {
        byte[] poison = "{ not-a-valid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 默认交换 + 路由键=队列名，直投 AIS 源队列（不经 wavenest.data 主题）
        rabbitTemplate.send("", "wavenest.m2.data-ais", new Message(poison));

        Message dead = null;
        for (int i = 0; i < 30 && dead == null; i++) {
            dead = rabbitTemplate.receive("wavenest.m2.data-ais.dlq", 1_000L);
        }
        assertThat(dead).as("毒帧应一次性进死信队列 wavenest.m2.data-ais.dlq").isNotNull();
        assertThat(dead.getBody()).isEqualTo(poison);
        // 不重投：源队列不应再有任何消息（毒帧已被拒收并路由走）
        assertThat(rabbitTemplate.receive("wavenest.m2.data-ais", 500L)).isNull();
    }

    // ─────────────────────── helpers ───────────────────────

    private String insertWaitingOrder(String priority, LocalDateTime expected, LocalDateTime created) {
        OrderTaskEntity e = new OrderTaskEntity();
        e.setOrderId("T-CG-" + UUID.randomUUID().toString().substring(0, 8));
        e.setShipMmsi(SHIP);
        e.setShipLon(new BigDecimal("115.8590"));
        e.setShipLat(new BigDecimal("28.6630"));
        e.setGoodsWeight(new BigDecimal("5.5"));
        e.setGoodsType("医疗物资");
        e.setCreateTime(created);
        e.setExpectedTime(expected);
        e.setPriority(priority);
        e.setStatus("待调度");
        orderTaskMapper.insert(e);
        return e.getOrderId();
    }

    private void insertNest() {
        if (nestMapper.selectById(NEST) != null) {
            return;
        }
        NestEntity nest = new NestEntity();
        nest.setNestId(NEST);
        nest.setLon(new BigDecimal("115.8600"));
        nest.setLat(new BigDecimal("28.6640"));
        nest.setCapacity(4);
        nest.setStatus("可用");
        nestMapper.insert(nest);
    }

    private void insertGoods(String orderId, String status) {
        GoodsEntity g = new GoodsEntity();
        g.setGoodsId("G-" + orderId);
        g.setOrderId(orderId);
        g.setWeight(new BigDecimal("5.5"));
        g.setType("医疗物资");
        g.setStatus(status);
        g.setLocation(NEST);
        goodsMapper.insert(g);
    }

    private OccupancySlotEntity activeSlotOf(String orderId) {
        List<OccupancySlotEntity> slots = occupancySlotMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OccupancySlotEntity>lambdaQuery()
                        .eq(OccupancySlotEntity::getOrderId, orderId)
                        .eq(OccupancySlotEntity::getStatus, "有效"));
        return slots.isEmpty() ? null : slots.get(0);
    }
}
