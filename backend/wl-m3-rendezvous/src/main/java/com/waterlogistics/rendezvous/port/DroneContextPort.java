package com.waterlogistics.rendezvous.port;

/**
 * 执行机台账端口——6a 重算的 {@code droneId → 机型速度／归属机巢／当前组合} 反查
 * （《81-B组算法方案》§2.5）。与 {@link TrajectorySnapshotPort} 同模式：接口由 M3
 * 声明、实现接线在装配层——M3 不读库。
 *
 * <p>首版实现为 {@code DroneContextAdapter}（装配层）：读 {@code drone}／{@code nest}
 * 台账，巡航速度取 {@code sys_param.droneCruiseSpeedMs} 单值（当前各机型同速）；
 * 按机型区分速度待机型档案落地后由实现补齐，接口约定不变。
 *
 * <p>本接口<b>纯读</b>：不落库、无副作用；返回结果须只读可共享，实现与调用方均不得修改。
 */
public interface DroneContextPort {

    /**
     * 按执行机实例反查上下文。
     *
     * <p>实现侧须对 {@code null}／空白 {@code droneId} 做防御，返回 {@code null} 而非抛异常；
     * 实现<b>不得</b>抛 {@link DroneContextUnavailableException}——该异常由调用方在拿到
     * {@code null} 后转换抛出。
     *
     * @param droneId 执行机实例（调用方保证非空、非空白；{@code RendezvousRecalcService} 已在入口校验）
     * @return 反查结果；查无该机时为 {@code null}，由调用方按「拒绝求解」处理；返回结果只读共享
     */
    DroneContext resolve(String droneId);
}
