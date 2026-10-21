package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;

import java.util.Objects;



/**
 * 6a 重算返回结构（《81-B组算法方案》§2.5「返回结构」）：{@code requestId} ＋
 * 会面点解。成功／无解语义在 {@code solution} 成员内部——成功
 * {@code solution.hasSolution()=true}；无解 {@code solution.hasSolution()=false} ＋
 * {@code solution.noSolutionReason()}，会面字段为 {@code null}。
 *
 * <p><b>不回 {@code routeVersion}、不触发重算航迹</b>——航迹是否重算由 `33` 纠偏循环
 * 另行调用 13 号决定（量判分离、两笔账不混，§2.5 返回结构 3）。
 *
 * @param requestId 请求标识（去除首尾空白后返回）
 * @param solution  会面点解（恒非空；其 comboId 由 droneId 当前任务回溯，回溯不到时为 null）
 */
@SuppressWarnings("MissingJavadoc")
public record RendezvousRecalcResult(

        String requestId,

        B_RendezvousSolution solution) {

    public RendezvousRecalcResult {
        Objects.requireNonNull(requestId, "requestId 不能为空");
        Objects.requireNonNull(solution, "solution 不能为空");
        requestId = requestId.strip();
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空白字符串");
        }
    }
}
