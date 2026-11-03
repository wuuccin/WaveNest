package com.waterlogistics.dispatch.plan;

import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;

/**
 * 航迹生成端口。M2 只依赖本接口，不把 M3 门面类型写进本模块的公开签名。
 *
 * <p>实现由 {@code wl-app} 提供，内部转调门面的单段航迹生成方法；
 * 三段航迹由 {@link MissionTrackAssembler} 按去程、末段交接、返程依次调用本端口拼装。
 */
@FunctionalInterface
public interface MissionTrackPort {

    /**
     * 生成一段可飞航迹。
     *
     * @param cmd 单段航迹生成入参
     * @return 可飞航迹
     */
    B_FlyableRoute generateTrack(GenerateRouteCommand cmd);
}
