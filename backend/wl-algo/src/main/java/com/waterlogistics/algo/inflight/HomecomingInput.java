package com.waterlogistics.algo.inflight;

import com.waterlogistics.contract.rest.GeoPoint;

/**
 * 功能10「返航复核与归巢资源判定」入口输入快照，字段照《84-E组算法方案》§7.3 输入表。
 *
 * <p>§7.3 最小必填集原文：「任务ID、机ID、原巢ID、位置、剩余电量、风况快照、中止原因、
 * event_id。其余缺省时按降级口径处理——无风况快照则按固定裕度系数 k 判，无目标点则用
 * 当前位置作返程起点。」
 *
 * <p>电量口径唯一（§4.1／§7.3）：取 cleaned_state.battery，百分比、满电＝100，剩余分钟不并入。
 *
 * <p><b>返程电量基线 {@code homewardEnergyBaselinePercent} 定为必填</b>——§4.1 输入表列了它
 * （来源：第 5 步精算／B 组），但 §7.3「最小必填集」原文漏列。第一级 A1 不等式
 * （{@code 实时电量 ≥ 返程基线 ＋ 储备裕度}）没有它无从判断，缺了只能默认「电量够」而静默放行，
 * 属安全侧不可接受的退化，故本类型要求必填、缺失即报错。该矛盾已回填《84》§7.3。
 *
 * <p><b>风况快照的可空性</b>：§7.3 最小必填集列了它，但同段又规定「无风况快照则按固定裕度系数 k 判」，
 * 两句并存意味着<b>允许缺省</b>。本类型据此定为可空；判据侧只在为空时才启用 k，
 * 避免与 §7.7 硬约束 2「风况只经返程基线进入判定，不得单独成维或单独调阈值」冲突。
 */
public record HomecomingInput(
        String eventId,
        String triggerSource,
        String taskId,
        String orderId,
        String droneId,
        String homeNestId,
        GeoPoint position,
        double batteryPercent,
        double homewardEnergyBaselinePercent,
        boolean cargoOnboard,
        GeoPoint currentTargetPoint,
        WindSnapshot windSnapshot,
        String abortReason,
        long eventTs,
        Boolean commCovered) {

    public HomecomingInput {
        requireText(eventId, "eventId");
        requireText(triggerSource, "triggerSource");
        requireText(taskId, "taskId");
        requireText(orderId, "orderId");
        requireText(droneId, "droneId");
        requireText(homeNestId, "homeNestId");
        requireText(abortReason, "abortReason");
        if (position == null) {
            throw new IllegalArgumentException("缺 position：§7.3 最小必填集含「位置」");
        }
        if (!Double.isFinite(batteryPercent) || batteryPercent < 0 || batteryPercent > 100) {
            throw new IllegalArgumentException("剩余电量须为 0-100 百分比，实测=" + batteryPercent);
        }
        if (!Double.isFinite(homewardEnergyBaselinePercent) || homewardEnergyBaselinePercent < 0) {
            throw new IllegalArgumentException("返程电量基线须为非负有限值，实测="
                    + homewardEnergyBaselinePercent);
        }
    }

    /** 返程起点：§7.3「无目标点则用当前位置作返程起点」 */
    public GeoPoint returnStartPoint() {
        return currentTargetPoint == null ? position : currentTargetPoint;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不可为空，§7.3 最小必填集要求其在位");
        }
    }
}