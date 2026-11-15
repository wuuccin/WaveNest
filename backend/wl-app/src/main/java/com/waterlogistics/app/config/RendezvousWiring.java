package com.waterlogistics.app.config;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.dispatch.homecoming.HomecomingTrackPort;
import com.waterlogistics.dispatch.plan.MissionTrackPort;
import com.waterlogistics.dispatch.rendezvous.NestLookup;
import com.waterlogistics.dispatch.rendezvous.RendezvousPort;
import com.waterlogistics.dispatch.rendezvous.RendezvousVerificationPort;
import com.waterlogistics.rendezvous.conflict.ConflictParams;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.handover.HandoverCommand;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.trajectory.TakeoffTimeCommand;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.repository.NestMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 动态会面装配（收口方维护，2026-10-17 补）。
 *
 * <p><b>为什么放在这里</b>：M2 → M3 是《42-包结构设计》§二铁律 2 允许的唯一
 * 编译期跨模块调用方向，且只能调 {@link RendezvousFacade}。但 {@code RendezvousFacade}
 * 本身是 M3 的<b>普通类</b>（M3 不引 Spring，与 {@code wl-algo} 同纪律），
 * 两个端口的实现在 M2（M2 侧仅有的两个出口：
 * {@code TrajectorySnapshotAdapter} 与 {@code DroneContextAdapter}）。
 * 三者跨了两个模块，<b>只有本装配层能把它们接起来</b>——
 * 与 {@code TrajectorySnapshotAdapter} javadoc 里
 * 「{@code wl-app} 装配（new RendezvousFacade(adapter, droneContextPort)）」
 * 的约定一致，此处只是把它落成代码。
 *
 * <p><b>纪律</b>：M2 里<b>不得</b>再出现 {@code new RendezvousFacade(...)}
 * 或任何对 M3 具体实现类的引用，只能注入本 Bean；
 * M3 侧也<b>不得</b>反向依赖 M2。两个端口的接口都声明在 M3，
 * M3 全程不需要知道 M2 存在。
 */
@Configuration
public class RendezvousWiring {

    /**
     * 动态会面门面：会面点求解、航迹生成、6a 重算、空域二查、起飞时刻回填、末段交接判定。
     *
     * @param snapshotPort 船侧预测快照（端口由 M3 声明、实现由 M2 提供）
     * @param droneContext 执行机台账反查（端口由 M3 声明、实现由 M2 提供）
     */
    @Bean
    public RendezvousFacade rendezvousFacade(TrajectorySnapshotPort snapshotPort,
            DroneContextPort droneContextPort) {
        return new RendezvousFacade(snapshotPort, droneContextPort);
    }

    /**
     * 会面求解端口：M2 只依赖 M2 自己的接口，实现转调 M3 门面。
     *
     * @param facade 动态会面门面
     * @return 会面求解端口的实现
     */
    @Bean
    public RendezvousPort rendezvousPort(RendezvousFacade facade) {
        return facade::solve;
    }

    /**
     * 会面定案校验端口：起飞时刻回填、空域冲突二查、末段交接判定，实现全部转调 M3 门面。
     *
     * <p>与 {@link #rendezvousPort} 分开成独立 Bean：三项能力各有独立入参类型，
     * 且由 {@code DispatchPlanVerifier} 按固定顺序调用；M2 侧不出现门面类型。
     *
     * @param facade 动态会面门面
     * @return 定案校验端口的实现
     */
    @Bean
    public RendezvousVerificationPort rendezvousVerificationPort(RendezvousFacade facade) {
        return new RendezvousVerificationPort() {
            @Override
            public B_TakeoffTimeResult computeTakeoffTime(TakeoffTimeCommand cmd) {
                return facade.computeTakeoffTime(cmd);
            }

            @Override
            public B_ConflictResult detectConflict(B_FlyableRoute route, B_FlyableRoute other,
                    ConflictParams params) {
                return facade.detectConflict(route, other, params);
            }

            @Override
            public B_HandoverConfirmation judgeHandover(HandoverCommand cmd) {
                return facade.judgeHandover(cmd);
            }
        };
    }

    /**
     * 返航航迹生成端口：M2 返航复核只依赖 M2 自己的接口，实现转调 M3 门面。
     *
     * @param facade 动态会面门面
     * @return 返航航迹端口的实现
     */
    @Bean
    public HomecomingTrackPort homecomingTrackPort(RendezvousFacade facade) {
        return facade::generateTrack;
    }

    /**
     * 航迹生成端口：M2 编排层只依赖 M2 自己的接口，实现转调 M3 门面的单段航迹生成方法。
     *
     * @param facade 动态会面门面
     * @return 航迹生成端口的实现
     */
    @Bean
    public MissionTrackPort missionTrackPort(RendezvousFacade facade) {
        return facade::generateTrack;
    }

    /**
     * 机巢坐标查询端口：会面求解按货物所在巢取起点时使用。
     *
     * @param nestMapper 机巢表
     * @return 只读查询实现；查无或坐标缺失返回 {@code null}
     */
    @Bean
    public NestLookup nestLookup(NestMapper nestMapper) {
        return nestId -> {
            NestEntity nest = nestMapper.selectById(nestId);
            if (nest == null || nest.getLon() == null || nest.getLat() == null) {
                return null;
            }
            double lon = nest.getLon().doubleValue();
            double lat = nest.getLat().doubleValue();
            if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0
                    || !Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
                return null;
            }
            return new NestLookup.NestPosition(nest.getNestId(), lon, lat);
        };
    }
}