package com.waterlogistics.rendezvous.port;

/**
 * 预测快照缺失异常——端口查无该船快照（尚未生成预测）时抛出，一律<b>拒绝求解</b>。
 *
 * <p>与 {@link SnapshotVersionMismatchException} 的分工：本异常是「数据没有」，
 * 那一个是「数据对不上」；两者都属数据前置缺失导致的拒绝，<b>不是算法无解</b>
 * （算法无解走 {@code B_RendezvousSolution.hasSolution=false}）。
 */
public class SnapshotUnavailableException extends RuntimeException {

    /**
     * @param shipMmsi 目标船标识
     */
    public SnapshotUnavailableException(String shipMmsi) {
        super("查无预测快照，拒绝求解：船 " + shipMmsi);
    }
}
