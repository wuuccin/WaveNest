package com.waterlogistics.rendezvous.facade;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.rendezvous.conflict.ConflictParams;
import com.waterlogistics.rendezvous.conflict.TrackConflictChecker;
import com.waterlogistics.rendezvous.handover.HandoverCommand;
import com.waterlogistics.rendezvous.handover.HandoverJudge;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.solver.RecalcOptions;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcRequest;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcResult;
import com.waterlogistics.rendezvous.solver.RendezvousRecalcService;
import com.waterlogistics.rendezvous.solver.RendezvousSolveService;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCalculator;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCommand;
import com.waterlogistics.rendezvous.trajectory.TrackGenerator;

import java.util.Objects;



/**
 * 会面与航迹门面——M2 对 M3 的<b>唯一</b>同步入口（《42-包结构设计》§3.3 facade/、
 * 《22-接口契约》§5）；其余包（solver／trajectory／conflict／handover）不对外暴露。
 *
 * <p>编排纪律：会面求解与航迹生成都是<b>纯计算、无状态副作用</b>的可复用能力，
 * 支持按组合循环调用（《81-B组算法方案》§2.4）；只在被调用时求解、不主动发起
 * （《22》§5 硬约定 1）。
 *
 * <p>端口接线：{@code TrajectorySnapshotPort} 与 {@code DroneContextPort} 均由 M2 实现、
 * {@code wl-app} 装配（本类构造器接收）——M3 全程不知道 M2 存在。
 */
@SuppressWarnings("OverlyCoupledClass")
public class RendezvousFacade {

    private final RendezvousSolveService solveService;

    private final RendezvousRecalcService recalcService;





    /**
     * 装配门面——两个端口均由 M2 实现、{@code wl-app} 注入。
     *
     * @param snapshotPort     轨迹快照端口（快照自取与版本校验）
     * @param droneContextPort 执行机台账端口（6a 重算反查机型与归属机巢）
     */
    public RendezvousFacade(TrajectorySnapshotPort snapshotPort, DroneContextPort droneContextPort) {
        Objects.requireNonNull(snapshotPort, "snapshotPort 不能为空");
        Objects.requireNonNull(droneContextPort, "droneContextPort 不能为空");
        this.solveService = new RendezvousSolveService(snapshotPort);
        this.recalcService = new RendezvousRecalcService(snapshotPort, droneContextPort);
    }





    /**
     * 会面点求解——M2 第 3 步 · 判「追得上」（《22》§5），组合级循环调用。
     *
     * @param cmd 组合级求解命令（快照由其经端口自取并校验版本）
     * @return 会面点解；无解走 {@code hasSolution=false}
     * @throws com.waterlogistics.rendezvous.port.SnapshotUnavailableException     查无快照或归属不符
     * @throws com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException 快照版本不一致
     * @throws IllegalArgumentException 命令入参非法
     * @throws NullPointerException     命令为 {@code null}
     */
    public B_RendezvousSolution solve(RendezvousCommand cmd) {
        return this.solveService.solve(cmd);
    }





    /**
     * 航迹生成——M2 第 5 步（去程）与第 8 步（返程）同一能力两次调用（《33》§4.1）。
     *
     * @param cmd 航迹生成入参（起点、终点、巡检参数全由调用方注入）
     * @return 可飞航迹（首版为直线简化，寻路内核留桩待精算版替换）
     * @throws IllegalArgumentException 航迹入参非法
     * @throws NullPointerException     入参为 {@code null}
     */
    public B_FlyableRoute generateTrack(GenerateRouteCommand cmd) {
        return TrackGenerator.generate(cmd);
    }





    /**
     * 6a 会面点重算——同一机、同一巢重算（《32-M3-动态会面》§3.3），接收侧形态照
     * 《81-B组算法方案》§2.5；只算不落库、不改占位、<b>不触发航迹重算</b>——航迹是否
     * 重生成由 33 纠偏循环另行调 {@link #generateTrack}（量判分离、两笔账不混）。
     *
     * @param request 重算请求（E 组按同源口径构造）
     * @param options 任务与阈值参数（调用方注入）
     * @return requestId ＋ 会面点解
     * @throws com.waterlogistics.rendezvous.port.DroneContextUnavailableException 查无执行机台账
     * @throws com.waterlogistics.rendezvous.port.SnapshotUnavailableException     查无快照或归属不符
     * @throws com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException 快照版本不一致
     * @throws IllegalArgumentException 请求或选项非法
     * @throws NullPointerException     请求或选项为 {@code null}
     */
    public RendezvousRecalcResult recompute(RendezvousRecalcRequest request, RecalcOptions options) {
        return this.recalcService.recompute(request, options);
    }





    /**
     * 航迹级空域冲突二查——M2 第 5 步补做（《22-接口契约》§5），只给检测结论；
     * 错峰挪时刻归 D 功能 7（《81-B组算法方案》§3.4 划界）。
     *
     * @param route  被查航迹（checksumVersion 取它的 routeVersion）
     * @param other  对照航迹
     * @param params 间隔与采样参数（调用方注入）
     * @return 冲突结论；绕行几何首版留桩为 null
     * @throws IllegalArgumentException 航迹航点不足或非严格递增
     * @throws NullPointerException     入参为 {@code null}
     */
    public B_ConflictResult detectConflict(B_FlyableRoute route, B_FlyableRoute other,
                                           ConflictParams params) {
        return TrackConflictChecker.detect(route, other, params);
    }





    /**
     * 起飞时刻回填（算式部分）——M2 第 5 步 · 航线确认与起飞时刻确定（《22-接口契约》§5）；
     * 只算不定案：时刻定案、回写占位与二次校验归 D 功能 7（《81-B组算法方案》§3.3 划界）。
     *
     * @param cmd 回填入参（会面时刻、精算时长、当前时刻、时刻精度粒度全由调用方注入）
     * @return 算式结果（反推起飞时刻＋可行性；不可行走「早于当前时刻」）
     * @throws IllegalArgumentException 回填入参非法
     */
    public B_TakeoffTimeResult computeTakeoffTime(TakeoffTimeCommand cmd) {
        return TakeoffTimeCalculator.compute(cmd);
    }





    /**
     * 末段会面与交接判定——M2 第 7 步（《22-接口契约》§5）：四子阶段判据推进简化版，
     * 产出交接完成确认（成功／失败上报）；不自行决策兜底（《81-B组算法方案》§3.6）。
     *
     * @param cmd 判定入参（计划会面基准＋末段采样＋阈值，全由调用方注入）
     * @return 交接完成确认（失败含「重试／悬停等待」的简化结论：样本耗尽即放弃）
     * @throws IllegalArgumentException 判定入参非法
     * @throws NullPointerException     入参为 {@code null}
     */
    public B_HandoverConfirmation judgeHandover(HandoverCommand cmd) {
        return HandoverJudge.judge(cmd);
    }
}
