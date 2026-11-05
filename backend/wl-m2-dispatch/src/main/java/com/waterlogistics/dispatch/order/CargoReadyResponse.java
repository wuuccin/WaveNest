package com.waterlogistics.dispatch.order;

import com.waterlogistics.contract.state.GoodsStatus;

/**
 * {@code POST /api/orders/{orderId}/cargo-ready} 的响应体（M2 · 卢肄文 · 11-05 序号 1）。
 *
 * <p><b>为什么把「本次是否真的改了库」单独作为一个字段</b>：本端点的契约是<b>幂等</b>——
 * 货主重试、前端双击、MQ 重投都会重复调用，重复调用<b>不能</b>被当成「又到货一次」。
 * 如果只回 {@code accepted=true}，调用方无法区分「这次改成已就绪」与「上次就改好了」；
 * 而下游（序号 2 的「货到巢关」放行）恰恰要按「首次就绪」触发一次推进、重复就绪不再触发。
 * 故用 {@code changed} ＋ {@code reasonCode=ALREADY_READY} 把两种情况分开表达，
 * 而不是靠调用方比对时间戳或自行查库。
 *
 * @param accepted    是否完成登记：货已就绪（含本次改成与上次已改好）为 {@code true}；
 *                    订单无货物记录时为 {@code false}
 * @param orderId     订单标识（回显归一后的值）
 * @param goodsStatus 该订单货物<b>处理后</b>的状态；取不到时为 {@code null}
 * @param changed     本次调用是否真的改了库（{@code false} ＝ 幂等命中）
 * @param updatedRows 本次更新的货物行数（多货行订单下可能 &gt;1）
 * @param reasonCode  幂等或拒绝的原因码：{@code ALREADY_READY}／{@code GOODS_NOT_FOUND}／
 *                    {@code ORDER_ID_MISSING}／{@code GOODS_STATE_FROZEN}；正常改动时为 {@code null}
 * @param message     原因说明（中文，供前端直接展示）
 */
public record CargoReadyResponse(
        boolean accepted,
        String orderId,
        String goodsStatus,
        boolean changed,
        int updatedRows,
        String reasonCode,
        String message) {

    /** 本次把货物改成了「已就绪」。 */
    public static CargoReadyResponse updated(String orderId, String goodsStatus, int updatedRows) {
        return new CargoReadyResponse(true, orderId, goodsStatus, true, updatedRows, null, null);
    }

    /** 幂等命中：货物本来就是「已就绪」，本次未改库。 */
    public static CargoReadyResponse alreadyReady(String orderId) {
        return new CargoReadyResponse(true, orderId, GoodsStatus.READY.getValue(),
                false, 0, Reason.ALREADY_READY.name(),
                "货物已是「已就绪」，本次不重复登记");
    }

    /** 拒绝：请求无法登记。 */
    public static CargoReadyResponse rejected(String orderId, Reason reason, String message) {
        return new CargoReadyResponse(false, orderId, null, false, 0, reason.name(), message);
    }

    /** 原因码（枚举名即码，便于前端分支与日志检索）。 */
    public enum Reason {

        /** 订单号缺失 */
        ORDER_ID_MISSING,

        /** 该订单查无货物记录 */
        GOODS_NOT_FOUND,

        /** 货物已进入不可回退的状态（已装机／已交付／已回收／灭失） */
        GOODS_STATE_FROZEN,

        /** 幂等命中：已是「已就绪」 */
        ALREADY_READY
    }
}
