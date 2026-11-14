package com.waterlogistics.dispatch.reschedule;

import com.waterlogistics.contract.state.AlertLevel;

/**
 * 在飞安全复核的入参快照（一次告警 → 一次复核，不跨事件累积）。
 *
 * <p><b>风速与电量必须分开传</b>：气象超限告警的 {@code currentValue} 是风速（m/s），
 * 低电量告警的 {@code currentValue} 是电量（%）。{@code HomecomingInputAssembler}
 * 只按低电量取电量，若把本入参交给它反推，气象告警会因取不到电量而整体跳过复核。
 *
 * <p><b>判档不看机型抗风上限</b>：《86-G组算法方案》§扩.5 待定 2 已裁定
 * 「首版统一阈值、不按机型区分」，故本入参<b>没有</b> {@code windLimitMs} 字段。
 * 机型抗风上限用于候选链淘汰与全域熔断，与告警分档是两件事。
 *
 * <p><b>{@code cleared} 为什么要显式传</b>：「继续」档在事件驱动下没有输入——
 * M4 对 CLEARED 状态不发事件（{@code WeatherClearWaker} 类注释），迟滞清除只体现为
 * 告警侧内部状态。故由调用方显式给该位，本批只由单测覆盖，不接生产触发点。
 *
 * @param eventId      告警事件标识，用于与 {@code alert} 台账对齐
 * @param trigger      触发来源；本批固定 {@link InflightSafetyTrigger#AIRSPACE_OR_WEATHER}
 * @param orderId      订单标识；<b>允许为空</b>，为空时由调用方按 {@code droneId} 反查在飞订单
 * @param droneId      无人机标识
 * @param windSpeedMs  告警携带的风速（m/s），须为有限值且不小于零
 * @param alertLevel   告警级别；仅「警告」与「严重」两档参与判档，其余档返回空结论
 * @param durationSec  告警持续时长（秒），小于零按零处理
 * @param observedAtMs 观测时刻（毫秒时间戳），须大于零
 * @param cleared      告警是否已解除（迟滞清除完成）
 */
public record InflightSafetyRequest(
        String eventId,
        InflightSafetyTrigger trigger,
        String orderId,
        String droneId,
        double windSpeedMs,
        AlertLevel alertLevel,
        int durationSec,
        long observedAtMs,
        boolean cleared) {

    public InflightSafetyRequest {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("告警事件标识不得为空：复核结论须能追回原始告警（alert 台账）");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("触发来源不得为空：《84》§7.3 的 trigger_source 必填");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("无人机标识不得为空：复核按无人机组织，缺它无法定位在飞任务");
        }
        // orderId 可空：告警侧不保证带单号，允许调用方按 droneId 反查在飞订单后再判档。
        // 不做「空串归一为 null」——空串是调用方 bug，留给判档前的可空判断自然暴露。
        if (durationSec < 0) {
            durationSec = 0;
        }
    }

    /**
     * 该级别是否属于气象超限的告警两档。
     *
     * <p>{@code Info} 与 {@code null} 都不参与判档：前者是提示，后者是告警侧漏填。
     * 两者都返回空结论而不是「继续」——「没判」不能伪装成「判过且安全」。
     *
     * @param level 告警级别，可空
     * @return 为「警告」或「严重」时返回 {@code true}
     */
    public static boolean isWeatherAlertLevel(AlertLevel level) {
        return level == AlertLevel.WARN || level == AlertLevel.CRITICAL;
    }
}
