package com.waterlogistics.rendezvous.port;

import lombok.Getter;



/**
 * 预测快照缺失异常——端口查无该船快照（尚未生成预测）时抛出，一律<b>拒绝求解</b>。
 *
 * <p>与 {@link SnapshotVersionMismatchException} 的分工：本异常是「数据没有」，
 * 那一个是「数据对不上」；两者都属数据前置缺失导致的拒绝，<b>不是算法无解</b>
 * （算法无解走 {@code B_RendezvousSolution.hasSolution=false}）。
 */
@SuppressWarnings("UncheckedExceptionClass")
public class SnapshotUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;





    /**
     * 目标船标识（原样保留，可能为空/空白）
     */
    @Getter
    private final String shipMmsi;





    /**
     * @param shipMmsi 目标船标识
     */
    public SnapshotUnavailableException(String shipMmsi) {
        this(shipMmsi, "查无预测快照，拒绝求解");
    }





    private SnapshotUnavailableException(String shipMmsi, String reason) {
        super(reason + "：船 " + (shipMmsi == null || shipMmsi.isBlank() ? "<unknown>" : shipMmsi));
        this.shipMmsi = shipMmsi;
    }





    /**
     * 快照存在但归属与目标船不符。
     *
     * @param shipMmsi 目标船标识
     * @return 归属不符异常（与「查无快照」区分，避免误导排障）
     */
    public static SnapshotUnavailableException ownershipMismatch(String shipMmsi) {
        return new SnapshotUnavailableException(shipMmsi, "快照归属与目标船不符，拒绝求解");
    }
}
