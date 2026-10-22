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
 *   <li><b>不把「迭代不收敛」直接当无解</b>：二分负责兜底，固定点结果须先验会面条件已满足
 *       才接受（《81》§3.1 收敛性与数值注意）；</li>
 *   <li>「追不上」＝在 [now, min(T_max, 末点时刻)] 内无根——短路出口（《32》§4.3）；</li>
 *   <li>纯函数：无状态副作用、同输入同输出（《81》§2.4 硬约束 1）。</li>
 * </ul>
 *
 * <p>首版简化（留接口待精算版）：有效速度取<b>单次值</b>——沿「巢 → 当前船位」方位的
 * 代表风况修正，不逐迭代、不逐段修正（《81》§3.1 步骤 2「首版每组合取一个代表风况」）。
 *
 * <p>检出分辨率：粗扫按 256／1024／4096 逐级加密，最细间隔为窗口的 1/4096（1 h 窗口约 0.9 s）；
 * 短于该间隔且完全落在两探测点之间的可行窗口可能漏判，故 {@code CANNOT_CATCH} 是「按此分辨率
 * 未找到」而非解析意义上的精确判定。
 */
public final class RendezvousSolver {

    /** 粗扫分级：先稀后密，避免短促可达落在两个探测点之间被漏判 */
    private static final int[] COARSE_SCAN_STEPS = {256, 1024, 4096};

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

        long tLo = request.nowMs();
        long tHi = Math.min(request.deadlineMs(), points.get(points.size() - 1).t());
        if (tHi < tLo) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.OTHER, snapshotVersion);
        }

        // 有效速度：沿「巢 → 锚点」方位，代表风况修正，首版单次取值。
        // 锚点＝调用方给定船当前状态（6a 重算路径，《81》§2.5）；缺省回落到快照在求解
        // 起点的插值位置（常规求解路径）——两者都只用于定向，不替代快照运动模型。
        double[] anchor = request.shipState() != null
                ? new double[] {request.shipState().lon(), request.shipState().lat()}
                : TrackInterpolator.positionAt(request.snapshot(), tLo);
        double nestToAnchorM = GeoApprox.distanceM(
                request.nestLon(), request.nestLat(), anchor[0], anchor[1]);
        // 船已在巢位：方位无定义，不做风向修正（会面即当前时刻，由窗口判定）
        double v = nestToAnchorM <= 1e-9
                ? request.cruiseSpeedMs()
                : EffectiveSpeed.alongBearing(request.cruiseSpeedMs(),
                        request.representativeWind(),
                        GeoApprox.bearingDeg(request.nestLon(), request.nestLat(), anchor[0], anchor[1]));
        if (v <= 0) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.CANNOT_CATCH, snapshotVersion);
        }

        double t;
        if (tLo == tHi) {
            // 零时长窗口：仅当船此刻恰在巢位（d==0）才即刻会面；近似重合不在此分支
            if (gapMeters(request, tLo, tLo, v) > 0) {
                return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                        NoSolutionReason.CANNOT_CATCH, snapshotVersion);
            }
            t = tLo;
        } else {
            // 不动点迭代给一个根；二分另给最早根，两处都取到时取较早者——
            // 非单调船迹可能让迭代收敛到较晚的根，违反「取最早满足者」（类注释）。
            t = solveByFixedPoint(request, tLo, v);
            if (!(t <= tHi)) {
                t = Double.NaN;
            }
            double tBisection = solveByBisection(request, tLo, tHi, v);
            // 固定点结果须先验会面条件已满足；容差按毫秒取整与收敛阈值折算为米
            double toleranceM = v * (request.convergeEpsilonSec() + 0.001);
            if (!Double.isNaN(t) && gapMeters(request, Math.round(t), tLo, v) > toleranceM) {
                t = Double.NaN;
            }
            if (!Double.isNaN(tBisection)) {
                t = Double.isNaN(t) ? tBisection : Math.min(t, tBisection);
            }
        }
        if (Double.isNaN(t)) {
            return B_RendezvousSolution.unsolvable(solutionId, request.comboId(),
                    NoSolutionReason.CANNOT_CATCH, snapshotVersion);
        }

        long meetT = Math.round(t);
        double[] meeting = TrackInterpolator.positionAt(request.snapshot(), meetT);
        int tauCoarse = (int) Math.ceil(
                GeoApprox.distanceM(request.nestLon(), request.nestLat(), meeting[0], meeting[1]) / v);
        long meetTMax = Math.min(meetT + request.meetWindowMarginSec() * 1000L, request.deadlineMs());

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
     * <p>船迹非单调时 g(t) 可多次变号，固定步长扫描可能把「短暂达标」漏在两个探测点之间；
     * 先按稀步长扫描，未找到根再逐级加密，最后才判无根。
     */
    private static double solveByBisection(RendezvousSolveRequest request, long tLo, long tHi, double v) {
        if (gapMeters(request, tLo, tLo, v) <= 0) {
            return tLo;
        }
        long span = tHi - tLo;
        long upper = tHi;
        double earliest = Double.NaN;
        for (int steps : COARSE_SCAN_STEPS) {
            long step = Math.max(1L, span / steps);
            double root = scanFirstCrossing(request, tLo, upper, v, step);
            if (!Double.isNaN(root)) {
                earliest = root;
                upper = Math.min(upper, (long) Math.ceil(root));
            }
            if (step == 1L) {
                // 已达毫秒分辨率，再加密不会产生新的探测点
                break;
            }
        }
        return earliest;
    }

    /** 按给定步长扫描首个「g(t) ≤ 0」的区间并细化；未命中返回 {@code NaN} */
    private static double scanFirstCrossing(RendezvousSolveRequest request,
            long tLo, long tHi, double v, long step) {
        long lo = tLo;
        for (long probe = tLo + step; ; probe += step) {
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
