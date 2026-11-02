package com.waterlogistics.dispatch.rendezvous;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;

/**
 * 会面求解端口。M2 只依赖本接口，不把 M3 的门面类型写进本模块的公开签名。
 *
 * <p>实现由 {@code wl-app} 提供，内部转调 {@code RendezvousFacade}；
 * 这样 M2 与 M3 的耦合仍只发生在门面一处，但 M2 的构造器与测试桩不必再引用门面类。
 */
@FunctionalInterface
public interface RendezvousPort {

    /**
     * 为某候选组合求解会面点。
     *
     * @param cmd 组合级求解命令
     * @return 会面点解；无解时返回 {@code hasSolution=false}
     */
    B_RendezvousSolution solve(RendezvousCommand cmd);
}
