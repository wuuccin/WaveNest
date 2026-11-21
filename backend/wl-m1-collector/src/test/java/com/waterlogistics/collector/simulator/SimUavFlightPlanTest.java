package com.waterlogistics.collector.simulator;

import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仿真航迹位置来源的单测。
 *
 * <p>全部用两点一线的短航迹做断言：南北向直线便于手算中点与航向，
 * 也不用重复实现被测类的插值公式来凑期望值。
 *
 * <p>两个 Mapper 都用动态代理假实现，不接数据库——本类只读两个方法，
 * 假实现比引入 Mockito 更少依赖（与 {@code DispatchPlanVerifierTest} 同一手法）。
 */
class SimUavFlightPlanTest {

    /** 南端机巢，与 {@link SimUavTelemetryConnector} 的默认值一致 */
    private static final double NEST_LON = 115.848;
    private static final double NEST_LAT = 28.642;

    private static final String UAV = "UAV-01";
    private static final String ORDER = "ORD-TEST-1";

    @Test
    @DisplayName("去程中：按航迹点的时间轴插值，中点落在两点中间，航向沿航段方向")
    void interpolatesAlongOutboundByTime() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.OUTBOUND),
                List.of(point(PlanTrackFrame.SEGMENT_OUTBOUND, 1, 115.848, 28.642, 1_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 2, 115.848, 28.652, 2_000L)));

        Map<String, SimUavFlightPlan.Fix> fixes = plan.fixes(1_500L, homes(), 12.0);

        assertEquals(1, fixes.size(), "在飞订单应产出一个位置");
        SimUavFlightPlan.Fix fix = fixes.get(UAV);
        assertEquals(115.848, fix.lon(), 1e-9);
        assertEquals(28.647, fix.lat(), 1e-9, "时间过半应走到纬度中点");
        assertEquals(0.0, fix.headingDeg(), 1e-9, "自南向北飞，航向应为正北 0 度");
    }

    @Test
    @DisplayName("已派发但未到起飞时刻：停在航迹首点，也就是机巢")
    void holdsAtNestBeforeTakeoff() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.DISPATCHED),
                List.of(point(PlanTrackFrame.SEGMENT_OUTBOUND, 1, 115.848, 28.642, 1_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 2, 115.848, 28.652, 2_000L)));

        SimUavFlightPlan.Fix fix = plan.fixes(500L, homes(), 12.0).get(UAV);

        assertEquals(28.642, fix.lat(), 1e-9, "起飞时刻之前不得提前沿航迹前推");
    }

    @Test
    @DisplayName("末段交接中：停在会面点等船，不再沿航迹往前推")
    void waitsAtMeetDuringHandover() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.HANDOVER),
                List.of(point(PlanTrackFrame.SEGMENT_OUTBOUND, 1, 115.848, 28.642, 1_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 2, 115.848, 28.652, 2_000L)));

        SimUavFlightPlan.Fix fix = plan.fixes(9_000L, homes(), 12.0).get(UAV);

        assertEquals(28.652, fix.lat(), 1e-9, "交接期飞机应停在会面点");
    }

    @Test
    @DisplayName("空机转场与去程接成一条时间轴，终点仍是会面点")
    void joinsTransferAndOutbound() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.OUTBOUND),
                List.of(point(PlanTrackFrame.SEGMENT_TRANSFER, 1, 115.840, 28.640, 1_000L),
                        point(PlanTrackFrame.SEGMENT_TRANSFER, 2, 115.848, 28.642, 2_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 1, 115.848, 28.642, 2_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 2, 115.848, 28.652, 3_000L)));

        SimUavFlightPlan.Fix early = plan.fixes(1_500L, homes(), 12.0).get(UAV);
        SimUavFlightPlan.Fix late = plan.fixes(9_000L, homes(), 12.0).get(UAV);

        assertEquals(115.844, early.lon(), 1e-9, "转场段中点应落在两巢之间的连线上");
        assertEquals(28.652, late.lat(), 1e-9, "去程走完后停在会面点");
    }

    @Test
    @DisplayName("返航中：从被召回时所在位置直线回巢，不瞬移到会面点")
    void returnsStraightHomeAfterRecall() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.RETURNING),
                List.of(point(PlanTrackFrame.SEGMENT_OUTBOUND, 1, 115.848, 28.642, 1_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 2, 115.848, 28.652, 2_000L)));

        // 第一拍观测到返航中：飞机在航迹末点，即会面点，距机巢约 1113 m
        SimUavFlightPlan.Fix recalled = plan.fixes(2_000L, homes(), 12.0).get(UAV);
        assertEquals(28.652, recalled.lat(), 1e-9);

        // 1113.2 m ÷ 12 m/s ≈ 92.8 s 到巢，走过一半时间应落在中点
        SimUavFlightPlan.Fix halfway = plan.fixes(2_000L + 46_400L, homes(), 12.0).get(UAV);
        assertEquals(28.647, halfway.lat(), 0.001, "半小时程应回到纬度中点");
        assertEquals(180.0, halfway.headingDeg(), 1e-6, "南向回巢，航向应为 180 度");

        SimUavFlightPlan.Fix landed = plan.fixes(2_000L + 200_000L, homes(), 12.0).get(UAV);
        assertEquals(NEST_LAT, landed.lat(), 1e-9, "到巢后停在机巢，不再越过去");
    }

    @Test
    @DisplayName("提前召回：起点取召回时刻的航迹位置，不是计划返程的起点")
    void recallStartsFromCurrentPosition() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.RETURNING),
                List.of(point(PlanTrackFrame.SEGMENT_OUTBOUND, 1, 115.848, 28.642, 1_000L),
                        point(PlanTrackFrame.SEGMENT_OUTBOUND, 2, 115.848, 28.662, 3_000L)));

        // 去程只走到四分之一（纬度 28.647）就被召回
        SimUavFlightPlan.Fix atRecall = plan.fixes(1_500L, homes(), 12.0).get(UAV);
        assertEquals(28.647, atRecall.lat(), 1e-6, "召回时刻应仍在去程半路");

        double distanceM = 0.005 * 111_320.0;
        long halfWayMs = 1_500L + Math.round(distanceM / 12.0 / 2.0 * 1000.0);
        SimUavFlightPlan.Fix halfway = plan.fixes(halfWayMs, homes(), 12.0).get(UAV);
        assertEquals(28.6445, halfway.lat(), 0.001, "应从半路直接往回飞，不得先跳到会面点");
    }

    @Test
    @DisplayName("没有在飞订单：不产出位置，连接器据此退回圆周巡航")
    void noActiveOrderYieldsNoFix() {
        SimUavFlightPlan plan = planWith(null, List.of());

        assertTrue(plan.fixes(1_000L, homes(), 12.0).isEmpty());
    }

    @Test
    @DisplayName("订单没有计划航迹：不产出位置，也不抛异常")
    void missingPlanDegradesToNoFix() {
        SimUavFlightPlan plan = planWith(order(OrderStatus.OUTBOUND), List.of());

        Map<String, SimUavFlightPlan.Fix> fixes = plan.fixes(1_000L, homes(), 12.0);

        assertFalse(fixes.containsKey(UAV));
    }

    // ------------------------------------------------------------------
    // 假数据
    // ------------------------------------------------------------------

    private static Map<String, SimUavFlightPlan.Home> homes() {
        Map<String, SimUavFlightPlan.Home> homes = new HashMap<>();
        homes.put(UAV, new SimUavFlightPlan.Home(NEST_LON, NEST_LAT));
        return homes;
    }

    private static OrderTaskEntity order(OrderStatus status) {
        OrderTaskEntity order = new OrderTaskEntity();
        order.setOrderId(ORDER);
        order.setDroneId(UAV);
        order.setStatus(status.getValue());
        order.setMeetLon(BigDecimal.valueOf(NEST_LON));
        order.setMeetLat(BigDecimal.valueOf(NEST_LAT));
        return order;
    }

    private static PlanTrackEntity point(String kind, int seq, double lon, double lat, long tMs) {
        PlanTrackEntity point = new PlanTrackEntity();
        point.setOrderId(ORDER);
        point.setRouteId("RT-TEST");
        point.setSegmentKind(kind);
        point.setSeq(seq);
        point.setLon(BigDecimal.valueOf(lon));
        point.setLat(BigDecimal.valueOf(lat));
        point.setTMs(tMs);
        return point;
    }

    private static SimUavFlightPlan planWith(OrderTaskEntity order, List<PlanTrackEntity> points) {
        List<OrderTaskEntity> orders = order == null ? List.of() : List.of(order);
        return new SimUavFlightPlan(orderTaskMapper(orders), planTrackMapper(points));
    }

    private static OrderTaskMapper orderTaskMapper(List<OrderTaskEntity> orders) {
        return (OrderTaskMapper) Proxy.newProxyInstance(
                OrderTaskMapper.class.getClassLoader(),
                new Class<?>[]{OrderTaskMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> orders;
                    case "toString" -> "fakeOrderTaskMapper";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static PlanTrackMapper planTrackMapper(List<PlanTrackEntity> points) {
        return (PlanTrackMapper) Proxy.newProxyInstance(
                PlanTrackMapper.class.getClassLoader(),
                new Class<?>[]{PlanTrackMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectByOrderId" -> points;
                    case "toString" -> "fakePlanTrackMapper";
                    case "hashCode" -> 2;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
