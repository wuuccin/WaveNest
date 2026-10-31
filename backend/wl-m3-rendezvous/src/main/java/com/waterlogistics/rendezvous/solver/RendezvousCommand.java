package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.EpochMillis;
import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.ShipState;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.rendezvous.support.WireText;

import java.util.Objects;



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
@SuppressWarnings("MissingJavadoc")
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

    private static final int MAX_ID_LENGTH = 64;






    public RendezvousCommand {
        Objects.requireNonNull(representativeWind, "representativeWind 不能为空");
        comboId = WireText.requireIdentifier(comboId, "comboId", RendezvousCommand.MAX_ID_LENGTH);
        shipMmsi = WireText.requireIdentifier(shipMmsi, "shipMmsi", RendezvousCommand.MAX_ID_LENGTH);
        snapshotVersion = WireText.requireIdentifier(snapshotVersion, "snapshotVersion",
                RendezvousCommand.MAX_ID_LENGTH);
        if (!Double.isFinite(nestLon) ||
                !Double.isFinite(nestLat) ||
                nestLon < -180.0 ||
                nestLon > 180.0 ||
                nestLat < -90.0 ||
                nestLat > 90.0) {
            throw new IllegalArgumentException("机巢坐标必须为有限数且经纬度在合法范围内");
        }
        if (!Double.isFinite(cruiseSpeedMs) || cruiseSpeedMs <= 0.0 || cruiseSpeedMs > ShipState.MAX_SOG_MS) {
            throw new IllegalArgumentException("巡航速度须在 (0, " + ShipState.MAX_SOG_MS
                                                       + "] m/s 内，实际 " + cruiseSpeedMs);
        }
        if (meetWindowMarginSec < 0
                || meetWindowMarginSec > RendezvousSolveRequest.MAX_WINDOW_MARGIN_SEC) {
            throw new IllegalArgumentException("会面时间窗余量须在 [0, "
                                                       + RendezvousSolveRequest.MAX_WINDOW_MARGIN_SEC + "] 秒内，实际 " + meetWindowMarginSec);
        }
        if (!EpochMillis.isValid(nowMs)) {
            throw new IllegalArgumentException("求解起点须为合法的 Unix 毫秒值，实际 " + nowMs);
        }
        if (!EpochMillis.isValid(deadlineMs) || deadlineMs <= nowMs) {
            throw new IllegalArgumentException("最晚会面时刻须为合法的 Unix 毫秒值且晚于求解起点，实际 nowMs="
                                                       + nowMs + ", deadlineMs=" + deadlineMs);
        }
        if (!Double.isFinite(convergeEpsilonSec) || !(convergeEpsilonSec > (double) 0)) {
            throw new IllegalArgumentException("收敛阈值必须为正的有限数，实际 " + convergeEpsilonSec);
        }
        if (convergeEpsilonSec > RendezvousSolveRequest.MAX_CONVERGE_EPSILON_SEC) {
            throw new IllegalArgumentException("收敛阈值须在 (0, "
                                                       + RendezvousSolveRequest.MAX_CONVERGE_EPSILON_SEC + "] 秒内，实际 "
                                                       + convergeEpsilonSec);
        }
        if (maxIterations <= 0 || maxIterations > RendezvousSolveRequest.MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "最大迭代次数须在 [1, " + RendezvousSolveRequest.MAX_ITERATIONS + "] 内，实际 " + maxIterations);
        }
    }
}
