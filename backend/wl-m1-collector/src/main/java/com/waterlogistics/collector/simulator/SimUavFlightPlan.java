package com.waterlogistics.collector.simulator;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仿真无人机的位置来源：让演示机沿订单的计划航迹飞。
 *
 * <p>没有本类时，四架仿真机各绕自己的机巢画半径 27.8 m 的小圈（见
 * {@link SimUavTelemetryConnector} 的巡航注释），订单一进「去程中」，飞机仍然贴在机巢上，
 * 地图上只能看到图标原地转，看不出「飞机正在往船飞」。本类按订单状态与计划航迹的到达时刻
 * 插值出坐标与航向，交给连接器替换掉圆周位置。
 *
 * <p>分段口径直接取 {@link PlanTrackFrame} 的分段词，不自造第四种：
 * 空机转场与去程按航迹点的时间轴插值；末段交接停在会面点等船靠上来；
 * 返航中从当前位置直线回巢。
 *
 * <p><b>为什么不按计划返程航迹飞</b>：M4 的偏航判据用的是 T2 直基线，基线由
 * {@code DatabaseFlightContextProvider} 取「机巢到会面点」两点连成，而计划返程航迹相对这条
 * 基线的横向偏差实测 36.6 m，已越过偏航预警阈值 30.0 m（{@code offtrackWarningThreshold}）。
 * 照航迹飞会把每一次正常返航都报成偏航，连带触发在飞安全复核。返程走直线，横向偏差为零，
 * 而且「提前召回」时飞机并不在会面点，照计划返程插值会先瞬移到会面点再往回飞。
 *
 * <p>本类只读库（{@code order_task} 与 {@code plan_track}），不写库、不发事件。
 * 查库失败时本轮返回空表，连接器退回圆周巡航，演示不至于因为一次查询失败停摆。
 */
@Component
public class SimUavFlightPlan {

    private static final Logger log = LoggerFactory.getLogger(SimUavFlightPlan.class);

    /** 十进制度与米的换算，与连接器、M4 清洗同口径 */
    private static final double METERS_PER_DEGREE = 111_320.0;

    /** 视为「在飞」的订单状态：已派发之后、已完成之前 */
    private static final List<String> ACTIVE_STATUSES = List.of(
            OrderStatus.DISPATCHED.getValue(),
            OrderStatus.OUTBOUND.getValue(),
            OrderStatus.HANDOVER.getValue(),
            OrderStatus.RETURNING.getValue());

    private final OrderTaskMapper orderTaskMapper;
    private final PlanTrackMapper planTrackMapper;

    /** 订单号 → 计划航迹缓存；同一订单换了航迹版本才重新查库 */
    private final Map<String, OrderPlan> plans = new HashMap<>();

    /** 机号 → 本轮回程的起点与起始时刻（提前召回时起点不是会面点） */
    private final Map<String, ReturnLeg> returns = new HashMap<>();

    /** 查库失败时只报一次，恢复后重新计 */
    private boolean degraded;

    public SimUavFlightPlan(OrderTaskMapper orderTaskMapper, PlanTrackMapper planTrackMapper) {
        this.orderTaskMapper = orderTaskMapper;
        this.planTrackMapper = planTrackMapper;
    }

    /**
     * 算出本轮在飞的每架机应处的位置。
     *
     * @param nowMs    节拍时刻，Unix 毫秒
     * @param homes    机号 → 本机所属机巢坐标；不在表里的机不产出位置
     * @param speedMs  巡航速度，米每秒，用于把「回程还差多少秒」换算成位移
     * @return 机号 → 位置；没有在飞订单的机不出现在表里
     */
    public synchronized Map<String, Fix> fixes(long nowMs, Map<String, Home> homes, double speedMs) {
        Map<String, Fix> fixes = new HashMap<>();
        if (homes == null || homes.isEmpty()) {
            return fixes;
        }
        List<OrderTaskEntity> orders;
        try {
            orders = activeOrders(homes.keySet());
        } catch (RuntimeException error) {
            if (!degraded) {
                log.warn("仿真航迹查询失败，本轮退回圆周巡航：err={}", error.getMessage());
                degraded = true;
            }
            return fixes;
        }
        degraded = false;

        Set<String> flying = new LinkedHashSet<>();
        Set<String> seenOrders = new LinkedHashSet<>();
        for (OrderTaskEntity order : orders) {
            String uavId = order.getDroneId();
            if (uavId == null || flying.contains(uavId) || !homes.containsKey(uavId)) {
                continue;
            }
            OrderPlan plan = planOf(order.getOrderId());
            if (plan == null || plan.outbound().isEmpty()) {
                continue;
            }
            seenOrders.add(order.getOrderId());
            boolean returning = OrderStatus.RETURNING.getValue().equals(order.getStatus());
            Fix fix;
            if (returning) {
                fix = returningFix(uavId, plan, nowMs, homes.get(uavId), speedMs);
            } else {
                returns.remove(uavId);
                fix = positionAt(plan.outbound(), nowMs);
            }
            if (fix != null) {
                fixes.put(uavId, fix);
                flying.add(uavId);
            }
        }

        // 缓存只留这一轮还在飞的订单与还在返航的机，避免演示连跑几小时后越积越多
        plans.keySet().retainAll(seenOrders);
        returns.keySet().retainAll(flying);
        return fixes;
    }

    // ------------------------------------------------------------------
    // 内部：取数
    // ------------------------------------------------------------------

    private List<OrderTaskEntity> activeOrders(Set<String> uavIds) {
        // 用列名写法的 QueryWrapper，不用 LambdaQueryWrapper：本类在单测里不经 Spring 装配，
        // lambda 包装器要求 MyBatis-Plus 已建好实体元数据缓存，直接就绪失败
        // （can not find lambda cache for this entity）。
        List<OrderTaskEntity> orders = orderTaskMapper.selectList(
                new QueryWrapper<OrderTaskEntity>()
                        .in("drone_id", uavIds)
                        .in("status", ACTIVE_STATUSES)
                        .isNotNull("meet_lon")
                        .isNotNull("meet_lat")
                        .orderByDesc("create_time"));
        return orders == null ? List.of() : orders;
    }

    private OrderPlan planOf(String orderId) {
        List<PlanTrackEntity> points = planTrackMapper.selectByOrderId(orderId);
        if (points == null || points.isEmpty()) {
            plans.remove(orderId);
            return null;
        }
        String routeId = null;
        for (PlanTrackEntity point : points) {
            if (point.getRouteId() != null && !point.getRouteId().isBlank()) {
                routeId = point.getRouteId();
                break;
            }
        }
        OrderPlan cached = plans.get(orderId);
        if (cached != null && cached.routeId().equals(routeId)) {
            return cached;
        }
        OrderPlan built = build(orderId, routeId, points);
        if (built == null) {
            plans.remove(orderId);
        } else {
            plans.put(orderId, built);
        }
        return built;
    }

    /**
     * 只拼出航段：空机转场与去程按时间轴合并。末段交接与返程不入选，
     * 因为「等交接」停在会面点、「返航」走直线，两者都不需要航迹点。
     */
    private OrderPlan build(String orderId, String routeId, List<PlanTrackEntity> points) {
        List<PlanPoint> outbound = new ArrayList<>();
        for (PlanTrackEntity point : points) {
            String kind = point.getSegmentKind();
            boolean outboundLeg = PlanTrackFrame.SEGMENT_TRANSFER.equals(kind)
                    || PlanTrackFrame.SEGMENT_OUTBOUND.equals(kind);
            if (!outboundLeg || point.getTMs() == null || point.getLon() == null || point.getLat() == null) {
                continue;
            }
            outbound.add(new PlanPoint(
                    point.getLon().doubleValue(),
                    point.getLat().doubleValue(),
                    point.getTMs()));
        }
        if (outbound.isEmpty()) {
            return null;
        }
        outbound.sort(Comparator.comparingLong(PlanPoint::tMs));
        return new OrderPlan(orderId, routeId, List.copyOf(outbound));
    }

    // ------------------------------------------------------------------
    // 内部：几何
    // ------------------------------------------------------------------

    /** 航迹时间轴上取点：早于首点停在首点，晚于末点停在末点，中间按前后两点线性插值。 */
    private static Fix positionAt(List<PlanPoint> points, long nowMs) {
        if (points.isEmpty()) {
            return null;
        }
        PlanPoint first = points.get(0);
        int last = points.size() - 1;
        if (nowMs <= first.tMs()) {
            return new Fix(first.lon(), first.lat(), headingBetween(first, points.get(Math.min(1, last))));
        }
        if (nowMs >= points.get(last).tMs()) {
            return new Fix(points.get(last).lon(), points.get(last).lat(),
                    headingBetween(points.get(Math.max(0, last - 1)), points.get(last)));
        }
        for (int i = 0; i < last; i++) {
            PlanPoint from = points.get(i);
            PlanPoint to = points.get(i + 1);
            if (nowMs < from.tMs() || nowMs > to.tMs()) {
                continue;
            }
            long span = to.tMs() - from.tMs();
            double ratio = span <= 0 ? 1.0 : (double) (nowMs - from.tMs()) / span;
            double lon = from.lon() + (to.lon() - from.lon()) * ratio;
            double lat = from.lat() + (to.lat() - from.lat()) * ratio;
            return new Fix(lon, lat, headingBetween(from, to));
        }
        PlanPoint tail = points.get(last);
        return new Fix(tail.lon(), tail.lat(), headingBetween(points.get(Math.max(0, last - 1)), tail));
    }

    /**
     * 返航位置：从第一次观测到「返航中」时飞机所在的位置起飞，沿直线回机巢，
     * 已飞距离按「巡航速度 × 已过时间」推进，到巢后停在巢上等订单收尾。
     */
    private Fix returningFix(String uavId, OrderPlan plan, long nowMs, Home home, double speedMs) {
        if (home == null) {
            return null;
        }
        ReturnLeg leg = returns.get(uavId);
        if (leg == null) {
            Fix at = positionAt(plan.outbound(), nowMs);
            if (at == null) {
                return null;
            }
            leg = new ReturnLeg(nowMs, at.lon(), at.lat());
            returns.put(uavId, leg);
        }
        double distanceM = metersBetween(leg.fromLon(), leg.fromLat(), home.lon(), home.lat());
        double heading = bearingBetween(leg.fromLon(), leg.fromLat(), home.lon(), home.lat());
        if (distanceM < 1.0) {
            return new Fix(home.lon(), home.lat(), heading);
        }
        double elapsedSec = Math.max(0.0, (nowMs - leg.startMs()) / 1000.0);
        double flownM = elapsedSec * Math.max(0.1, speedMs);
        double ratio = Math.min(1.0, flownM / distanceM);
        double lon = leg.fromLon() + (home.lon() - leg.fromLon()) * ratio;
        double lat = leg.fromLat() + (home.lat() - leg.fromLat()) * ratio;
        return new Fix(lon, lat, heading);
    }

    /** 两点连线方向（度，0 为正北，顺时针），用于填遥测的航向字段。 */
    private static double headingBetween(PlanPoint from, PlanPoint to) {
        return bearingBetween(from.lon(), from.lat(), to.lon(), to.lat());
    }

    private static double bearingBetween(double fromLon, double fromLat, double toLon, double toLat) {
        double cosLat = Math.cos(Math.toRadians((fromLat + toLat) / 2.0));
        double east = (toLon - fromLon) * cosLat;
        double north = toLat - fromLat;
        if (east == 0.0 && north == 0.0) {
            return 0.0;
        }
        double deg = Math.toDegrees(Math.atan2(east, north));
        return deg < 0 ? deg + 360.0 : deg;
    }

    private static double metersBetween(double fromLon, double fromLat, double toLon, double toLat) {
        double cosLat = Math.cos(Math.toRadians((fromLat + toLat) / 2.0));
        double dx = (toLon - fromLon) * METERS_PER_DEGREE * cosLat;
        double dy = (toLat - fromLat) * METERS_PER_DEGREE;
        return Math.hypot(dx, dy);
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /**
     * 一架机在本轮应上报的位置。
     *
     * @param lon       经度，十进制度
     * @param lat       纬度，十进制度
     * @param headingDeg 航向，度，0 为正北、顺时针
     */
    public record Fix(double lon, double lat, double headingDeg) {
    }

    /**
     * 机巢坐标。
     *
     * @param lon 经度
     * @param lat 纬度
     */
    public record Home(double lon, double lat) {
    }

    private record PlanPoint(double lon, double lat, long tMs) {
    }

    private record OrderPlan(String orderId, String routeId, List<PlanPoint> outbound) {
    }

    private record ReturnLeg(long startMs, double fromLon, double fromLat) {
    }
}
