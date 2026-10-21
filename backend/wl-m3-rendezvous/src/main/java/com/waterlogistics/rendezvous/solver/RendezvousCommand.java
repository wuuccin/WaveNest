package com.waterlogistics.rendezvous.solver;

import com.waterlogistics.algo.meeting.model.EpochMillis;
import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.Wind;

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






    public RendezvousCommand {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        Objects.requireNonNull(shipMmsi, "shipMmsi 不能为空");
        Objects.requireNonNull(snapshotVersion, "snapshotVersion 不能为空");
        Objects.requireNonNull(representativeWind, "representativeWind 不能为空");
        comboId = comboId.strip();
        shipMmsi = shipMmsi.strip();
        snapshotVersion = snapshotVersion.strip();
        if (comboId.isEmpty() || shipMmsi.isEmpty() || snapshotVersion.isEmpty()) {
            throw new IllegalArgumentException("comboId/shipMmsi/snapshotVersion 不能为空白字符串");
        }
        if (!Double.isFinite(nestLon) ||
                !Double.isFinite(nestLat) ||
                nestLon < -180.0 ||
                nestLon > 180.0 ||
                nestLat < -90.0 ||
                nestLat > 90.0) {
            throw new IllegalArgumentException("机巢坐标必须为有限数且经纬度在合法范围内");
        }
        if (!Double.isFinite(cruiseSpeedMs) || !(cruiseSpeedMs > (double) 0)) {
            throw new IllegalArgumentException("巡航速度必须为正的有限数，实际 " + cruiseSpeedMs);
        }
        if (meetWindowMarginSec < 0) {
            throw new IllegalArgumentException("会面时间窗余量不能为负，实际 " + meetWindowMarginSec);
        }
        if (nowMs < EpochMillis.MIN_VALID_MS) {
            throw new IllegalArgumentException("求解起点须为 Unix 毫秒值，实际 " + nowMs);
        }
        if (deadlineMs <= nowMs) {
            throw new IllegalArgumentException("最晚会面时刻须晚于求解起点，实际 nowMs=" + nowMs + ", deadlineMs=" + deadlineMs);
        }
        if (!Double.isFinite(convergeEpsilonSec) || !(convergeEpsilonSec > (double) 0)) {
            throw new IllegalArgumentException("收敛阈值必须为正的有限数，实际 " + convergeEpsilonSec);
        }
        if (convergeEpsilonSec * 1000.0 >= (double) (deadlineMs - nowMs)) {
            throw new IllegalArgumentException("收敛阈值须明显小于求解时域，实际 epsilonSec="
                    + convergeEpsilonSec + ", 时域毫秒=" + (deadlineMs - nowMs));
        }
        if (maxIterations <= 0 || maxIterations > RendezvousSolveRequest.MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "最大迭代次数须在 [1, " + RendezvousSolveRequest.MAX_ITERATIONS + "] 内，实际 " + maxIterations);
        }
    }
}
