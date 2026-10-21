package com.waterlogistics.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.mq.RendezvousDeviationMessage;
import com.waterlogistics.contract.mq.TaskPlanMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.rest.GateSource;
import com.waterlogistics.contract.rest.GateStatus;
import com.waterlogistics.contract.rest.WaitReason;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.DroneStatus;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.state.TaskStatus;
import com.waterlogistics.contract.ws.WsFrameType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 契约"照抄校验"：把《22-接口契约》v1.2 与《23-状态机定义》v0.6 的字面值钉死在测试里。
 *
 * <p>任何一处被私自增删，这里立刻红。改动枚举必须走 PR 由收口方裁决。
 */
class ContractDoctrineTest {

    /**
     * 《22》§1.2 的 12 个主题：raw.* 五条、data.* 五条、alert.event、task.plan；
     * ＋《86》附 G-01 新增的 {@code rendezvous.deviation}（功能 19 数据回流，非告警通道）＝ 13 个。
     */
    @Test
    void topics_shouldBeThirteenAndAuthoritative() {
        assertEquals(13, TopicName.TOPICS.size(), "主题数量必须为 12＋1（rendezvous.deviation）");
        assertEquals(List.of(
                        "raw.ais", "raw.uav.telemetry", "raw.weather", "raw.hydro", "raw.notice",
                        "data.ais", "data.uav.telemetry", "data.weather", "data.hydro", "data.notice",
                        "alert.event", "task.plan", "rendezvous.deviation"),
                TopicName.TOPICS);
    }

    /**
     * 《23》§3 十二态，顺序同 Rodriguez order_task.status 列注释。
     */
    @Test
    void orderStatus_shouldFollow23Section3() {
        assertEquals(List.of("待受理", "已受理待唤醒", "待调度", "调度中", "已派发", "去程中",
                        "末段交接中", "返航中", "已完成", "已拒绝", "失败", "异常终止"),
                values(OrderStatus.values()));
        assertEquals(3, Arrays.stream(OrderStatus.values()).filter(OrderStatus::isTerminal).count(),
                "终止态必须是《23》§3 的三个：已拒绝／失败／异常终止");
    }

    /** 《23》§4 八态 */
    @Test
    void taskStatus_shouldFollow23Section4() {
        assertEquals(List.of("已派发", "去程中", "末段交接中", "返航中", "已完成",
                        "迫停中", "迫降待回收", "异常终止"),
                values(TaskStatus.values()));
    }

    /** 《23》§6 六态 */
    @Test
    void goodsStatus_shouldFollow23Section6() {
        assertEquals(List.of("未就绪", "已就绪", "已装机", "已交付", "已回收", "灭失"),
                values(GoodsStatus.values()));
    }

    /** 《23》§7 九态，且只有"空闲"可入候选 */
    @Test
    void droneStatus_shouldFollow23Section7() {
        assertEquals(List.of("空闲", "已预占", "执行任务中", "返航中", "迫停", "迫降", "故障", "维护", "失联"),
                values(DroneStatus.values()));
        assertEquals(1, Arrays.stream(DroneStatus.values()).filter(DroneStatus::isCandidate).count());
        assertTrue(DroneStatus.IDLE.isCandidate());
    }

    /** 《22》§3.1 等待原因三值（由算法层四类合并而来） */
    @Test
    void waitReason_shouldBeThreeValues() {
        assertEquals(List.of("WAIT_SHIP", "WAIT_CARGO", "GATE_OR_DATA"),
                codes(WaitReason.values()));
    }

    /** 《22》§3.2 闸门状态与来源 */
    @Test
    void gate_shouldFollow22Section32() {
        assertEquals(List.of("正常", "熔断中"), values(GateStatus.values()));
        assertEquals(List.of("人工冻结", "自动熔断"), values(GateSource.values()));
    }

    /** 《22》§2 告警类型五类、级别三级（以《37》§5.12 alert 表为准） */
    @Test
    void alert_shouldFollow37Section512() {
        assertEquals(List.of("失联", "低电量", "偏航", "气象超限", "链路异常"),
                values(AlertType.values()));
        assertEquals(List.of("提示", "警告", "严重"), values(AlertLevel.values()));
    }

    /** 《22》§4 五类 WS 帧 */
    @Test
    void wsFrameTypes_shouldBeFive() {
        assertEquals(List.of("ship_traj", "drone_state", "rendezvous", "alarm", "order_state"),
                Arrays.stream(WsFrameType.values()).map(WsFrameType::getType).toList());
    }

    /**
     * 《22》§2.1 投影纪律：{@code task.plan} 只认子集——
     * 被裁字段一律不得混入（须回算法层取、不得反推），六项内容与标识必须在位。
     */
    @Test
    void taskPlan_shouldCarryOnlyProjectedSubset() {
        List<String> components = Arrays.stream(TaskPlanMessage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        for (String forbidden : List.of("tauFine", "energyExact", "pathRisk", "tauCoarse",
                "snapshotVersion", "landingPointsAlong", "avoidZones",
                "infeasibleReason", "tauCoarseUsed", "noSolutionReason")) {
            assertFalse(components.contains(forbidden),
                    "《22》§2.1：被裁字段 " + forbidden + " 不得进 task.plan，须回算法层取");
        }

        for (String required : List.of("taskId", "orderId", "droneId", "homeNestId",
                "meetLon", "meetLat", "meetTMin", "meetTMax",
                "takeoffT", "takeoffFeasible", "routeVersion", "waypoints", "shipTrack")) {
            assertTrue(components.contains(required),
                    "《22》§1.2：task.plan 缺字段 " + required);
        }
        assertEquals(TopicName.TASK_PLAN, TaskPlanMessage.TOPIC);
    }

    /**
     * 《86》附 G-01「会面偏差样本对象级字段表」：全表 13 项，逐项照抄，一个不多一个不少。
     * 字段被私自增删、改名或换序，这里立刻红。
     */
    @Test
    void rendezvousDeviation_shouldFollowG01FieldTable() {
        List<String> components = Arrays.stream(RendezvousDeviationMessage.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(13, components.size(),
                "《86》附 G-01 会面偏差样本字段表恰 13 项，字段不得增删");
        assertEquals(List.of("sampleId", "droneId", "orderId",
                        "plannedRendezvous", "actualRendezvous", "plannedTime", "actualTime",
                        "dSpaceM", "dTimeS", "model", "homeNestId",
                        "windCondition", "shipSpeedRange"),
                components, "须逐项照 G-01 全表，顺序即表格顺序");

        assertEquals(TopicName.RENDEZVOUS_DEVIATION, RendezvousDeviationMessage.TOPIC);
        assertFalse(TopicName.TOPICS.contains("alert.deviation"),
                "《86》§5：偏差样本走数据回流行、不走告警通道，不得并入 alert.*");
    }

    private static List<String> values(Enum<?>[] enums) {
        return Arrays.stream(enums).map(ContractDoctrineTest::chineseValue).toList();
    }

    private static List<String> codes(Enum<?>[] enums) {
        return Arrays.stream(enums).map(e -> String.valueOf(e)).toList();
    }

    private static String chineseValue(Enum<?> e) {
        if (e instanceof OrderStatus status) {
            return status.getValue();
        }
        if (e instanceof TaskStatus status) {
            return status.getValue();
        }
        if (e instanceof GoodsStatus status) {
            return status.getValue();
        }
        if (e instanceof DroneStatus status) {
            return status.getValue();
        }
        if (e instanceof AlertLevel level) {
            return level.getValue();
        }
        if (e instanceof AlertType type) {
            return type.getValue();
        }
        if (e instanceof GateStatus status) {
            return status.getValue();
        }
        if (e instanceof GateSource source) {
            return source.getValue();
        }
        return String.valueOf(e);
    }
}
