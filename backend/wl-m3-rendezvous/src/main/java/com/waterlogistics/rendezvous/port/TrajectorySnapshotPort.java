package com.waterlogistics.rendezvous.port;

import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;



/**
 * 预测快照端口——<b>端口反转</b>：接口由 M3 声明、M2 提供实现、{@code wl-app} 装配接线
 * （《42-包结构设计》§3.3、《22-接口契约》§5 第 2／3 步）。M3 全程不知道 M2 存在。
 *
 * <p>调用契约（《81-B组算法方案》§2.1 硬要求 1）：快照携带版本号，本轮调度内多候选
 * 共用同一版本；上层服务按调用方给出的期望版本比对 {@link ShipTrackSnapshot#version()}，
 * 不一致即<b>拒绝求解</b>并抛 {@link SnapshotVersionMismatchException}。
 *
 * <p>约定：查无时<b>返回 {@code null}</b>——实现<b>不得</b>抛 {@link SnapshotUnavailableException}，
 * 该异常由调用方在拿到 null 后转换抛出；非法入参须显式抛 {@link IllegalArgumentException}；
 * 底层存储读取失败须包装为未检查异常（保留原因），不得伪装成「查无」。返回的快照本身由
 * {@link ShipTrackSnapshot} 保证不可变（构造期防御拷贝），实现只需交出实例，消费方按只读使用。
 *
 * <p>本接口<b>纯读</b>：不落库、不改占位、无状态副作用（《81》§2.4 硬约束 1）。
 */
@FunctionalInterface
public interface TrajectorySnapshotPort {

    /**
     * 取指定船的最新预测快照。
     *
     * @param shipMmsi 目标船标识（调用方保证非空、非空白；违例时实现须显式抛
     *                 {@link IllegalArgumentException}，不得伪装成 {@code null}）
     * @return 该船当前快照；查无此船（或尚无预测）时为 {@code null}，由调用方按「拒绝求解」处理
     * @throws IllegalArgumentException {@code shipMmsi} 为空/空白
     * @throws RuntimeException         底层存储读取失败（实现须包装并保留原因）
     */
    ShipTrackSnapshot latest(String shipMmsi);
}
