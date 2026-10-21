package com.waterlogistics.rendezvous.conflict;

/**
 * 冲突二查参数——三项均由调用方注入（来源 {@code sys_param}；水平／垂直安全间隔与采样
 * 步长的建议初值已登记《37-M6-数据库》§5.14.1，B 组自定项 B-10），本层不硬编码
 * （《81-B组算法方案》§2.4 硬约束 2）。
 *
 * @param horizontalSepM 水平安全间隔，米
 * @param verticalSepM   垂直安全间隔，米
 * @param sampleStepMs   冲突时间采样步长，毫秒（10–600000；过小造成迭代膨胀，过大降低检出分辨率）
 */
public record ConflictParams(

        double horizontalSepM,

        double verticalSepM,

        long sampleStepMs) {

    private static final long MIN_SAMPLE_STEP_MS = 10L;

    private static final long MAX_SAMPLE_STEP_MS = 600_000L;





    @SuppressWarnings("MissingJavadoc")
    public ConflictParams {
        if (!Double.isFinite(horizontalSepM) || !(horizontalSepM > (double) 0)) {
            throw new IllegalArgumentException("水平安全间隔必须为正的有限数，实际 " + horizontalSepM);
        }
        if (!Double.isFinite(verticalSepM) || !(verticalSepM > (double) 0)) {
            throw new IllegalArgumentException("垂直安全间隔必须为正的有限数，实际 " + verticalSepM);
        }
        if (sampleStepMs < ConflictParams.MIN_SAMPLE_STEP_MS || sampleStepMs > ConflictParams.MAX_SAMPLE_STEP_MS) {
            throw new IllegalArgumentException("采样步长须在 [" + ConflictParams.MIN_SAMPLE_STEP_MS + ", "
                                                       + ConflictParams.MAX_SAMPLE_STEP_MS + "] ms 内，实际 " + sampleStepMs);
        }
    }
}
