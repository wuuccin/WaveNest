package com.waterlogistics.app.config;

import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
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
}