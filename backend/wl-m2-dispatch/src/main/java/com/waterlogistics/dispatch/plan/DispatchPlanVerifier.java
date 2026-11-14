package com.waterlogistics.dispatch.plan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Waypoint;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.dispatch.rendezvous.RendezvousCoordinator.MeetPlan;
import com.waterlogistics.dispatch.rendezvous.RendezvousVerificationPort;
import com.waterlogistics.rendezvous.conflict.ConflictParams;
import com.waterlogistics.rendezvous.handover.HandoverCommand;
import com.waterlogistics.rendezvous.handover.HandoverParams;
import com.waterlogistics.rendezvous.handover.HandoverSample;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCommand;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 派单定案校验（《1112-功能做实批次｜接口约定》v1.2 §4.2）。
 *
 * <p><b>调用顺序固定</b>：{@code computeTakeoffTime} → {@code detectConflict} →
 * {@code judgeHandover}，三个门面方法在 {@code src/main} 各一个调用点，全在派单事务内。
 *
 * <p><b>冲突处置</b>：检出冲突后按「错峰 → 绕行 → 回退换机」三级处置，不得直接放行。
 * 错峰＝把本单计划航迹整体后移若干采样步长后重查，消除冲突则把时移量交给编排器
 * 重建帧（落库与推帧都用错峰后的时刻）；{@code detourRoute} 首版恒 {@code null}，
 * 绕行不可用；两级都不成立即抛异常回滚派单事务，回退换候选组合。
 *
 * <p><b>只算不假造</b>：末段交接判定用的是<b>计划</b>航迹点＋船侧预测快照的预检采样，
 * 不放 {@code dropDone}、不写实际交接时刻；预检失败只记结论、不阻断派单。
 */
@Component
public class DispatchPlanVerifier {

    private static final Logger log = LoggerFactory.getLogger(DispatchPlanVerifier.class);
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String SEP_HORIZ_M_KEY = "sepHorizM";
    private static final String SEP_VERT_M_KEY = "sepVertM";
    private static final String CONF_SAMPLE_STEP_SEC_KEY = "confSampleStepSec";

    private static final double DEFAULT_SEP_HORIZ_M = 50.0;
    private static final double DEFAULT_SEP_VERT_M = 15.0;
    private static final int DEFAULT_CONF_SAMPLE_STEP_SEC = 1;

    /** 时刻对齐粒度：{@code sys_param} 未登记该键，按占位时窗粒度取 1 秒，不臆造键名。 */
    private static final long TIME_GRID_MS = 1_000L;

    /** 末段预检阈值：《81-B组算法方案》§3.7 建议初值，{@code sys_param} 未登记，不臆造键名。 */
    private static final HandoverParams HANDOVER_PARAMS =
            new HandoverParams(50.0, 20.0, 1.5, 3.0, 12.0, 0.02);

    /** 错峰步长倍数：一次错峰后移三个采样步长。 */
    private static final int STAGGER_STEPS = 3;

    private static final List<String> IN_FLIGHT_STATUSES = List.of(
            OrderStatus.DISPATCHED.getValue(),
            OrderStatus.OUTBOUND.getValue(),
            OrderStatus.HANDOVER.getValue());

    private final RendezvousVerificationPort verificationPort;
    private final SysParamLoader sysParamLoader;
    private final OrderTaskMapper orderTaskMapper;
    private final PlanTrackMapper planTrackMapper;
    private final TrajectorySnapshotPort snapshotPort;

    public DispatchPlanVerifier(RendezvousVerificationPort verificationPort,
            SysParamLoader sysParamLoader, OrderTaskMapper orderTaskMapper,
            PlanTrackMapper planTrackMapper, TrajectorySnapshotPort snapshotPort) {
        this.verificationPort = verificationPort;
        this.sysParamLoader = sysParamLoader;
        this.orderTaskMapper = orderTaskMapper;
        this.planTrackMapper = planTrackMapper;
        this.snapshotPort = snapshotPort;
    }

    /** 定案结果：起飞时刻、错峰时移量、末段预检结论、二查次数。 */
    public record Outcome(long takeoffMs, long shiftMs, boolean handoverPredicted, int conflictChecks) {
    }

    /**
     * 对一帧已装配的规划航迹做定案校验。
     *
     * @param frame    规划航迹帧
     * @param plan     会面方案（取会面时刻与计划会面点）
     * @param orderId  订单号
     * @param comboId  中选组合标识
     * @param droneId  执行机
     * @param shipMmsi 船舶标识（取预测快照预检末段）
     * @param nowMs    当前时刻，Unix 毫秒
     * @return 定案结果；{@code shiftMs > 0} 时编排器须按该值重建帧后再落库与推帧
     * @throws IllegalStateException 去程计划点不足，或冲突经错峰／绕行仍不能消除
     */
    public Outcome verify(PlanTrackFrame frame, MeetPlan plan, String orderId,
            String comboId, String droneId, String shipMmsi, long nowMs) {
        List<PlanTrackFrame.Point> outboundPoints = pointsOf(frame, PlanTrackFrame.SEGMENT_OUTBOUND);
        if (outboundPoints.size() < 2) {
            throw new IllegalStateException("订单 " + orderId + " 去程计划点不足 2 个，无法定案校验");
        }
        B_FlyableRoute outbound = toRoute(frame.routeId() + "-out", frame.routeId(), comboId,
                outboundPoints);
        long meetMs = plan.meetTime().atZone(DB_ZONE).toInstant().toEpochMilli();

        // ① 起飞时刻回填：门面「只算不定案」（《约定》§4.2），时刻定案、回写占位与
        // 二次校验归 D 功能 7。算式判不可行时只留痕，不得由本校验器阻断派单事务。
        B_TakeoffTimeResult takeoff = verificationPort.computeTakeoffTime(
                new TakeoffTimeCommand(comboId, meetMs, tauFineSec(outboundPoints), nowMs, TIME_GRID_MS));
        if (!takeoff.feasible()) {
            log.warn("订单 {} 起飞时刻算式不可行（{}），只留痕不定案；时刻定案归 D 功能 7",
                    orderId, takeoff.infeasibleReason());
        }

        // ② 航迹级空域冲突二查。
        ConflictParams conflictParams = new ConflictParams(
                sysParamLoader.getDouble(SEP_HORIZ_M_KEY, DEFAULT_SEP_HORIZ_M),
                sysParamLoader.getDouble(SEP_VERT_M_KEY, DEFAULT_SEP_VERT_M),
                (long) sysParamLoader.getInt(CONF_SAMPLE_STEP_SEC_KEY, DEFAULT_CONF_SAMPLE_STEP_SEC) * 1000L);
        B_FlyableRoute other = otherRoute(orderId, frame, comboId);
        B_ConflictResult first = verificationPort.detectConflict(outbound, other, conflictParams);
        int checks = 1;
        long shiftMs = 0L;
        if (first.conflictFlag()) {
            // 第一级：错峰。整体后移若干采样步长后重查，消除冲突才允许定案。
            long staggerMs = conflictParams.sampleStepMs() * STAGGER_STEPS;
            B_FlyableRoute staggered = shiftRoute(outbound, staggerMs);
            B_ConflictResult afterStagger = verificationPort.detectConflict(
                    staggered, other, conflictParams);
            checks++;
            if (!afterStagger.conflictFlag()) {
                shiftMs = staggerMs;
                log.info("订单 {} 空域冲突经错峰 {} ms 消除，按时移后航迹定案", orderId, staggerMs);
            } else {
                // 第二级：绕行。首版 detourRoute 恒 null，不具备可飞绕行几何。
                if (afterStagger.detourRoute() != null && !afterStagger.detourRoute().isEmpty()) {
                    throw new IllegalStateException("订单 " + orderId
                            + " 空域冲突绕行几何已产出但本编排未接线，回退换候选组合");
                }
                // 第三级：回退换机。抛异常让派单事务整体回滚，由上层换候选。
                throw new IllegalStateException("订单 " + orderId
                        + " 空域冲突，错峰后仍冲突且绕行几何首版留桩，回退换候选组合；"
                        + "检查版本=" + first.checksumVersion());
            }
        }

        // ③ 末段会面与交接判定（计划预检，不放 dropDone、不写实际交接时刻）。
        boolean handoverPredicted = judgeHandover(frame, plan, orderId, droneId, shipMmsi, shiftMs);
        return new Outcome(takeoff.takeoffT(), shiftMs, handoverPredicted, checks);
    }

    private boolean judgeHandover(PlanTrackFrame frame, MeetPlan plan, String orderId,
            String droneId, String shipMmsi, long shiftMs) {
        List<PlanTrackFrame.Point> terminal = pointsOf(frame, PlanTrackFrame.SEGMENT_TERMINAL);
        if (terminal.size() < 2) {
            log.warn("订单 {} 末段计划点不足 2 个，跳过交接预检", orderId);
            return false;
        }
        ShipTrackSnapshot snapshot = null;
        try {
            if (shipMmsi != null && !shipMmsi.isBlank()) {
                snapshot = snapshotPort.latest(shipMmsi);
            }
        } catch (RuntimeException e) {
            log.debug("订单 {} 交接预检取预测快照失败，改用计划会面点作船位：err={}",
                    orderId, e.getMessage());
        }
        List<HandoverSample> samples = new ArrayList<>();
        for (int i = 0; i < terminal.size(); i++) {
            PlanTrackFrame.Point p = terminal.get(i);
            long t = p.tMs() + shiftMs;
            ShipPoint ship = pointAt(snapshot, t);
            double shipLon = ship == null ? plan.meetLon() : ship.lon();
            double shipLat = ship == null ? plan.meetLat() : ship.lat();
            samples.add(new HandoverSample(t, p.lon(), p.lat(), shipLon, shipLat, 0.0, false));
        }
        long plannedMeetT = plan.meetTime().atZone(DB_ZONE).toInstant().toEpochMilli() + shiftMs;
        B_HandoverConfirmation confirmation = verificationPort.judgeHandover(
                new HandoverCommand("TASK-" + orderId, droneId,
                        plan.meetLon(), plan.meetLat(), plannedMeetT, samples, HANDOVER_PARAMS));
        boolean predicted = confirmation.handoverDoneT() != null;
        log.info("订单 {} 末段交接计划预检：预测可完成={} 结果={} 样本数={}",
                orderId, predicted, confirmation.handoverResult(), samples.size());
        return predicted;
    }

    /** 对照航迹优先取其他在飞订单的已落库计划点；无并发任务时用本单返程段作基线自检。 */
    private B_FlyableRoute otherRoute(String orderId, PlanTrackFrame frame, String comboId) {
        try {
            List<OrderTaskEntity> others = orderTaskMapper.selectList(
                    new LambdaQueryWrapper<OrderTaskEntity>()
                            .in(OrderTaskEntity::getStatus, IN_FLIGHT_STATUSES)
                            .ne(OrderTaskEntity::getOrderId, orderId)
                            .last("LIMIT 20"));
            for (OrderTaskEntity candidate : others) {
                List<PlanTrackEntity> points = planTrackMapper.selectByOrderId(candidate.getOrderId());
                if (points.size() < 2) {
                    continue;
                }
                List<Waypoint> waypoints = new ArrayList<>();
                long lastT = Long.MIN_VALUE;
                for (PlanTrackEntity p : points) {
                    long t = p.getTMs() == null ? lastT + 1L : p.getTMs();
                    if (t <= lastT) {
                        t = lastT + 1L;
                    }
                    waypoints.add(new Waypoint(p.getLon().doubleValue(), p.getLat().doubleValue(),
                            p.getAltM() == null ? 0.0 : p.getAltM().doubleValue(),
                            p.getSpeedMs() == null ? 0.0 : p.getSpeedMs().doubleValue(), t));
                    lastT = t;
                }
                if (waypoints.size() >= 2) {
                    return new B_FlyableRoute(candidate.getOrderId() + "-plan", frame.routeId(),
                            comboId, waypoints, tauFineSec(waypoints), 0.0, 0.0,
                            List.of(), List.of());
                }
            }
        } catch (RuntimeException e) {
            log.warn("订单 {} 读取其他在飞航迹失败，回落本单返程段作对照：err={}",
                    orderId, e.getMessage());
        }
        List<PlanTrackFrame.Point> returning = pointsOf(frame, PlanTrackFrame.SEGMENT_RETURN);
        if (returning.size() < 2) {
            returning = pointsOf(frame, PlanTrackFrame.SEGMENT_OUTBOUND);
        }
        return toRoute(frame.routeId() + "-ret", frame.routeId(), comboId, returning);
    }

    private static B_FlyableRoute toRoute(String routeId, String version, String comboId,
            List<PlanTrackFrame.Point> points) {
        List<Waypoint> waypoints = points.stream()
                .map(p -> new Waypoint(p.lon(), p.lat(), p.altM(), p.speedMs(), p.tMs()))
                .toList();
        return new B_FlyableRoute(routeId, version, comboId, waypoints,
                tauFineSec(points), 0.0, 0.0, List.of(), List.of());
    }

    private static B_FlyableRoute shiftRoute(B_FlyableRoute route, long shiftMs) {
        List<Waypoint> shifted = route.waypoints().stream()
                .map(p -> new Waypoint(p.lon(), p.lat(), p.altM(), p.speedMs(), p.t() + shiftMs))
                .toList();
        return new B_FlyableRoute(route.routeId(), route.routeVersion(), route.comboId(),
                shifted, route.tauFine(), route.energyExact(), route.pathRisk(),
                route.landingPointsAlong(), route.avoidZones());
    }

    private static int tauFineSec(List<? extends Object> points) {
        if (points.size() < 2) {
            return 1;
        }
        long firstT = tOf(points.get(0));
        long lastT = tOf(points.get(points.size() - 1));
        long sec = Math.max(1L, Math.round((lastT - firstT) / 1000.0));
        return (int) Math.min(sec, 2_592_000L);
    }

    private static long tOf(Object point) {
        if (point instanceof Waypoint w) {
            return w.t();
        }
        return ((PlanTrackFrame.Point) point).tMs();
    }

    private static List<PlanTrackFrame.Point> pointsOf(PlanTrackFrame frame, String kind) {
        return frame.segments().stream()
                .filter(s -> kind.equals(s.kind()))
                .findFirst()
                .map(PlanTrackFrame.Segment::points)
                .orElse(List.of());
    }

    private static ShipPoint pointAt(ShipTrackSnapshot snapshot, long targetMs) {
        if (snapshot == null || snapshot.points() == null || snapshot.points().isEmpty()) {
            return null;
        }
        List<ShipPoint> points = snapshot.points();
        ShipPoint first = points.get(0);
        if (targetMs <= first.t()) {
            return first;
        }
        ShipPoint last = points.get(points.size() - 1);
        if (targetMs >= last.t()) {
            return last;
        }
        for (int i = 1; i < points.size(); i++) {
            ShipPoint right = points.get(i);
            if (targetMs <= right.t()) {
                ShipPoint left = points.get(i - 1);
                double span = (double) (right.t() - left.t());
                double ratio = span <= 0.0 ? 0.0 : (double) (targetMs - left.t()) / span;
                return new ShipPoint(targetMs,
                        left.lon() + ratio * (right.lon() - left.lon()),
                        left.lat() + ratio * (right.lat() - left.lat()),
                        right.cog(), right.sigmaAlong(), right.sigmaCross());
            }
        }
        return last;
    }
}
