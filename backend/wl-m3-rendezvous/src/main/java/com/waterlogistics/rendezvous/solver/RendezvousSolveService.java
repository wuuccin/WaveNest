package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.RendezvousSolver;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.rendezvous.port.SnapshotUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;

import java.util.Objects;



/**
 * 会面求解在模块层的编排——取快照 → 校验版本 → 调 {@code algo.meeting}
 * （《42-包结构设计》§3.3 solver/、《81-B组算法方案》§3.1）。
 *
 * <p>职责边界：本层只做「取数与一致性校验 ＋ 类型搬运」，几何求解全部在
 * {@link RendezvousSolver}；无状态副作用、不落库、不改占位（《81》§2.4 硬约束 1）。
 */
public class RendezvousSolveService {

    private final TrajectorySnapshotPort snapshotPort;





    /**
     * 装配求解服务——快照端口由 {@code wl-app} 注入。
     *
     * @param snapshotPort 轨迹快照端口（求解时自取快照并校验版本）
     */
    public RendezvousSolveService(TrajectorySnapshotPort snapshotPort) {
        this.snapshotPort = Objects.requireNonNull(snapshotPort, "snapshotPort 不能为空");
    }





    /**
     * 求解一个候选组合的会面点。
     *
     * @param cmd 组合级求解命令
     * @return 会面点解（有解／无解均属正常返回；追不上等无解走 {@code hasSolution=false}）
     * @throws SnapshotUnavailableException     端口查无该船快照或快照已过有效期，数据前置缺失，拒绝求解
     * @throws SnapshotVersionMismatchException 期望版本与快照实际版本不一致——拒绝求解
     * @throws IllegalArgumentException         命令入参非法；同轮多候选的版本一致性由调用方锚定并下发
     *                                          （《81》§2.1 硬要求 1：本轮调度多候选须共用同一版本）
     * @throws IllegalStateException            快照覆盖不足，求解时刻超出快照点列范围
     */
    public B_RendezvousSolution solve(RendezvousCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");
        ShipTrackSnapshot snapshot = SnapshotGate.fetchVerified(
                this.snapshotPort, cmd.shipMmsi(), cmd.snapshotVersion());
        long snapshotStartT = snapshot.points().get(0).t();
        long snapshotEndT = snapshot.points().get(snapshot.points().size() - 1).t();
        long validUntilT = snapshot.t0() + (long) snapshot.validitySec() * 1000L;
        if (cmd.nowMs() > validUntilT) {
            throw SnapshotUnavailableException.expired(cmd.shipMmsi(), cmd.nowMs(), validUntilT);
        }
        if (cmd.nowMs() < snapshotStartT || cmd.nowMs() > snapshotEndT) {
            throw new IllegalStateException("快照覆盖不足，求解时刻超出快照覆盖范围，实际 nowMs=" + cmd.nowMs()
                                                    + ", 快照区间=[" + snapshotStartT + ", " + snapshotEndT + "]");
        }
        return RendezvousSolver.solve(new RendezvousSolveRequest(
                cmd.comboId(), cmd.nestLon(), cmd.nestLat(), cmd.cruiseSpeedMs(),
                cmd.representativeWind(), snapshot, null /* shipState：常规求解回落快照插值 */,
                cmd.nowMs(), cmd.deadlineMs(), cmd.meetWindowMarginSec(),
                cmd.convergeEpsilonSec(), cmd.maxIterations()));
    }
}
