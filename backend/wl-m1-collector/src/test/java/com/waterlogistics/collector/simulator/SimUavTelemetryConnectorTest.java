package com.waterlogistics.collector.simulator;

import com.waterlogistics.collector.support.FieldInjector;
import com.waterlogistics.collector.support.RecordingPublisher;
import com.waterlogistics.contract.mq.UavTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无人机遥测仿真连接器单测：机数、电量递减与下限、圆周巡航几何，
 * 以及 11-16 任务 3-8 的注入式手段（偏航渐进位移、低电量一步到位）。
 *
 * <p>注入类的断言一律用「对照组」写法：同一时刻跑两台仿真器，一台注入、一台不注入，
 * 逐拍比对同一架机的坐标。巡航是圆周运动，单看「离机巢多远」会随相位起伏，
 * 量不出注入到底推了多少米。
 */
class SimUavTelemetryConnectorTest {

    private RecordingPublisher publisher;
    private SimUavTelemetryConnector sim;
    /** 对照组用（{@link #referenceConnector()} 建，与 sim 同步起跑、不注入） */
    private RecordingPublisher referencePublisher;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        sim = new SimUavTelemetryConnector(publisher);
        FieldInjector.applySimDefaults(sim);
        sim.start();
    }

    @Test
    @DisplayName("每节拍按配置机数发遥测，编号从 UAV-01 起")
    void publishesConfiguredNumberOfDrones() {
        sim.pollOnce(1_000L);

        List<UavTelemetry> sent = publisher.telemetry();
        assertEquals(4, sent.size(), "机队规模＝四架（T1-5 兵配）");
        assertEquals("UAV-01", sent.get(0).uavId());
        assertEquals("UAV-02", sent.get(1).uavId());
        assertEquals("UAV-03", sent.get(2).uavId());
        assertEquals("UAV-04", sent.get(3).uavId());
    }

    @Test
    @DisplayName("电量逐节拍递减，且永不低于 5% 下限")
    void batteryDrainsWithFloor() {
        for (int i = 0; i < 100; i++) {
            sim.pollOnce(i * 1_000L);
        }
        List<UavTelemetry> sent = publisher.telemetry();
        UavTelemetry first = sent.get(0);
        UavTelemetry last = sent.get(sent.size() - 4);   // 倒数第 4 条也是 UAV-01（本轮共 4 架）

        assertEquals(100.0, first.battery(), 1e-9, "首帧满电");
        assertTrue(last.battery() < first.battery(), "电量应随时间消耗");
        assertTrue(last.battery() >= 5.0, "电量不得跌破 5% 下限");
    }

    @Test
    @DisplayName("圆周巡航：到所属机巢的距离约等于配置半径")
    void orbitsAtConfiguredRadius() {
        sim.pollOnce(1_000L);
        List<UavTelemetry> sent = publisher.telemetry();

        double south = degDistance(sent.get(0), 115.8480000, 28.6420000);
        double north = degDistance(sent.get(2), 115.8670000, 28.6980000);
        assertEquals(0.00025, south, 1e-6, "UAV-01 应绕南巢半径 0.00025° 巡航");
        assertEquals(0.00025, north, 1e-6, "UAV-03 应绕北巢半径 0.00025° 巡航");
    }

    @Test
    @DisplayName("停巢巡航：四架机始终在各自机巢 50 m 内，不越过偏航预警阈值")
    void parkedFleetStaysWithinDeviationThreshold() {
        for (int i = 0; i < 60; i++) {
            sim.pollOnce(i * 1_000L);
        }

        for (UavTelemetry m : publisher.telemetry()) {
            boolean north = m.uavId().compareTo("UAV-02") > 0;
            double nestLon = north ? 115.8670000 : 115.8480000;
            double nestLat = north ? 28.6980000 : 28.6420000;
            double dx = (m.lon() - nestLon) * 111_320.0 * Math.cos(Math.toRadians(nestLat));
            double dy = (m.lat() - nestLat) * 111_320.0;
            assertTrue(Math.hypot(dx, dy) < 50.0,
                    m.uavId() + " 距机巢应小于偏航预警阈值 50 m，实际 " + Math.hypot(dx, dy));
        }
    }

    @Test
    @DisplayName("契约字段：高度分层、速度与航向在合理区间")
    void carriesSaneContractFields() {
        sim.pollOnce(1_000L);
        List<UavTelemetry> sent = publisher.telemetry();

        assertEquals(80.0, sent.get(0).altM(), 1e-9, "第 1 架 80m");
        assertEquals(90.0, sent.get(1).altM(), 1e-9, "第 2 架 90m（分层防混）");
        for (UavTelemetry m : sent) {
            assertEquals(12.0, m.speedMs(), 1e-9);
            assertTrue(m.heading() >= 0.0 && m.heading() < 360.0, "航向应在 [0,360)");
        }
    }

    @Test
    @DisplayName("未显式 start 时首次 pollOnce 自动初始化（容错）")
    void lazyInitialisesOnFirstPoll() {
        RecordingPublisher fresh = new RecordingPublisher();
        SimUavTelemetryConnector connector = new SimUavTelemetryConnector(fresh);
        FieldInjector.applySimDefaults(connector);

        connector.pollOnce(1_000L);
        assertEquals(4, fresh.telemetry().size(), "懒初始化后应正常产出");
    }

    // ------------------------------------------------------------------
    // 注入式手段（11-16 任务 3-8）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("偏航注入按拍渐进：首拍约 12 m，六拍追平 60 m，且每拍位移不越过清洗上限")
    void offtrackRampsGraduallyAndCrossesThreshold() {
        SimUavTelemetryConnector reference = referenceConnector();
        sim.injectOfftrack("UAV-01", 60.0, 90.0);

        sim.pollOnce(1_000L);
        reference.pollOnce(1_000L);
        double firstStep = eastOffsetMeters(lastTelemetryOf("UAV-01"), lastOf(referencePublisher, "UAV-01"));
        assertTrue(firstStep > 10.0 && firstStep < 13.5,
                "首拍应只走约 12 m（speed-ms × 1 s），实测 " + firstStep + "——" +
                        "瞬移会被 M4 清洗判 POSITION_JUMP 后丢弃");

        for (int i = 1; i <= 6; i++) {
            sim.pollOnce(1_000L + i * 1_000L);
            reference.pollOnce(1_000L + i * 1_000L);
        }
        double ramped = eastOffsetMeters(lastTelemetryOf("UAV-01"), lastOf(referencePublisher, "UAV-01"));
        assertEquals(60.0, ramped, 0.5, "六拍后应追平 60 m，实测 " + ramped);

        // 每拍位移反算速度必须留在清洗上限（默认 50 m/s）以内
        List<UavTelemetry> all = publisher.telemetry().stream()
                .filter(m -> m.uavId().equals("UAV-01"))
                .toList();
        for (int i = 1; i < all.size(); i++) {
            double step = metersBetween(all.get(i - 1), all.get(i));
            assertTrue(step < 50.0, "第 " + i + " 拍位移 " + step + " m/拍，超过清洗上限会被判 POSITION_JUMP");
        }
    }

    @Test
    @DisplayName("偏航注入只作用于指定机，其他机与对照组逐拍一致")
    void offtrackAffectsOnlyTargetDrone() {
        SimUavTelemetryConnector reference = referenceConnector();
        sim.injectOfftrack("UAV-01", 60.0, 90.0);
        for (int i = 0; i < 8; i++) {
            sim.pollOnce(i * 1_000L);
            reference.pollOnce(i * 1_000L);
        }

        assertTrue(eastOffsetMeters(lastTelemetryOf("UAV-01"), lastOf(referencePublisher, "UAV-01")) > 55.0,
                "UAV-01 应被推开 60 m");
        // 噪声虽设为 1e-9°（≈0.1 mm），但坐标经 round6() 取 6 位小数，
        // 末位舍入抖动 ±1e-6°≈0.11132 m，两台独立仿真的差最大即一个舍入阶；容差 0.2 m 覆盖该阶。
        assertEquals(0.0, metersBetween(lastTelemetryOf("UAV-02"), lastOf(referencePublisher, "UAV-02")), 0.2,
                "UAV-02 不应被带偏");
        assertEquals(0.0, metersBetween(lastTelemetryOf("UAV-04"), lastOf(referencePublisher, "UAV-04")), 0.2,
                "UAV-04 不应被带偏");
    }

    @Test
    @DisplayName("偏航注入方向归一到 [0,360)，注入瞬间已施加位移为 0")
    void offtrackNormalizesBearingAndReportsTarget() {
        SimUavTelemetryConnector.Injection state = sim.injectOfftrack("UAV-03", 40.0, 450.0);

        assertEquals(40.0, state.targetDeviationM(), 1e-9);
        assertEquals(90.0, state.bearingDeg(), 1e-9, "450° 应归一到 90°");
        assertEquals(0.0, state.currentDeviationM(), 1e-9, "注入瞬间尚未施加位移");
        assertEquals(100.0, state.batteryPercent(), 1e-9);
    }

    @Test
    @DisplayName("低电量注入一步到位，此后仍按拍递减")
    void batteryInjectionAppliesImmediatelyAndKeepsDraining() {
        sim.injectBattery("UAV-02", 15.0);

        // 首拍：15 - 0.02 = 14.98，按 0.1 精度上报仍是 15.0
        sim.pollOnce(1_000L);
        assertEquals(15.0, lastTelemetryOf("UAV-02").battery(), 0.1, "注入后首帧即应落在 15% 附近");

        for (int i = 1; i <= 10; i++) {
            sim.pollOnce(1_000L + i * 1_000L);
        }
        double afterTen = lastTelemetryOf("UAV-02").battery();
        assertEquals(14.8, afterTen, 0.1, "电量应继续按每拍 0.02% 递减");
    }

    @Test
    @DisplayName("复位：偏航位移渐进退回原点、电量回满")
    void clearingInjectionRampsBackAndRefillsBattery() {
        SimUavTelemetryConnector reference = referenceConnector();
        sim.injectOfftrack("UAV-01", 60.0, 90.0);
        sim.injectBattery("UAV-01", 12.0);
        for (int i = 0; i < 8; i++) {
            sim.pollOnce(i * 1_000L);
            reference.pollOnce(i * 1_000L);
        }
        assertTrue(eastOffsetMeters(lastTelemetryOf("UAV-01"), lastOf(referencePublisher, "UAV-01")) > 55.0,
                "复位前应已推开");

        sim.clearInjection("UAV-01");
        for (int i = 8; i < 20; i++) {
            sim.pollOnce(i * 1_000L);
            reference.pollOnce(i * 1_000L);
        }

        assertEquals(0.0, eastOffsetMeters(lastTelemetryOf("UAV-01"), lastOf(referencePublisher, "UAV-01")), 0.5,
                "复位后位移应退回原点（不是瞬间归零，避免反向跳变被清洗）");
        assertTrue(lastTelemetryOf("UAV-01").battery() > 99.0, "复位后电量应回满");
    }

    @Test
    @DisplayName("非法入参一律拒绝：未知机号、格式错、位移越界、电量越界")
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> sim.injectOfftrack("UAV-09", 60.0, 90.0), "超出机数");
        assertThrows(IllegalArgumentException.class, () -> sim.injectOfftrack("uav-1", 60.0, 90.0), "格式不对");
        assertThrows(IllegalArgumentException.class, () -> sim.injectOfftrack("UAV-01", 0.0, 90.0), "位移须大于 0");
        assertThrows(IllegalArgumentException.class, () -> sim.injectOfftrack("UAV-01", 6000.0, 90.0), "位移上限 5000 m");
        assertThrows(IllegalArgumentException.class, () -> sim.injectBattery("UAV-01", 120.0), "电量须 ≤100");
        assertThrows(IllegalArgumentException.class, () -> sim.injectBattery("UAV-01", -1.0), "电量须 ≥0");
        assertThrows(IllegalArgumentException.class, () -> sim.clearInjection("UAV-42"), "复位也认机号");
    }

    @Test
    @DisplayName("注入状态查询覆盖全部机，未注入的机偏航未生效")
    void injectionsSnapshotCoversFleet() {
        sim.injectBattery("UAV-04", 18.0);

        List<SimUavTelemetryConnector.Injection> all = sim.injections();
        assertEquals(4, all.size());
        assertEquals("UAV-01", all.get(0).uavId());
        assertEquals(0.0, all.get(0).targetDeviationM(), 1e-9);
        assertEquals(18.0, all.get(3).batteryPercent(), 1e-9);
        assertTrue(all.get(0).targetDeviationM() == 0.0, "未注入的机不应显示偏航生效");
    }

    /** 与 {@link #sim} 同步起跑、不注入的对照组：两台的相位与噪声分布一致，可直接逐拍比对。 */
    private SimUavTelemetryConnector referenceConnector() {
        referencePublisher = new RecordingPublisher();
        SimUavTelemetryConnector reference = new SimUavTelemetryConnector(referencePublisher);
        FieldInjector.applySimDefaults(reference);
        reference.start();
        return reference;
    }

    /** 遥测点到给定坐标的十进制度平面距离，仅用于断言巡航半径。 */
    private static double degDistance(UavTelemetry m, double lon, double lat) {
        return Math.hypot(m.lon() - lon, m.lat() - lat);
    }

    /** 注入机相对对照组在正东方向多走的米数（注入方向 90° 时即位移量）。 */
    private static double eastOffsetMeters(UavTelemetry injected, UavTelemetry reference) {
        return (injected.lon() - reference.lon()) * 111_320.0 * Math.cos(Math.toRadians(reference.lat()));
    }

    /** 两条遥测之间的平面距离（米）。 */
    private static double metersBetween(UavTelemetry a, UavTelemetry b) {
        double dx = (b.lon() - a.lon()) * 111_320.0 * Math.cos(Math.toRadians(a.lat()));
        double dy = (b.lat() - a.lat()) * 111_320.0;
        return Math.hypot(dx, dy);
    }

    /** 取某机最后一条遥测（发布器按拍扁平存放，4 架机一循环）。 */
    private UavTelemetry lastTelemetryOf(String uavId) {
        return lastOf(publisher, uavId);
    }

    private UavTelemetry lastOf(RecordingPublisher source, String uavId) {
        List<UavTelemetry> all = source.telemetry();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).uavId().equals(uavId)) {
                return all.get(i);
            }
        }
        throw new AssertionError("没有 " + uavId + " 的遥测");
    }
}
