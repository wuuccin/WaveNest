package com.waterlogistics.rendezvous.conflict;

import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.ConflictSpaceTime;
import com.waterlogistics.algo.meeting.model.CpaPoint;
import com.waterlogistics.algo.meeting.model.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 航迹级空域冲突二查（简化版）——检测对象①「机—机」两条航迹的时空冲突
 * （《81-B组算法方案》§3.4、《33-M3-路径规划》§3.2）。
 *
 * <p><b>简化口径（《43-编码分工说明》§1；答辩口径「已规划、实现顺延」）</b>：
 * <ul>
 *   <li>粗筛＝三维包围盒（含安全间隔膨胀）＋时间窗相交——照 §3.4 步骤 2；</li>
 *   <li>精判＝时间同步采样＋双间隔判据「水平距离＜水平安全间隔 <b>且</b> 垂直间隔＜
 *       垂直安全间隔，同时成立」——§3.4 步骤 3 的采样简化版，<b>分段解析 CPA 留桩</b>；</li>
 *   <li><b>绕行几何留桩</b>：只给检测结论，{@code detourRoute} 为 null（§3.4 步骤 5 的
 *       动态障碍重规划属精算版；处置顺序「先错峰→再绕行→换候选」由 M2 执行）；</li>
 *   <li>检测对象②「航迹 × 空域台账」留桩（需 {@code airspace_restriction} 台账接入）；</li>
 *   <li>只检测不决策：错峰挪时刻归 D 功能 7（§3.4 划界）。</li>
 * </ul>
 *
 * <p>坐标处理：以<b>被查航迹首点</b>为原点做局部米制平移（同 {@code GeoApprox} 近似），
 * 两航迹共用同一原点——包围盒、距离与 CPA 全部在米制下计算。
 *
 * <p>前置约束：两航迹的航点时刻须<b>严格递增</b>（时间窗与线性插值均基于此；不满足即
 * 抛 {@link IllegalArgumentException}）。
 */
public final class TrackConflictChecker {

    /** 每度纬差对应的米数（与 GeoApprox 同一近似） */
    private static final double METERS_PER_DEG = Math.PI * 6_371_000.0 / 180.0;

    private TrackConflictChecker() {
    }

    /**
     * 检测两条航迹的时空冲突。
     *
     * @param route  被查航迹（{@code checksumVersion} 取它的 {@code routeVersion}）
     * @param other  对照航迹
     * @param params 间隔与采样参数（调用方注入）
     * @return 冲突结论；包围盒粗筛或时间窗短路时不产 CPA，绕行几何首版恒为 null
     * @throws IllegalArgumentException 航迹航点不足、航点时刻非严格递增或采样步长非正
     * @throws NullPointerException     route／other／params 为 null
     */
    public static B_ConflictResult check(B_FlyableRoute route, B_FlyableRoute other,
            ConflictParams params) {
        Objects.requireNonNull(route, "route 不能为空");
        Objects.requireNonNull(other, "other 不能为空");
        Objects.requireNonNull(params, "params 不能为空");
        requireUsable(route, "route");
        requireUsable(other, "other");
        if (params.sampleStepMs() <= 0) {
            throw new IllegalArgumentException("采样步长必须为正，实际 " + params.sampleStepMs());
        }

        Waypoint origin = route.waypoints().get(0);
        List<LocalPoint> a = toLocal(route.waypoints(), origin);
        List<LocalPoint> b = toLocal(other.waypoints(), origin);

        B_ConflictResult shortCircuit = new B_ConflictResult(
                false, null, null, null, null, null, route.routeVersion());

        long tStart = Math.max(a.get(0).t(), b.get(0).t());
        long tEnd = Math.min(a.get(a.size() - 1).t(), b.get(b.size() - 1).t());
        if (tStart > tEnd) {
            return shortCircuit;
        }
        if (!boxesOverlap(a, b, params)) {
            return shortCircuit;
        }

        // 时间同步采样（含两端点）：双间隔判据 + 全程三维最近接近点
        double bestD3 = Double.POSITIVE_INFINITY;
        long bestT = tStart;
        double bestMidX = 0;
        double bestMidY = 0;
        double bestMidAlt = 0;
        long firstConflictT = 0;
        long lastConflictT = 0;
        boolean anyConflict = false;

        long step = params.sampleStepMs();
        long t = tStart;
        while (true) {
            LocalPoint pa = interpolate(a, t);
            LocalPoint pb = interpolate(b, t);
            double h = Math.hypot(pa.x() - pb.x(), pa.y() - pb.y());
            double v = Math.abs(pa.altM() - pb.altM());
            double d3 = Math.sqrt(h * h + v * v);

            if (d3 < bestD3) {
                bestD3 = d3;
                bestT = t;
                bestMidX = (pa.x() + pb.x()) / 2;
                bestMidY = (pa.y() + pb.y()) / 2;
                bestMidAlt = (pa.altM() + pb.altM()) / 2;
            }
            if (h < params.horizontalSepM() && v < params.verticalSepM()) {
                if (!anyConflict) {
                    firstConflictT = t;
                    anyConflict = true;
                }
                lastConflictT = t;
            }

            if (t == tEnd) {
                break;
            }
            t = (tEnd - t <= step) ? tEnd : t + step;
        }

        CpaPoint cpaPoint = new CpaPoint(
                origin.lon() + bestMidX / (METERS_PER_DEG * Math.cos(Math.toRadians(origin.lat()))),
                origin.lat() + bestMidY / METERS_PER_DEG,
                bestMidAlt);
        ConflictSpaceTime interval = anyConflict
                ? new ConflictSpaceTime(null, firstConflictT, lastConflictT)
                : null;

        return new B_ConflictResult(
                anyConflict, interval, cpaPoint, bestT, bestD3, null, route.routeVersion());
    }

    private static void requireUsable(B_FlyableRoute route, String name) {
        if (route.waypoints() == null || route.waypoints().size() < 2) {
            throw new IllegalArgumentException(name + " 至少需要 2 个航点");
        }
        List<Waypoint> waypoints = route.waypoints();
        for (int i = 1; i < waypoints.size(); i++) {
            if (waypoints.get(i).t() <= waypoints.get(i - 1).t()) {
                throw new IllegalArgumentException(name + " 航点时刻必须严格递增（第 " + i + " 个非增）");
            }
        }
    }

    /**
     * 三维包围盒粗筛——§3.4 步骤 2：水平箱距（二维）与垂直箱隙<b>都</b>小于各自安全
     * 间隔才可能冲突，否则近零成本剔除。箱距是任意轨迹点对距离的下界，故剔除不丢真冲突。
     */
    private static boolean boxesOverlap(List<LocalPoint> a, List<LocalPoint> b,
            ConflictParams params) {
        double[] ba = box(a);
        double[] bb = box(b);
        double dxGap = Math.max(0, Math.max(ba[0] - bb[1], bb[0] - ba[1]));
        double dyGap = Math.max(0, Math.max(ba[2] - bb[3], bb[2] - ba[3]));
        double altGap = Math.max(0, Math.max(ba[4] - bb[5], bb[4] - ba[5]));
        return Math.hypot(dxGap, dyGap) < params.horizontalSepM()
                && altGap < params.verticalSepM();
    }

    /** 返回 {minX, maxX, minY, maxY, minAlt, maxAlt} */
    private static double[] box(List<LocalPoint> points) {
        double[] box = {
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (LocalPoint p : points) {
            box[0] = Math.min(box[0], p.x());
            box[1] = Math.max(box[1], p.x());
            box[2] = Math.min(box[2], p.y());
            box[3] = Math.max(box[3], p.y());
            box[4] = Math.min(box[4], p.altM());
            box[5] = Math.max(box[5], p.altM());
        }
        return box;
    }

    /** 段内线性插值；越界取端点（不外推） */
    private static LocalPoint interpolate(List<LocalPoint> points, long t) {
        LocalPoint first = points.get(0);
        LocalPoint last = points.get(points.size() - 1);
        if (t <= first.t()) {
            return first;
        }
        if (t >= last.t()) {
            return last;
        }
        for (int i = 1; i < points.size(); i++) {
            LocalPoint b = points.get(i);
            if (t <= b.t()) {
                LocalPoint a = points.get(i - 1);
                double k = (double) (t - a.t()) / (b.t() - a.t());
                return new LocalPoint(
                        a.x() + k * (b.x() - a.x()),
                        a.y() + k * (b.y() - a.y()),
                        a.altM() + k * (b.altM() - a.altM()),
                        t);
            }
        }
        return last;
    }

    private static List<LocalPoint> toLocal(List<Waypoint> waypoints, Waypoint origin) {
        double cosLat = Math.cos(Math.toRadians(origin.lat()));
        List<LocalPoint> out = new ArrayList<>(waypoints.size());
        for (Waypoint w : waypoints) {
            out.add(new LocalPoint(
                    (w.lon() - origin.lon()) * METERS_PER_DEG * cosLat,
                    (w.lat() - origin.lat()) * METERS_PER_DEG,
                    w.altM(),
                    w.t()));
        }
        return out;
    }

    /** 局部米制点（共原点平移系） */
    private record LocalPoint(double x, double y, double altM, long t) {
    }
}
