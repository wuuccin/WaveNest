package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.Wind;

/**
 * 会面点求解命令——M2 发起的组合级调用入参（《22-接口契约》§5 第 3 步 · 判「追得上」）。
 *
 * <p>与算法层 {@code RendezvousSolveRequest} 的差别：<b>不带快照本体</b>——快照经
 * {@code TrajectorySnapshotPort} 由本模块自取（端口反转，《42-包结构设计》§3.3），
 * 命令只带<b>期望版本</b>作一致性锚；版本不一致即拒绝求解（《81-B组算法方案》§2.1 硬要求 1）。
 *
 * <p>阈值与参数一律由调用方注入（来源 {@code sys_param}／机型档案），本层不硬编码。
 *
 * @param comboId             候选组合标识（会面点为组合级产物）
 * @param shipMmsi            目标船标识
 * @param snapshotVersion     调用方期望的快照版本（与端口取回快照不一致即拒绝）
 * @param nestLon             机巢起点经度
 * @param nestLat             机巢起点纬度
 * @param cruiseSpeedMs       机型巡航速度，m/s
 * @param representativeWind  代表风况（修正有效速度）
 * @param nowMs               求解起点＝当前时刻，Unix 毫秒
 * @param deadlineMs          允许时限 T_max 对应的最晚会面时刻，Unix 毫秒（由上层算好）
 * @param meetWindowMarginSec 会面时间窗余量，秒（窗口＝[t*, t*＋余量]）
 * @param convergeEpsilonSec  不动点迭代收敛阈值 ε_t，秒
 * @param maxIterations       不动点迭代最大次数
 */
public record RendezvousCommand(

        String comboId,

        String shipMmsi,

        String snapshotVersion,

        double nestLon,

        double nestLat,

        double cruiseSpeedMs,

        Wind representativeWind,

        long nowMs,

        long deadlineMs,

        int meetWindowMarginSec,

        double convergeEpsilonSec,

        int maxIterations) {
}
