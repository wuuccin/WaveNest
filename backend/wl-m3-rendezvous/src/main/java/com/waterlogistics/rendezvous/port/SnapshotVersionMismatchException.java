package com.waterlogistics.rendezvous.port;

import lombok.Getter;



/**
 * 快照版本不一致异常——调用方期望的快照版本与端口取回快照的版本不符时抛出，
 * 一律<b>拒绝求解</b>（《81-B组算法方案》§2.1 硬要求 1、§2.5 校验用入参）。
 *
 * <p>不吞、不退化为「取最新版本继续算」：版本漂移意味着该候选不在本轮调度的同一口径内，
 * 继续求解会拿到版本不一致的轨迹（《32-M3-动态会面》§4.2）。
 */
@SuppressWarnings("UncheckedExceptionClass")
public class SnapshotVersionMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 目标船标识（原样保留，可能为空/空白）
     */
    @Getter
    private final String shipMmsi;

    /**
     * 调用方期望的快照版本（原样保留，可能为空/空白）
     */
    private final String expected;

    /**
     * 端口实际取回的版本（原样保留，可能为空/空白）
     */
    private final String actual;





    /**
     * @param shipMmsi 目标船标识
     * @param expected 调用方期望的快照版本
     * @param actual   端口实际取回的版本
     */
    public SnapshotVersionMismatchException(String shipMmsi, String expected, String actual) {
        this(shipMmsi, expected, actual, null);
    }





    /**
     * @param shipMmsi 目标船标识
     * @param expected 调用方期望的快照版本
     * @param actual   端口实际取回的版本
     * @param cause    导致版本校验失败的底层原因
     */
    public SnapshotVersionMismatchException(String shipMmsi, String expected, String actual,
                                            Throwable cause) {
        super("快照版本不一致，拒绝求解：船 "
                      + (shipMmsi == null || shipMmsi.isBlank() ? "<unknown>" : shipMmsi)
                      + "，期望版本 " + (expected == null || expected.isBlank() ? "<none>" : expected)
                      + "，实际版本 " + (actual == null || actual.isBlank() ? "<none>" : actual), cause);
        this.shipMmsi = shipMmsi;
        this.expected = expected;
        this.actual = actual;
    }





    /**
     * 返回调用方期望的快照版本。
     *
     * @return 期望版本；空/空白时归一为 {@code <none>}，便于日志与排障
     */
    public String getExpected() {
        return this.expected == null || this.expected.isBlank() ? "<none>" : this.expected;
    }





    /**
     * 返回端口实际取回的版本。
     *
     * @return 实际版本；空/空白时归一为 {@code <none>}，便于日志与排障
     */
    public String getActual() {
        return this.actual == null || this.actual.isBlank() ? "<none>" : this.actual;
    }
}
