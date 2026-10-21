package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.rendezvous.port.SnapshotUnavailableException;
import com.waterlogistics.rendezvous.port.SnapshotVersionMismatchException;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;

import java.util.Objects;




/**
 * 快照版本闸门——solve 与 recompute 共用的「取快照 → 缺失即拒 → 版本不一致即拒」取数逻辑
 * （《81-B组算法方案》§2.1 硬要求 1：本轮调度多候选须共用同一版本）。
 *
 * <p>端口实现约定「查无返回 {@code null}、不抛异常」，由本闸门统一把 {@code null} 与
 * 版本漂移分别转换为 {@link SnapshotUnavailableException}、{@link SnapshotVersionMismatchException}。
 */
final class SnapshotGate {

    private SnapshotGate() {
    }





    /**
     * 自端口取快照并过版本闸门。
     *
     * @param port            轨迹快照端口
     * @param shipMmsi        目标船标识
     * @param expectedVersion 调用方期望的快照版本（一致性锚）
     * @return 版本一致的快照（只读可共享，消费方不得修改）
     * @throws IllegalArgumentException         目标船标识或期望版本为空/空白——调用方错误
     * @throws SnapshotUnavailableException     查无该船快照或快照归属与目标船不符——拒绝求解
     * @throws SnapshotVersionMismatchException 期望版本与快照实际版本不一致——拒绝求解
     */
    static ShipTrackSnapshot fetchVerified(TrajectorySnapshotPort port, String shipMmsi, String expectedVersion) {
        Objects.requireNonNull(port, "轨迹快照端口不能为空");
        if (expectedVersion == null || expectedVersion.isBlank()) {
            throw new IllegalArgumentException("期望快照版本不能为空");
        }
        if (shipMmsi == null || shipMmsi.isBlank()) {
            throw new IllegalArgumentException("目标船标识不能为空");
        }
        ShipTrackSnapshot snapshot = port.latest(shipMmsi);
        if (snapshot == null) {
            throw new SnapshotUnavailableException(shipMmsi);
        }
        if (!Objects.equals(shipMmsi, snapshot.shipMmsi())) {
            throw SnapshotUnavailableException.ownershipMismatch(shipMmsi, snapshot.shipMmsi());
        }
        if (snapshot.version() == null || !Objects.equals(expectedVersion, snapshot.version())) {
            throw new SnapshotVersionMismatchException(shipMmsi, expectedVersion, snapshot.version());
        }
        return snapshot;
    }
}
