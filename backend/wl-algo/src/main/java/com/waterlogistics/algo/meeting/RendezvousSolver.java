package com.waterlogistics.algo.meeting;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.NoSolutionReason;
import com.waterlogistics.algo.meeting.model.RendezvousSolveRequest;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import java.util.List;
import java.util.Objects;

/**
 * 会面点求解（真算法 1）——相对运动拦截求解：相遇条件方程的一维求根（不动点迭代）
 * 与二分兜底（《81-B组算法方案》§3.1、《32-M3-动态会面》§3.1）。
 *
 * <p>口径（照文档，勿改动）：
 * <ul>
 *   <li>求解起点为当前时刻（《32》§3.1 判定 1）；相遇条件为 |P(t) − N| ≤ v·(t − now)；</li>
 *   <li>取最早满足者为会面时刻 t*，P(t*) 即会面点；</li>
 *   <li><b>不把「迭代不收敛」直接当无解</b>，一律以二分结果为准（《81》§3.1 收敛性与数值注意）；</li>
 *   <li>「追不上」＝在 [now, min(T_max, 末点时刻)] 内无根——短路出口（《32》§4.3）；</li>
 *   <li>纯函数：无状态副作用、同输入同输出（《81》§2.4 硬约束 1）。</li>
 * </ul>
 *
 * <p>首版简化（留接口待精算版）：有效速度取<b>单次值</b>——沿「巢 → 当前船位」方位的
 * 代表风况修正，不逐迭代、不逐段修正（《81》§3.1 步骤 2「首版每组合取一个代表风况」）。
 */
public final class RendezvousSolver {

    /** 粗扫步数：把可求解窗口切成该数量级的探测格，用于定位首个变号区间 */
    private static final int COARSE_SCAN_STEPS = 256;

    private RendezvousSolver() {
    }

    /**
     * 解一个候选组合的会面点。
     *
     * @param request 求解入参（快照、巢起点、机型速度、代表风况、时限与阈值）
     * @return 会面点解；追不上或入参退化时为无解解（原因见 {@link NoSolutionReason}）
     */
    public static B_RendezvousSolution solve(RendezvousSolveRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(request.snapshot(), "snapshot 不能为空");
        Objects.requireNonNull(request.representativeWind(), "representativeWind 不能为空");
        String snapshotVersion = request.snapshot().version();
        String solutionId = "RS-" + request.comboId() + "-" + snapshotVersion + "-" + request.nowMs();

        List<ShipPoint> points = request.snapshot().points();
        if (points == null || points.isEmpty()) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.OTHER, snapshotVersion);
        }

        long tLo = request.nowMs();
        long tHi = Math.min(request.deadlineMs(), points.get(points.size() - 1).t());
        if (tHi <= tLo) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.OTHER, snapshotVersion);
        }

        // 有效速度：沿「巢 → 锚点」方位，代表风况修正，首版单次取值。
        // 锚点＝调用方给定船当前状态（6a 重算路径，《81》§2.5）；缺省回落到快照在求解
        // 起点的插值位置（常规求解路径）——两者都只用于定向，不替代快照运动模型。
        double[] anchor = request.shipState() != null
                ? new double[] {request.shipState().lon(), request.shipState().lat()}
                : TrackInterpolator.positionAt(request.snapshot(), tLo);
        double bearing = GeoApprox.bearingDeg(request.nestLon(), request.nestLat(), anchor[0], anchor[1]);
        double v = EffectiveSpeed.alongBearing(
                request.cruiseSpeedMs(), request.representativeWind(), bearing);
        if (v <= 0) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.CANNOT_CATCH, snapshotVersion);
        }

        // 不动点迭代给一个根；二分另给 [tLo, tHi] 内的根，两处都取到时取较早者——
        // 非单调船迹可能让迭代收敛到较晚的根，违反「取最早满足者」（类注释）。
        double t = solveByFixedPoint(request, tLo, v);
        if (!(t <= tHi)) {
            t = Double.NaN;
        }
        double tBisection = solveByBisection(request, tLo, tHi, v);
        if (!Double.isNaN(tBisection)) {
            t = Double.isNaN(t) ? tBisection : Math.min(t, tBisection);
        }
        if (Double.isNaN(t)) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.CANNOT_CATCH, snapshotVersion);
        }

        long meetT = Math.round(t);
        double[] meeting = TrackInterpolator.positionAt(request.snapshot(), meetT);
        int tauCoarse = (int) Math.round(
                GeoApprox.distanceM(request.nestLon(), request.nestLat(), meeting[0], meeting[1]) / v);
        long meetTMax = meetT + request.meetWindowMarginSec() * 1000L;

        return B_RendezvousSolution.solved(solutionId, request.comboId(),
                meeting[0], meeting[1], meetT, meetTMax, meetT, tauCoarse, snapshotVersion);
    }

    /** 不动点迭代；不收敛或越界时返回 {@code Double.POSITIVE_INFINITY} */
    private static double solveByFixedPoint(RendezvousSolveRequest request, long tLo, double v) {
        double t = tLo;
        double epsilonMs = request.convergeEpsilonSec() * 1000.0;
        for (int i = 0; i < request.maxIterations(); i++) {
            double[] p = TrackInterpolator.positionAt(request.snapshot(), Math.round(t));
            double d = GeoApprox.distanceM(request.nestLon(), request.nestLat(), p[0], p[1]);
            double next = tLo + d * 1000.0 / v;
            if (Math.abs(next - t) <= epsilonMs) {
                return next;
            }
            t = next;
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * 求 g(t) = d(t) − v·(t−tLo) 在 [tLo, tHi] 内的最早变号区间并细化到根；无根返回 {@code NaN}。
     *
     * <p>船迹非单调时 g(t) 可多次变号，直接二分会落到较晚的根——先按粗步长扫描定位
     * 首个 g(t) ≤ 0 的区间，再在该区间内二分。
     */
    private static double solveByBisection(RendezvousSolveRequest request, long tLo, long tHi, double v) {
        if (gapMeters(request, tLo, tLo, v) <= 0) {
            return tLo;
        }
        long span = tHi - tLo;
        long coarseStep = Math.max(1L, span / COARSE_SCAN_STEPS);
        long lo = tLo;
        for (long probe = tLo + coarseStep; ; probe += coarseStep) {
            long hi = Math.min(probe, tHi);
            if (gapMeters(request, hi, tLo, v) <= 0) {
                return bisect(request, lo, hi, tLo, v);
            }
            if (hi >= tHi) {
                return Double.NaN;
            }
            lo = hi;
        }
    }

    /** 在已知 [lo, hi] 存在变号的区间内二分细化（毫秒分辨率） */
    private static double bisect(RendezvousSolveRequest request, double lo, double hi, long tLo, double v) {
        while (hi - lo > 1.0) {
            double mid = (lo + hi) / 2;
            if (gapMeters(request, Math.round(mid), tLo, v) <= 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    /** 尚未够到的余量（米）：正＝还差，≤0＝可按期到达 */
    private static double gapMeters(RendezvousSolveRequest request, long t, long tLo, double v) {
        double[] p = TrackInterpolator.positionAt(request.snapshot(), t);
        double d = GeoApprox.distanceM(request.nestLon(), request.nestLat(), p[0], p[1]);
        return d - v * (t - tLo) / 1000.0;
    }
}
