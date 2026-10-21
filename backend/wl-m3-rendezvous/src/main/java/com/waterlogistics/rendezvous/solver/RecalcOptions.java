package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.Wind;

import java.util.Objects;



/**
 * 6a 重算的调用侧参数——请求形态（《81》§2.5）之外的任务与阈值参数，
 * 一律由调用方注入（来源 {@code sys_param}／任务上下文），本层不硬编码。
 *
 * @param representativeWind  代表风况（修正有效速度）
 * @param deadlineMs          允许时限 T_max 对应的最晚会面时刻，Unix 毫秒（由上层算好）
 * @param meetWindowMarginSec 会面时间窗余量，秒（窗口＝[t*, t*＋余量]）
 * @param convergeEpsilonSec  不动点迭代收敛阈值 ε_t，秒
 * @param maxIterations       不动点迭代最大次数
 */
@SuppressWarnings("MissingJavadoc")
public record RecalcOptions(

        Wind representativeWind,

        long deadlineMs,

        int meetWindowMarginSec,

        double convergeEpsilonSec,

        int maxIterations) {






    public RecalcOptions {
        Objects.requireNonNull(representativeWind, "representativeWind 不能为空");
        if (deadlineMs <= 0L) {
            throw new IllegalArgumentException("最晚会面时刻必须为正，实际 " + deadlineMs);
        }
        if (meetWindowMarginSec < 0 || meetWindowMarginSec > RendezvousSolveRequest.MAX_WINDOW_MARGIN_SEC) {
            throw new IllegalArgumentException(
                    "会面时间窗余量须在 [0, " + RendezvousSolveRequest.MAX_WINDOW_MARGIN_SEC
                            + "] 秒内，实际 " + meetWindowMarginSec);
        }
        if (!Double.isFinite(convergeEpsilonSec) || !(convergeEpsilonSec > (double) 0)) {
            throw new IllegalArgumentException("收敛阈值必须为正的有限数，实际 " + convergeEpsilonSec);
        }
        if (maxIterations <= 0 || maxIterations > RendezvousSolveRequest.MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "最大迭代次数须在 [1, " + RendezvousSolveRequest.MAX_ITERATIONS + "] 内，实际 " + maxIterations);
        }
    }
}
