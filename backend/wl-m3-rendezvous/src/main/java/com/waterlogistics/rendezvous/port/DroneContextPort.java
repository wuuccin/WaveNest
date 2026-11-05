package com.waterlogistics.rendezvous.port;

/**
 * 执行机台账端口——6a 重算的 {@code droneId → 机型速度／归属机巢／当前组合} 反查
 * （《81-B组算法方案》§2.5）。与 {@link TrajectorySnapshotPort} 同模式：接口由 M3
 * 声明、实现接线在装配层——M3 不读库。
 *
 * <p>首版实现为 M2 侧 {@code DroneContextAdapter}，由装配层接线：读 {@code drone}／{@code nest}
 * 台账，巡航速度取 {@code sys_param.droneCruiseSpeedMs} 单值（当前各机型同速）；
 * 按机型区分速度待机型档案落地后由实现补齐，接口约定不变。
 *
 * <p>本接口<b>纯读</b>：不落库、无副作用；返回结果须只读可共享，实现与调用方均不得修改。
 */
@FunctionalInterface
public interface DroneContextPort {

    /**
     * 按执行机实例反查上下文。
     *
     * <p>{@code droneId} 非空、非空白由调用方先校验（请求对象构造期），实现仍须做一次
     * 显式防御：违例时抛 {@link IllegalArgumentException}，<b>不得</b>把非法入参伪装成
     * {@code null}。{@code null} 返回值不区分「查无该机」「无归属机巢」「机巢坐标缺失」
     * 三种成因，调用方不得假定单一原因。实现不得抛 {@link DroneContextUnavailableException}，
     * 该异常由调用方在拿到 {@code null} 后转换抛出；底层存储读取失败属基础设施故障，
     * 实现须包装为未检查异常并保留原因，调用方不得当作「查无该机」。
     *
     * @param droneId 执行机实例（消费侧保证非空、非空白——请求对象构造期已校验）
     * @return 反查结果；查无该机、或缺归属机巢/机巢坐标时为 {@code null}，由调用方按「拒绝求解」处理；返回结果只读共享
     * @throws IllegalArgumentException {@code droneId} 为空/空白（调用方缺陷，实现须显式抛出）
     * @throws RuntimeException         底层存储读取失败（实现须包装并保留原因，禁止转为 {@code null}）
     */
    DroneContext resolve(String droneId);
}
