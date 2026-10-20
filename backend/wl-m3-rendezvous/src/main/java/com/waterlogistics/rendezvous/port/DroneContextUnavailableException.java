package com.waterlogistics.rendezvous.port;

import lombok.Getter;



/**
 * 执行机台账缺失异常——6a 重算按 {@code droneId} 反查不到机型／归属机巢时抛出，
 * 一律<b>拒绝求解</b>（数据前置缺失，不是算法无解）。
 */
@SuppressWarnings("UncheckedExceptionClass")
public class DroneContextUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 触发本异常的执行机实例（原样保留，可能为 null/空白；日志请用 {@link #getMessage()}）。
     */
    @Getter
    private final String droneId;





    /**
     * @param droneId 执行机实例
     */
    public DroneContextUnavailableException(String droneId) {
        super("查无执行机台账，拒绝求解：机 " + (droneId == null || droneId.isBlank() ? "<unknown>" : droneId));
        this.droneId = droneId;
    }
}
