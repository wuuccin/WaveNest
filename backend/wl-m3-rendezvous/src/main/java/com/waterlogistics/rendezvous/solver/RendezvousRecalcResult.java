package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;

/**
 * 6a 重算返回结构（《81-B组算法方案》§2.5「返回结构」）：{@code requestId} ＋
 * 会面点解。成功 {@code hasSolution=true}；无解 {@code hasSolution=false} ＋
 * {@code noSolutionReason}，会面字段为 {@code null}。
 *
 * <p><b>不回 {@code routeVersion}、不触发重算航迹</b>——航迹是否重算由 `33` 纠偏循环
 * 另行调用 13 号决定（量判分离、两笔账不混，§2.5 返回结构 3）。
 *
 * @param requestId 请求标识（原样返回）
 * @param solution  会面点解（含 comboId，由 droneId 当前任务回溯；取不到为 null）
 */
public record RendezvousRecalcResult(

        String requestId,

        B_RendezvousSolution solution) {
}
