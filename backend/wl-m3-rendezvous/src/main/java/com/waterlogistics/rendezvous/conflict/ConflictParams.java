package com.waterlogistics.rendezvous.conflict;

/**
 * 冲突二查参数——三项均由调用方注入（来源 {@code sys_param}；水平／垂直安全间隔与采样
 * 步长的建议初值已登记《37-M6-数据库》§5.14.1，B 组自定项 B-10），本层不硬编码
 * （《81-B组算法方案》§2.4 硬约束 2）。
 *
 * @param horizontalSepM 水平安全间隔，米
 * @param verticalSepM   垂直安全间隔，米
 * @param sampleStepMs   冲突时间采样步长，毫秒（正数；过大值降低检出分辨率——短于一步的冲突可能漏报，上界由调用方按最短可检冲突口径控制）
 */
public record ConflictParams(

        double horizontalSepM,

        double verticalSepM,

        long sampleStepMs) {

    @SuppressWarnings("MissingJavadoc")
    public ConflictParams {
        if (!Double.isFinite(horizontalSepM) ||
                !Double.isFinite(verticalSepM) ||
                !(horizontalSepM > (double) 0) ||
                !(verticalSepM > (double) 0)) {
            throw new IllegalArgumentException(
                    "安全间隔必须为正的有限数，实际 horizontalSepM=" + horizontalSepM + ", verticalSepM=" + verticalSepM
            );
        }
        if (sampleStepMs <= 0L) {
            throw new IllegalArgumentException("采样步长必须为正，实际 " + sampleStepMs);
        }
    }
}
