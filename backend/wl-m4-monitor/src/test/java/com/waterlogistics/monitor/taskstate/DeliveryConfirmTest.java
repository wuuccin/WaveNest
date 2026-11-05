package com.waterlogistics.monitor.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * 投送确认单测（《23》§8 写入方口径）。
 *
 * <p>核心钉死两条：① 交接成功推进到<b>「返航中」而非「已完成」</b>
 * ——「已完成」归 M4 落巢确认；② 交接失败<b>不推进任何状态</b>（M4 只报事实、不决策）。
 */
class DeliveryConfirmTest {

    private static B_HandoverConfirmation confirmation(HandoverResult result, Long doneT) {
        boolean success = result == HandoverResult.SUCCESS;
        return new B_HandoverConfirmation("TASK-1", "DRONE-1",
                120.15, 30.28, 120.10, 30.20, 1_700_000_000_000L,
                doneT,
                success ? Integer.valueOf(120) : null,
                success ? Integer.valueOf(60) : null,
                success ? Double.valueOf(18.5) : null,
                result);
    }

    /** 交接成功：任务进「返航中」、货物「已装机 → 已交付」 */
    @Test
    void confirm_shouldAdvanceToReturningAndDeliveredWhenHandoverSucceeds() {
        DeliveryConfirm.Result r = DeliveryConfirm.confirm(
                confirmation(HandoverResult.SUCCESS, 1_700_000_060_000L), GoodsStatus.LOADED);

        assertTrue(r.confirmed());
        assertEquals(TaskStatus.RETURNING, r.taskStatus(),
                "《23》§8：交接完成确认推进「末段交接中 → 返航中」");
        assertEquals(GoodsStatus.DELIVERED, r.goodsStatus(), "货物「已装机 → 已交付」");
        assertEquals(1_700_000_060_000L, r.handoverDoneT());
        assertEquals(120.15, r.actualMeetLon(), 1e-9, "实际会面点须带出，供 19 会面偏差回流用");
    }

    /** 不得直接判成「已完成」——那是落巢确认的事 */
    @Test
    void confirm_shouldNotJumpToFinishedBeforeLanding() {
        DeliveryConfirm.Result r = DeliveryConfirm.confirm(
                confirmation(HandoverResult.SUCCESS, 1_700_000_060_000L), GoodsStatus.LOADED);

        assertFalse(r.taskStatus() == TaskStatus.FINISHED,
                "交接完成不等于任务完成：无人机还没落巢");
        assertTrue(r.note().contains("落巢确认"), "结论须点明「已完成」待落巢确认：" + r.note());
    }

    /** 交接失败：不推进任何状态，由 M2 决策 */
    @Test
    void confirm_shouldNotAdvanceWhenHandoverFails() {
        DeliveryConfirm.Result r = DeliveryConfirm.confirm(
                confirmation(HandoverResult.FAILED, null), GoodsStatus.LOADED);

        assertFalse(r.confirmed());
        assertNull(r.taskStatus(), "M4 只报事实、不决策，失败时不带状态");
        assertNull(r.goodsStatus());
        assertNull(r.handoverDoneT());
        assertTrue(r.note().contains("失败"), "结论须点明交接未成功：" + r.note());
    }

    /** 交接前货物状态不是「已装机」须拒绝：空中不可转移 */
    @Test
    void confirm_shouldRejectWhenGoodsNotLoaded() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DeliveryConfirm.confirm(
                        confirmation(HandoverResult.SUCCESS, 1_700_000_060_000L), GoodsStatus.READY));

        assertTrue(thrown.getMessage().contains("已装机"),
                "须点明当前货物状态：" + thrown.getMessage());
    }

    /** 成功却缺交接时刻须拒绝：无法记账耗时 */
    @Test
    void confirm_shouldRejectSuccessWithoutDoneTime() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DeliveryConfirm.confirm(
                        confirmation(HandoverResult.SUCCESS, null), GoodsStatus.LOADED));

        assertTrue(thrown.getMessage().contains("交接完成时刻"),
                "须点明缺的是交接时刻：" + thrown.getMessage());
    }

    /** 交接确认不得为空：不得自行臆断交接结果 */
    @Test
    void confirm_shouldRejectNullConfirmation() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DeliveryConfirm.confirm(null, GoodsStatus.LOADED));

        assertTrue(thrown.getMessage().contains("不得为空"), "须点明缺的是交接确认：" + thrown.getMessage());
    }
}