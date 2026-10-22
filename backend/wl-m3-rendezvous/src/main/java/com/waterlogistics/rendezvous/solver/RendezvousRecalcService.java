package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.RendezvousSolver;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.ShipState;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.DroneContextUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;

import java.util.Objects;



/**
 * 6a 会面点重算的模块层编排——「同一机、同一巢」重算（《32-M3-动态会面》§3.3、
 * 《81-B组算法方案》§2.5）：反查执行机台账 → 取快照 → 版本闸门 → 调 {@code algo.meeting}。
 *
 * <p>口径（照文档）：
 * <ul>
 *   <li><b>求解起点＝船状态时刻</b>（{@code shipStateT} 求解必需）；</li>
 *   <li><b>状态船位只用于定向</b>——作修正有效速度的方位锚点，不替代快照运动模型；</li>
 *   <li><b>不触发航迹重算</b>——航迹是否重生成由 33 纠偏循环另行调 13 号（量判分离）；</li>
 *   <li>不落库、不改占位、无状态副作用（《81》§2.4 硬约束 1）。</li>
 * </ul>
 */
public class RendezvousRecalcService {

    private final TrajectorySnapshotPort snapshotPort;

    private final DroneContextPort droneContextPort;





    /**
     * 装配重算服务——两个端口均由 M2 实现、{@code wl-app} 注入。
     *
     * @param snapshotPort     轨迹快照端口（重算时自取快照并校验版本）
     * @param droneContextPort 执行机台账端口（按 droneId 反查机型、归属机巢与当前组合）
     */
    public RendezvousRecalcService(TrajectorySnapshotPort snapshotPort, DroneContextPort droneContextPort) {
        this.snapshotPort = Objects.requireNonNull(snapshotPort, "snapshotPort 不能为空");
        this.droneContextPort = Objects.requireNonNull(droneContextPort, "droneContextPort 不能为空");
    }





    /**
     * 重算一个在执行中组合的会面点。
     *
     * @param request 6a 重算请求（接收侧形态照《81》§2.5）
     * @param options 任务与阈值参数（调用方注入）
     * @return requestId ＋ 会面点解（有解／无解均属正常返回）
     * @throws DroneContextUnavailableException 反查不到执行机台账——拒绝求解
     * @throws SnapshotUnavailableException     查无该船快照——拒绝求解
     * @throws SnapshotVersionMismatchException 期望版本与快照实际版本不一致——拒绝求解
     * @throws IllegalArgumentException         快照点列为空、船状态时刻超出快照覆盖或时限早于状态时刻——拒绝重算
     */
    public RendezvousRecalcResult recompute(RendezvousRecalcRequest request, RecalcOptions options) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(options, "options 不能为空");
        DroneContext context = this.droneContextPort.resolve(request.droneId());
        if (context == null) {
            throw new DroneContextUnavailableException(request.droneId());
        }

        ShipTrackSnapshot snapshot = SnapshotGate.fetchVerified(
                this.snapshotPort, request.shipMmsi(), request.snapshotVersion()
                                                               );

        RecalcStateSnapshot state = request.stateSnapshot();
        long snapshotStartT = snapshot.points().get(0).t();
        long snapshotEndT = snapshot.points().get(snapshot.points().size() - 1).t();
        if (state.shipStateT() < snapshotStartT || state.shipStateT() > snapshotEndT) {
            throw new IllegalArgumentException("船状态时刻超出快照覆盖范围，实际 shipStateT="
                    + state.shipStateT() + ", 快照区间=[" + snapshotStartT + ", " + snapshotEndT + "]");
        }
        if (options.deadlineMs() < state.shipStateT()) {
            throw new IllegalArgumentException("最晚会面时刻不得早于船状态时刻，实际 deadlineMs="
                                                       + options.deadlineMs() + ", shipStateT=" + state.shipStateT());
        }
        B_RendezvousSolution solution = RendezvousSolver.solve(new RendezvousSolveRequest(
                context.comboId(), context.nestLon(), context.nestLat(), context.cruiseSpeedMs(),
                options.representativeWind(), snapshot,
                new ShipState(state.shipLon(), state.shipLat(), state.shipCogDeg(), state.sogMs(), state.shipStateT()),
                state.shipStateT(), options.deadlineMs(), options.meetWindowMarginSec(),
                options.convergeEpsilonSec(), options.maxIterations()));

        return new RendezvousRecalcResult(request.requestId(), solution);
    }
}
