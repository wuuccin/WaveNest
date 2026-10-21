package com.waterlogistics.monitor.taskstate;

import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.TaskStatus;

/**
 * 投送确认（模块四功能 2.2.1 一环）——把 M3 的末段交接完成确认落成状态流转。
 *
 * <p>落款：《包结构设计》§二行 123
 * 「{@code taskstate/  # 任务状态流转、进度与 ETA 计算、投送确认（吴）}」，
 * 负责人吴可欣，属 {@code wl-m4-monitor}。
 *
 * <p><b>输入是 M3 的交接判定，不自己重算</b>：{@link B_HandoverConfirmation} 由
 * {@code m3.handover.HandoverJudge.judge} 产出。本类只做<b>状态流转裁决</b>，
 * 不重算交接是否成功——重算会与 B 组产生两个真相源。
 *
 * <p><b>与《16 号清单》措辞的一处订正</b>：清单写「末段交接完成 → 投送确认 → 推进「已完成」」，
 * 但《23》§8 写入方表把流转拆成两步、且第二步是<b>落巢确认</b>：
 * <table border="1">
 *   <caption>《23》§8 相关写入方</caption>
 *   <tr><th>流转</th><th>写入方</th><th>触发</th></tr>
 *   <tr><td>任务 {@code 末段交接中 → 返航中}</td><td>M4</td><td>交接完成确认</td></tr>
 *   <tr><td>任务 {@code 返航中 → 已完成}</td><td>M4</td><td><b>落巢确认</b></td></tr>
 *   <tr><td>货物 {@code 已装机 → 已交付}</td><td>M4</td><td>与船交接完成</td></tr>
 * </table>
 * 即投送确认推进到<b>「返航中」</b>而非「已完成」——「已完成」要等原机落巢。
 * 若把交接成功直接判成「已完成」，会出现「货物已交付但无人机还没落巢」的中间态，
 * 与《23》§4 的状态语义冲突。本类据此实现，差异已回填《51》变更记录。
 *
 * <p><b>不落库</b>：《23》§8「落库归 M6」，本类只产出流转结论与货物状态。
 */
public final class DeliveryConfirm {

    private DeliveryConfirm() {
    }

    /**
     * 投送确认：由交接完成确认推进任务状态与货物状态。
     *
     * <p>成功路径：任务 {@code 末段交接中 → 返航中}，货物 {@code 已装机 → 已交付}。
     * <p>失败路径：<b>不推进任何状态</b>，返回 {@link Result#confirmed()}=false，
     * 由 M2 决策是继续跟飞还是走 6b——M4 只报事实、不决策（《20》§3）。
     *
     * @param confirmation M3 交接完成确认（不可为空）
     * @param goodsStatus  交接前货物状态（须为「已装机」；空中不可转移，《23》§6 约束 1）
     * @return 投送确认结果
     * @throws IllegalArgumentException 交接前货物状态不是「已装机」，或成功却缺交接时刻
     * @throws IllegalStateException    当前任务状态不允许流转到「返航中」
     */
    public static Result confirm(B_HandoverConfirmation confirmation, GoodsStatus goodsStatus) {
        if (confirmation == null) {
            throw new IllegalArgumentException("交接完成确认不得为空：投送确认的判据来自 M3 交接判定，不得自行臆断");
        }
        if (goodsStatus != GoodsStatus.LOADED) {
            throw new IllegalArgumentException(
                    "交接前货物状态应为「已装机」，实为「" + goodsStatus.getValue() + "」："
                            + "货物装机后绑死在原机上，空中不可转移（《23》§6 约束 1）");
        }
        if (confirmation.handoverResult() != HandoverResult.SUCCESS) {
            return Result.notConfirmed("交接未成功（" + confirmation.handoverResult().getValue() + "），不推进状态");
        }
        if (confirmation.handoverDoneT() == null) {
            throw new IllegalArgumentException(
                    "交接结果为成功却无交接完成时刻（taskId=" + confirmation.taskId() + "）："
                            + "缺该时刻无法记账 Escort/交接耗时");
        }

        // 校验流转合法：末段交接中 → 返航中（《23》§8）。非法即抛，避免脏状态落库。
        TaskStateMachine.requireTransit(TaskStatus.HANDOVER, TaskStatus.RETURNING);

        return new Result(true, TaskStatus.RETURNING, GoodsStatus.DELIVERED,
                confirmation.handoverDoneT(), confirmation.actualMeetLon(), confirmation.actualMeetLat(),
                "交接成功，投送确认：任务进「返航中」、货物「已装机 → 已交付」；"
                        + "「已完成」须等落巢确认（《23》§8）");
    }

    /**
     * 投送确认结果。
     *
     * @param confirmed     是否确认投送成功（失败时为 false，状态一律不变）
     * @param taskStatus    推进后的任务状态；未确认时为 {@code null}
     * @param goodsStatus   推进后的货物状态；未确认时为 {@code null}
     * @param handoverDoneT 交接完成时刻（Unix 毫秒）；未确认时为 {@code null}
     * @param actualMeetLon 实际会面点经度；未确认时为 {@code null}
     * @param actualMeetLat 实际会面点纬度；未确认时为 {@code null}
     * @param note          结论说明（供日志与 task_log，不自造状态词）
     */
    public record Result(
            boolean confirmed,
            TaskStatus taskStatus,
            GoodsStatus goodsStatus,
            Long handoverDoneT,
            Double actualMeetLon,
            Double actualMeetLat,
            String note) {

        public Result {
            if (confirmed) {
                if (taskStatus == null || goodsStatus == null || handoverDoneT == null) {
                    throw new IllegalArgumentException("投送确认成功时任务状态、货物状态、交接时刻均不得为空");
                }
            } else if (taskStatus != null || goodsStatus != null || handoverDoneT != null) {
                throw new IllegalArgumentException("投送确认未成功时不得携带状态或时刻：M4 只报事实、不决策");
            }
            if (note == null || note.isBlank()) {
                throw new IllegalArgumentException("投送确认须给出结论说明，供日志与 task_log 追溯");
            }
        }

        /** 未确认（交接失败）：状态一律不推进，只带结论说明。 */
        static Result notConfirmed(String note) {
            return new Result(false, null, null, null, null, null, note);
        }
    }
}