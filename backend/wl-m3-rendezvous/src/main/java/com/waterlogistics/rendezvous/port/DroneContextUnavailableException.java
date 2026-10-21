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
     * 触发本异常的执行机实例（原样保留，可能为 null/空白；结构化日志请用 {@link #getDisplayDroneId()}）。
     */
    @Getter
    private final String droneId;





    /**
     * @param droneId 执行机实例
     */
    public DroneContextUnavailableException(String droneId) {
        this(droneId, null);
    }





    /**
     * @param droneId 执行机实例
     * @param cause   底层技术原因（可为 {@code null}）
     */
    public DroneContextUnavailableException(String droneId, Throwable cause) {
        super("查无执行机台账，拒绝求解：机 " + DroneContextUnavailableException.sanitize(droneId), cause);
        this.droneId = droneId;
    }





    /**
     * 适合日志展示的执行机标识，仅把 null/空白替换为占位符，不做掩码。
     *
     * @return 标识原值；为空/空白时为 {@code <unknown>}
     */
    public String getDisplayDroneId() {
        return DroneContextUnavailableException.sanitize(this.droneId);
    }





    private static String sanitize(String droneId) {
        return droneId == null || droneId.isBlank() ? "<unknown>" : droneId;
    }
}
