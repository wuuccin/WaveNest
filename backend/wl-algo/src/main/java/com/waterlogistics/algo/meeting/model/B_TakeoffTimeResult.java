package com.waterlogistics.algo.meeting.model;

import java.util.Objects;

/**
 * 起飞时刻算式结果 ＋ 是否不可行（{@code B_} 前缀＝算法层 B 组产出）——字段照
 * 《81-B组算法方案》§2.3 第（3）项一字不改。
 *
 * <p>边界（§3.3）：本结果为「算式结果」——<b>实际起飞时刻由 D 功能 7 定案</b>，
 * 回写占位与二次资源校验亦归 D；C 组的最迟起飞时刻仅中间量，三方不得各算各的。
 *
 * <p>{@code tauCoarseUsed} 口径说明：字段名照 §2.3 保留；§3.3 要点 5 规定反推用
 * <b>精算</b>飞行时长（两笔时长账不混，见 §2.3 口径 3），故本字段值＝<b>本次反推
 * 实际所用的时长</b>（首版＝精算 τ_fine）。字段命名与正文口径的出入已报收口方备案。
 *
 * @param comboId          绑定的候选组合标识
 * @param takeoffT         反推起飞时刻，Unix 毫秒；不可行时仅供算术留痕，可能为负，不得当作有效时刻
 * @param feasible         是否可行
 * @param infeasibleReason 不可行原因（feasible=false 时给出，否则 null）
 * @param tauCoarseUsed    本次反推实际所用时长，秒（首版＝精算 τ_fine，见上述口径说明）
 */
public record B_TakeoffTimeResult(

        String comboId,

        long takeoffT,

        boolean feasible,

        TakeoffInfeasibleReason infeasibleReason,

        int tauCoarseUsed) {

    private static final int MAX_TAU_SEC = Integer.MAX_VALUE / 1000;

    public B_TakeoffTimeResult {
        if (comboId == null || comboId.isBlank()) {
            throw new IllegalArgumentException("comboId 不能为空或空白字符串");
        }
        comboId = comboId.strip();
        if (feasible && !EpochMillis.isValid(takeoffT)) {
            throw new IllegalArgumentException(
                    "可行结果的起飞时刻须为 Unix 毫秒值，实际 " + takeoffT);
        }
        if (tauCoarseUsed <= 0 || tauCoarseUsed > MAX_TAU_SEC) {
            throw new IllegalArgumentException("本次反推所用时长须在 [1, " + MAX_TAU_SEC
                    + "] 秒内，实际 " + tauCoarseUsed);
        }
        if (feasible && infeasibleReason != null) {
            throw new IllegalArgumentException("可行结果不应携带不可行原因");
        }
        if (!feasible && infeasibleReason == null) {
            throw new IllegalArgumentException("不可行结果必须给出不可行原因");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof B_TakeoffTimeResult that)) {
            return false;
        }
        return this.feasible == that.feasible
                && this.tauCoarseUsed == that.tauCoarseUsed
                && Objects.equals(this.comboId, that.comboId)
                && this.infeasibleReason == that.infeasibleReason
                && (!this.feasible || this.takeoffT == that.takeoffT);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(this.comboId, Boolean.valueOf(this.feasible),
                this.infeasibleReason, Integer.valueOf(this.tauCoarseUsed));
        return this.feasible ? 31 * result + Long.hashCode(this.takeoffT) : result;
    }
}
