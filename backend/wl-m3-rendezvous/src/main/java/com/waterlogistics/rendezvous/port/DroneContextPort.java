package com.waterlogistics.rendezvous.port;

/**
 * 执行机台账端口——6a 重算的 {@code droneId → 机型速度／归属机巢／当前组合} 反查
 * （《81-B组算法方案》§2.5）。与 {@link TrajectorySnapshotPort} 同模式：接口由 M3
 * 声明、实现接线在装配层——M3 不读库。
 *
 * <p>首版实现接 M2／装配层的内存台账桩；精算版可由实现改走 {@code wl-storage}
 * 的 {@code drone}／{@code nest} 台账（M3 本就依赖 storage 模块，接口不变）。
 *
 * <p>本接口<b>纯读</b>：不落库、无副作用。
 */
public interface DroneContextPort {

    /**
     * 按执行机实例反查上下文。
     *
     * @param droneId 执行机实例（调用方保证非空、非空白；{@code RendezvousRecalcService} 已在入口校验）
     * @return 反查结果；查无该机时为 {@code null}，由调用方按「拒绝求解」处理
     */
    DroneContext resolve(String droneId);
}
