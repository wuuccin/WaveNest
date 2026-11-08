package com.waterlogistics.algo.cargo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选巢器单元测试——钉住「选哪个巢」与「为什么选不出」两条判定。
 *
 * <p>为什么单独一个类：选巢结果会落库并决定空机转场段与起飞时刻，一旦选错，
 * 订单会在错误的位置等货，现场只能从订单轨迹上的货巢编号看出问题。
 * 因此过滤链每一档、排序的确定性都要单独钉住。
 */
class CargoNestSelectorTest {

    private final CargoNestSelector selector = new CargoNestSelector();

    private static final double SHIP_LON = 115.8611;
    private static final double SHIP_LAT = 28.6660;
    private static final double PAYLOAD_KG = 2.0;
    private static final double MAX_DISTANCE_KM = 5.0;
    private static final double MIN_BATTERY_PCT = 30.0;

    /** 离船约 2.96 公里的巢。 */
    private static CargoNestInput.NestView nearNest() {
        return new CargoNestInput.NestView("NEST-NEAR", 115.848, 28.642, List.of("M30T"));
    }

    /** 离船约 3.4 公里的巢。 */
    private static CargoNestInput.NestView farNest() {
        return new CargoNestInput.NestView("NEST-FAR", 115.867, 28.698, List.of("M30T"));
    }

    /** 停在近巢上的空闲机。 */
    private static CargoNestInput.DroneView idleDrone() {
        return new CargoNestInput.DroneView("UAV-01", "NEST-NEAR", "M30T", "空闲", 90.0, 6.0);
    }

    private static CargoNestInput input(List<CargoNestInput.NestView> nests,
            List<CargoNestInput.DroneView> drones) {
        return new CargoNestInput(SHIP_LON, SHIP_LAT, PAYLOAD_KG, nests, drones,
                MAX_DISTANCE_KM, MIN_BATTERY_PCT);
    }

    // ───────────── 命中 ─────────────

    @Test
    @DisplayName("选巢：两巢都有可用机时选离船更近的那个")
    void select_choosesNearestNestWithUsableDrone() {
        CargoNestChoice choice = selector.select(input(
                List.of(nearNest(), farNest()), List.of(idleDrone())));

        assertTrue(choice.hit());
        assertEquals("NEST-NEAR", choice.nestId());
        assertEquals(CargoNestChoice.Reason.HIT, choice.reason());
        assertTrue(choice.distanceKm() > 0.0, "命中时必须给出巢到船位的距离");
    }

    @Test
    @DisplayName("选巢：更近的巢停不下机时改选更远的巢")
    void select_skipsNearestNestWithoutCompatibleDrone() {
        CargoNestInput.NestView noSlot = new CargoNestInput.NestView(
                "NEST-NEAR", 115.848, 28.642, List.of("M30T"), 0);

        CargoNestChoice choice = selector.select(input(
                List.of(noSlot, farNest()), List.of(idleDrone())));

        assertTrue(choice.hit());
        assertEquals("NEST-FAR", choice.nestId());
    }

    // ───────────── 过滤链各档 ─────────────

    @Test
    @DisplayName("选巢：巢坐标缺失不参与选巢")
    void select_skipsNestWithoutCoordinates() {
        CargoNestInput.NestView noCoord = new CargoNestInput.NestView(
                "NEST-BROKEN", Double.NaN, Double.NaN, List.of("M30T"));
        CargoNestInput.DroneView drone = new CargoNestInput.DroneView(
                "UAV-01", "NEST-FAR", "M30T", "空闲", 90.0, 6.0);

        CargoNestChoice choice = selector.select(input(
                List.of(noCoord, farNest()), List.of(drone)));

        assertTrue(choice.hit());
        assertEquals("NEST-FAR", choice.nestId(), "坐标缺失的巢必须被跳过");
    }

    @Test
    @DisplayName("选巢：巢到船位超出距离半径不参与选巢")
    void select_skipsNestBeyondMaxDistance() {
        CargoNestInput.NestView tooFar = new CargoNestInput.NestView(
                "NEST-TOO-FAR", 116.5, 29.5, List.of("M30T"));

        CargoNestChoice choice = selector.select(input(
                List.of(tooFar, nearNest()), List.of(idleDrone())));

        assertTrue(choice.hit());
        assertEquals("NEST-NEAR", choice.nestId());
    }

    @Test
    @DisplayName("选巢：机型不被巢允许的机不算可用机")
    void select_skipsNestWhenDroneTypeNotAllowed() {
        CargoNestInput.NestView otherType = new CargoNestInput.NestView(
                "NEST-NEAR", 115.848, 28.642, List.of("M60L"));

        CargoNestChoice choice = selector.select(input(
                List.of(otherType, farNest()), List.of(idleDrone())));

        assertTrue(choice.hit());
        assertEquals("NEST-FAR", choice.nestId(), "机型不匹配的巢应被跳过");
    }

    @Test
    @DisplayName("选巢：载重不够的机不算可用机")
    void select_skipsNestWhenDronePayloadInsufficient() {
        CargoNestInput.DroneView lightDrone = new CargoNestInput.DroneView(
                "UAV-01", "NEST-NEAR", "M30T", "空闲", 90.0, 1.0);

        CargoNestChoice choice = selector.select(input(
                List.of(nearNest(), farNest()), List.of(lightDrone)));

        assertFalse(choice.hit(), "没有载重够的机，两巢都不可用");
        assertEquals(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE, choice.reason());
    }

    @Test
    @DisplayName("选巢：状态非空闲的机不算可用机")
    void select_skipsNestWhenNoIdleDrone() {
        CargoNestInput.DroneView busy = new CargoNestInput.DroneView(
                "UAV-01", "NEST-NEAR", "M30T", "充电中", 90.0, 6.0);

        CargoNestChoice choice = selector.select(input(
                List.of(nearNest(), farNest()), List.of(busy)));

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE, choice.reason());
    }

    @Test
    @DisplayName("选巢：电量低于下限的机不算可用机")
    void select_skipsNestWhenDroneBatteryLow() {
        CargoNestInput.DroneView lowBattery = new CargoNestInput.DroneView(
                "UAV-01", "NEST-NEAR", "M30T", "空闲", 10.0, 6.0);

        CargoNestChoice choice = selector.select(input(
                List.of(nearNest(), farNest()), List.of(lowBattery)));

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE, choice.reason());
    }

    @Test
    @DisplayName("选巢：归属巢坐标查不到的机不算可用机，避免选出产不出组合的货巢")
    void select_skipsNestWhenDroneHomeNestUnknown() {
        // 这台机停在台账里查不到的巢上：候选生成算空机转场距离要用归属巢坐标，
        // 查不到就产不出组合。若选巢仍把它算作可用机，就会选出一个必然无候选的货巢，
        // 订单从下单那一刻起就卡在调度中，现场只看到一句「无可用候选组合」。
        CargoNestInput.DroneView orphan = new CargoNestInput.DroneView(
                "UAV-01", "NEST-GONE", "M30T", "空闲", 90.0, 6.0);

        CargoNestChoice choice = selector.select(input(
                List.of(nearNest(), farNest()), List.of(orphan)));

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE, choice.reason());
    }

    @Test
    @DisplayName("选巢：机归属巢不在本轮可用巢台账里时同样不算可用机")
    void select_skipsNestWhenDroneHomeNestNotAvailable() {
        // 机的归属巢存在，但不在本轮可用巢列表里（状态不可用或缺坐标）：
        // 候选生成只认本轮可用巢，机同样产不出组合。
        CargoNestInput.DroneView awayDrone = new CargoNestInput.DroneView(
                "UAV-01", "NEST-OTHER", "M30T", "空闲", 90.0, 6.0);

        CargoNestChoice choice = selector.select(input(
                List.of(nearNest(), farNest()), List.of(awayDrone)));

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE, choice.reason());
    }

    // ───────────── 落选原因 ─────────────

    @Test
    @DisplayName("选巢：船位缺失时无距离基准，返回未命中")
    void select_missesWhenShipPositionMissing() {
        CargoNestInput input = new CargoNestInput(Double.NaN, Double.NaN, PAYLOAD_KG,
                List.of(nearNest()), List.of(idleDrone()), MAX_DISTANCE_KM, MIN_BATTERY_PCT);

        CargoNestChoice choice = selector.select(input);

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_SHIP_POSITION, choice.reason());
        assertNull(choice.nestId(), "未命中时不得给出巢编号");
    }

    @Test
    @DisplayName("选巢：半径内没有巢时返回未命中")
    void select_missesWhenNoNestInRange() {
        CargoNestInput.NestView tooFar = new CargoNestInput.NestView(
                "NEST-TOO-FAR", 116.5, 29.5, List.of("M30T"));

        CargoNestChoice choice = selector.select(input(List.of(tooFar), List.of(idleDrone())));

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_NEST_AVAILABLE, choice.reason());
    }

    @Test
    @DisplayName("选巢：半径内的巢全满时返回未命中并说明是容量原因")
    void select_missesWhenAllNestsFull() {
        CargoNestInput.NestView full = new CargoNestInput.NestView(
                "NEST-NEAR", 115.848, 28.642, List.of("M30T"), 0);

        CargoNestChoice choice = selector.select(input(List.of(full), List.of(idleDrone())));

        assertFalse(choice.hit());
        assertEquals(CargoNestChoice.Reason.NO_CAPACITY, choice.reason());
    }

    // ───────────── 排序确定性 ─────────────

    @Test
    @DisplayName("选巢：同距离时按巢编号升序，与入参顺序无关")
    void select_breaksDistanceTieByNestId() {
        CargoNestInput.NestView first = new CargoNestInput.NestView(
                "NEST-A", 115.848, 28.642, List.of("M30T"));
        CargoNestInput.NestView second = new CargoNestInput.NestView(
                "NEST-B", 115.848, 28.642, List.of("M30T"));
        // 机停在其中一个同坐标巢上：两个巢都停得下这台机，比的才是距离与编号
        CargoNestInput.DroneView drone = new CargoNestInput.DroneView(
                "UAV-01", "NEST-A", "M30T", "空闲", 90.0, 6.0);

        CargoNestChoice byNestId = selector.select(input(
                List.of(first, second), List.of(drone)));
        CargoNestChoice reversed = selector.select(input(
                List.of(second, first), List.of(drone)));

        assertEquals("NEST-A", byNestId.nestId());
        assertEquals(byNestId.nestId(), reversed.nestId(),
                "同距离的巢必须选出同一个，入参顺序不得影响结果——选巢结果要落库并决定起飞时刻");
    }

    // ───────────── 未登记字段的口径 ─────────────

    @Test
    @DisplayName("选巢：电量与载重未登记的机不被淘汰，巢未登记容量不拦")
    void select_treatsUnregisteredFieldsAsUnlimited() {
        CargoNestInput.DroneView unregistered = new CargoNestInput.DroneView(
                "UAV-01", "NEST-NEAR", "M30T", "空闲", Double.NaN, Double.NaN);
        CargoNestInput.NestView unknownCapacity = new CargoNestInput.NestView(
                "NEST-NEAR", 115.848, 28.642, List.of("M30T"), Integer.MAX_VALUE);
        CargoNestInput.NestView unknownTypes = new CargoNestInput.NestView(
                "NEST-FAR", 115.867, 28.698, List.of());

        CargoNestChoice choice = selector.select(input(
                List.of(unknownCapacity, unknownTypes), List.of(unregistered)));

        assertTrue(choice.hit(), "未登记字段按不限制处理，不得静默淘汰");
        assertEquals("NEST-NEAR", choice.nestId());
    }

    @Test
    @DisplayName("选巢：输入为 null 时抛 IllegalArgumentException")
    void select_rejectsNullInput() {
        try {
            selector.select(null);
            org.junit.jupiter.api.Assertions.fail("input 为 null 时应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("input"));
        }
    }
}
