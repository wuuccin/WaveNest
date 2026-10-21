package com.waterlogistics.rendezvous.conflict;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_ConflictResult;
import com.waterlogistics.algo.meeting.model.B_FlyableRoute;
import com.waterlogistics.algo.meeting.model.ConflictSpaceTime;
import com.waterlogistics.algo.meeting.model.CpaPoint;
import com.waterlogistics.algo.meeting.model.Waypoint;

import java.util.ArrayList;
import java.util.Collection;
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
 * <p>冲突区间口径：{@code conflictSpaceTime} 取首末冲突采样点，<b>两端边界向内量化</b>，
 * 真实起点最多晚报、终点最多早报一个采样步长；中间空档可能被并段扩大。消费方须向两端
 * 各外扩一个 {@code sampleStepMs} 后再使用，不得按边界直接错峰。
 *
 * <p>检出限制：判据只在离散采样时刻求值，<b>最短可检冲突时长＝{@code sampleStepMs}</b>——
 * 更短且恰好落在两采样点之间的冲突会漏检（区间本身仍只会被保守扩大）。经度方向的米/度
 * 比例取两航迹首点纬度的均值，适用于十公里级、纬向跨度不大的作业区。
 *
 * <p>前置约束：两航迹的航点时刻须<b>严格递增</b>（时间窗与线性插值均基于此；不满足即
 * 抛 {@link IllegalArgumentException}）。
 */
public final class TrackConflictChecker {

    /**
     * 每度纬差对应的米数（与 GeoApprox 同一近似）
     */
    private static final double METERS_PER_DEG = GeoApprox.METERS_PER_DEG;





    private TrackConflictChecker() {
    }





    /**
     * 检测两条航迹的时空冲突。
     *
     * @param route  被查航迹（{@code checksumVersion} 取它的 {@code routeVersion}）
     * @param other  对照航迹
     * @param params 间隔与采样参数（调用方注入；数值前置由 {@link ConflictParams} 构造期校验）
     * @return 冲突结论；包围盒粗筛或时间窗短路时不产 CPA，绕行几何首版恒为 null
     * @throws IllegalArgumentException 入参航迹结构非法（由 {@code B_FlyableRoute} 构造期保证）
     * @throws NullPointerException     route／other／params 为 null
     */
    public static B_ConflictResult detect(B_FlyableRoute route, B_FlyableRoute other,
                                          ConflictParams params) {
        Objects.requireNonNull(route, "route 不能为空");
        Objects.requireNonNull(other, "other 不能为空");
        Objects.requireNonNull(params, "params 不能为空");

        Waypoint origin = route.waypoints().get(0);
        double refLat = (origin.lat() + other.waypoints().get(0).lat()) / 2.0;
        List<LocalPoint> routePoints = TrackConflictChecker.toLocal(route.waypoints(), origin, refLat);
        List<LocalPoint> otherPoints = TrackConflictChecker.toLocal(other.waypoints(), origin, refLat);

        B_ConflictResult shortCircuit = new B_ConflictResult(
                false, null, null, null, null, null, route.routeVersion()
        );

        long tStart = Math.max(routePoints.get(0).t(), otherPoints.get(0).t());
        long tEnd = Math.min(routePoints.get(routePoints.size() - 1).t(), otherPoints.get(otherPoints.size() - 1).t());
        if (tStart > tEnd) {
            return shortCircuit;
        }
        if (!TrackConflictChecker.areBoxesOverlapping(routePoints, otherPoints, params)) {
            return shortCircuit;
        }

        SamplingResult sampled = TrackConflictChecker.sampleConflicts(routePoints, otherPoints, tStart, tEnd, params);
        NearestApproach best = sampled.best();
        CpaPoint cpaPoint = new CpaPoint(
                TrackConflictChecker.wrapLon(origin.lon()
                                                     + best.midX / TrackConflictChecker.mPerDegLon(refLat)),
                origin.lat() + best.midY / TrackConflictChecker.METERS_PER_DEG,
                best.midAlt);
        ConflictSpaceTime interval = sampled.interval();

        return new B_ConflictResult(
                interval != null, interval, cpaPoint, Long.valueOf(best.t), Double.valueOf(best.d3), null, route.routeVersion());
    }





    /**
     * 时间同步采样（含两端点）——按「水平距离＜水平安全间隔 且 垂直间隔＜垂直安全间隔」记冲突区间，
     * 并全程／冲突段各跟踪一个三维最近接近点。
     *
     * @param routePoints 被查航迹局部米制点列
     * @param otherPoints 对照航迹局部米制点列
     * @param tStart      两航迹时间窗起点（采样含端点）
     * @param tEnd        两航迹时间窗终点（采样含端点）
     * @param params      间隔与采样参数
     * @return 冲突区间（无冲突为 null）与选定的最近接近点
     */
    private static SamplingResult sampleConflicts(List<LocalPoint> routePoints, List<LocalPoint> otherPoints,
                                                  long tStart, long tEnd, ConflictParams params) {
        NearestApproach bestAny = new NearestApproach();
        NearestApproach bestConflict = new NearestApproach();
        long firstConflictT = 0L;
        long lastConflictT = 0L;
        boolean anyConflict = false;

        long window = tEnd - tStart;
        long maxSamples = 200_000L;
        long step = params.sampleStepMs();
        if (window / step > maxSamples) {
            // 时间窗过长时按上限自适应放大步长，避免迭代膨胀（检出分辨率随之下调）
            step = window / maxSamples + 1L;
        }
        long t = tStart;
        while (true) {
            LocalPoint pa = TrackConflictChecker.interpolate(routePoints, t);
            LocalPoint pb = TrackConflictChecker.interpolate(otherPoints, t);
            double h = Math.hypot(pa.x() - pb.x(), pa.y() - pb.y());
            double v = Math.abs(pa.altM() - pb.altM());
            double d3 = Math.sqrt(h * h + v * v);
            bestAny.consider(d3, t, pa, pb);

            if (h < params.horizontalSepM() && v < params.verticalSepM()) {
                if (!anyConflict) {
                    firstConflictT = t;
                    anyConflict = true;
                }
                lastConflictT = t;
                bestConflict.consider(d3, t, pa, pb);
            }

            if (t == tEnd) {
                break;
            }
            t = (tEnd - t <= step) ? tEnd : t + step;
        }

        ConflictSpaceTime interval = anyConflict
                ? new ConflictSpaceTime(null, firstConflictT, lastConflictT)
                : null;
        return new SamplingResult(anyConflict ? bestConflict : bestAny, interval);
    }










    /**
     * 三维包围盒粗筛——§3.4 步骤 2：水平箱距（二维）与垂直箱隙<b>都</b>小于各自安全
     * 间隔才可能冲突，否则近零成本剔除。箱距是任意轨迹点对距离的下界，故剔除不丢真冲突。
     */
    private static boolean areBoxesOverlapping(Iterable<LocalPoint> routePoints,
                                               Iterable<LocalPoint> otherPoints,
                                               ConflictParams params) {
        double[] boxRoute = TrackConflictChecker.box(routePoints);
        double[] boxOther = TrackConflictChecker.box(otherPoints);
        double dxGap = Math.max((double) 0, Math.max(boxRoute[0] - boxOther[1], boxOther[0] - boxRoute[1]));
        double dyGap = Math.max((double) 0, Math.max(boxRoute[2] - boxOther[3], boxOther[2] - boxRoute[3]));
        double altGap = Math.max((double) 0, Math.max(boxRoute[4] - boxOther[5], boxOther[4] - boxRoute[5]));
        return Math.hypot(dxGap, dyGap) < params.horizontalSepM() && altGap < params.verticalSepM();
    }





    /**
     * 返回 {minX, maxX, minY, maxY, minAlt, maxAlt}
     */
    private static double[] box(Iterable<LocalPoint> points) {
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





    /**
     * 段内线性插值；越界取端点（不外推）
     */
    private static LocalPoint interpolate(List<LocalPoint> points, long t) {
        LocalPoint first = points.get(0);
        LocalPoint last = points.get(points.size() - 1);
        if (t <= first.t()) {
            return first;
        }
        if (t >= last.t()) {
            return last;
        }
        //noinspection DuplicatedCode
        int lo = 1;
        int hi = points.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t <= points.get(mid).t()) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        LocalPoint curr = points.get(lo);
        LocalPoint prev = points.get(lo - 1);
        double frac = (double) (t - prev.t()) / (double) (curr.t() - prev.t());
        return new LocalPoint(
                prev.x() + frac * (curr.x() - prev.x()),
                prev.y() + frac * (curr.y() - prev.y()),
                prev.altM() + frac * (curr.altM() - prev.altM()),
                t);
    }





    /**
     * 经度方向米/度——高纬处 cos(φ)→0，取 1 m 兜底避免除零／精度崩
     * （同 {@code ConstantVelocityPredictor} 既有写法，正反变换共用同一取值）。
     */
    private static double mPerDegLon(double lat) {
        return Math.max(TrackConflictChecker.METERS_PER_DEG * Math.cos(Math.toRadians(lat)), 1.0);
    }





    /**
     * 经差归一化到 [−180, 180]，跨 ±180° 走短侧
     */
    private static double normalizeLonDelta(double dLon) {
        if (dLon > 180.0) {
            return dLon - 360.0;
        }
        if (dLon < -180.0) {
            return dLon + 360.0;
        }
        return dLon;
    }





    /**
     * 经度回卷到 [−180, 180)
     */
    private static double wrapLon(double lon) {
        double wrapped = (lon + 180.0) % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return wrapped - 180.0;
    }





    @SuppressWarnings("ObjectAllocationInLoop")
    private static List<LocalPoint> toLocal(Collection<Waypoint> waypoints, Waypoint origin, double refLat) {
        double metersPerDegLon = TrackConflictChecker.mPerDegLon(refLat);
        List<LocalPoint> out = new ArrayList<>(waypoints.size());
        for (Waypoint w : waypoints) {
            out.add(new LocalPoint(
                    TrackConflictChecker.normalizeLonDelta(w.lon() - origin.lon()) * metersPerDegLon,
                    (w.lat() - origin.lat()) * TrackConflictChecker.METERS_PER_DEG,
                    w.altM(),
                    w.t()));
        }
        return out;
    }





    /**
     * 最近接近点跟踪器——采样循环里按三维距离打擂台的共用状态（全程与冲突段各持一份）。
     */
    private static final class NearestApproach {

        private double d3 = Double.POSITIVE_INFINITY;

        private long t;

        private double midX;

        private double midY;

        private double midAlt;





        private NearestApproach() {
            this.t = 0L;
            this.midX = (double) 0;
            this.midY = (double) 0;
            this.midAlt = (double) 0;
        }





        /**
         * 用当拍采样参与打擂台，更近则刷新记录。
         *
         * @param d3 当拍三维距离，米
         * @param t  当拍采样时刻，Unix 毫秒
         * @param pa 被查航迹当拍位置
         * @param pb 对照航迹当拍位置
         */
        private void consider(double d3, long t, LocalPoint pa, LocalPoint pb) {
            if (d3 < this.d3) {
                this.d3 = d3;
                this.t = t;
                this.midX = (pa.x() + pb.x()) / 2.0;
                this.midY = (pa.y() + pb.y()) / 2.0;
                this.midAlt = (pa.altM() + pb.altM()) / 2.0;
            }
        }
    }







    /**
     * 同步采样结果——冲突区间（无冲突时为 {@code null}）与选定的最近接近点。
     *
     * @param best     最近接近点（有冲突取冲突段内者，否则取全程者）
     * @param interval 冲突区间；{@code null} 表示无冲突
     */
    private record SamplingResult(NearestApproach best, ConflictSpaceTime interval) {
    }







    /**
     * 局部米制点（共原点平移系）
     */
    private record LocalPoint(double x, double y, double altM, long t) {
    }
}
