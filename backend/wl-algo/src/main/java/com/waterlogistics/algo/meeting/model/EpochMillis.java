package com.waterlogistics.algo.meeting.model;

/**
 * Unix 毫秒时刻的共用合法下界，供各层入参与结果模型统一引用，避免魔数漂移。
 */
public final class EpochMillis {

    /** 合法 Unix 毫秒下界：2001-09-09，低于此值基本可判为秒级时间戳误传 */
    public static final long MIN_VALID_MS = 1_000_000_000_000L;

    private EpochMillis() {
    }
}