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

    public B_TakeoffTimeResult {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        if (comboId.isBlank()) {
            throw new IllegalArgumentException("comboId 不能为空白字符串");
        }
        if (feasible && takeoffT < EpochMillis.MIN_VALID_MS) {
            throw new IllegalArgumentException(
                    "可行结果的起飞时刻须为 Unix 毫秒值（疑似误传秒级时间戳），实际 " + takeoffT);
        }
        if (tauCoarseUsed <= 0) {
            throw new IllegalArgumentException("本次反推所用时长必须为正，实际 " + tauCoarseUsed);
        }
        if (feasible && infeasibleReason != null) {
            throw new IllegalArgumentException("可行结果不应携带不可行原因");
        }
        if (!feasible && infeasibleReason == null) {
            throw new IllegalArgumentException("不可行结果必须给出不可行原因");
        }
    }
}
