package com.waterlogistics.algo.meeting.model;

/**
 * Unix 毫秒时刻的共用合法下界，供各层入参与结果模型统一引用，避免魔数漂移。
 */
public final class EpochMillis {

    /**
     * 合法 Unix 毫秒下界：2001-09-09T01:46:40Z，低于此值基本可判为秒级时间戳误传。
     * 合法值需满足 {@code timestamp >= MIN_VALID_MS}。
     */
    public static final long MIN_VALID_MS = 1_000_000_000_000L;

    /** 合法 Unix 毫秒上界：2100-01-01T00:00:00Z，防止时刻加法溢出 */
    public static final long MAX_VALID_MS = 4_102_444_800_000L;

    /**
     * 统一判定入口，避免各调用方在 {@code >} 与 {@code >=} 之间产生分歧。
     *
     * @param timestampMs 待判定的 Unix 毫秒时刻
     * @return 是否落在 [MIN_VALID_MS, MAX_VALID_MS]
     */
    public static boolean isValid(long timestampMs) {
        return timestampMs >= MIN_VALID_MS && timestampMs <= MAX_VALID_MS;
    }

    private EpochMillis() {
    }
}