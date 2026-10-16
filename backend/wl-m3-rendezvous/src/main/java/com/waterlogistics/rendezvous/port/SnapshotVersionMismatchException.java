package com.waterlogistics.rendezvous.port;

/**
 * 快照版本不一致异常——调用方期望的快照版本与端口取回快照的版本不符时抛出，
 * 一律<b>拒绝求解</b>（《81-B组算法方案》§2.1 硬要求 1、§2.5 校验用入参）。
 *
 * <p>不吞、不退化为「取最新版本继续算」：版本漂移意味着该候选不在本轮调度的同一口径内，
 * 继续求解会拿到版本不一致的轨迹（《32-M3-动态会面》§4.2）。
 */
public class SnapshotVersionMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param shipMmsi 目标船标识
     * @param expected 调用方期望的快照版本
     * @param actual   端口实际取回的版本
     */
    public SnapshotVersionMismatchException(String shipMmsi, String expected, String actual) {
        super("快照版本不一致，拒绝求解：船 "
                + (shipMmsi == null || shipMmsi.isBlank() ? "<unknown>" : shipMmsi)
                + "，期望版本 " + expected + "，实际版本 " + actual);
    }
}
