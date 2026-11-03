package com.waterlogistics.dispatch.homecoming;

import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.rendezvous.trajectory.GenerateRouteCommand;

/**
 * 返航航迹生成端口。M2 返航复核只依赖本接口，不把 M3 门面类型写进本模块的公开签名。
 *
 * <p>与 {@link com.waterlogistics.dispatch.rendezvous.RendezvousPort}（会面求解）分开成
 * 独立端口：两者职责不同，且 {@code RendezvousPort} 是 {@code @FunctionalInterface}，
 * 不能为它加第二个方法。
 *
 * <p>实现由 {@code wl-app} 提供，内部转调 {@code RendezvousFacade#generateTrack}。
 */
@FunctionalInterface
public interface HomecomingTrackPort {

    /**
     * 生成返航航迹。
     *
     * @param cmd 航迹生成命令（起终点、巡航参数、能耗率、风险等）
     * @return 可飞行航迹（含精算时长、精算能耗、路径风险）
     */
    B_FlyableRoute generateTrack(GenerateRouteCommand cmd);
}
