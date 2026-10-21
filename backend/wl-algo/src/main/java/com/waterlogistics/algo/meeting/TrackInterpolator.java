package com.waterlogistics.algo.meeting;

import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * 预测点列时间插值——点间线性插值求位置（《81-B组算法方案》§3.1 步骤 3）。
 *
 * <p>越界口径：早于首点取首点位置、晚于末点取末点位置——<b>不向外推</b>，外推会虚构
 * 「船停住／直行」的假轨迹；可求解时间窗由 {@link RendezvousSolver} 截到末点时刻。
 */
public final class TrackInterpolator {

    private TrackInterpolator() {
    }

    /**
     * 求快照点列在时刻 {@code t} 的插值位置。
     *
     * <p>点列非空、无空元素与严格升序由 {@link ShipTrackSnapshot} 构造期钉死，本类不再重复
     * 校验（该函数在求解内层循环里高频调用）；这里只防守空点列的退化入参。
     *
     * @param snapshot 船舶预测快照（构造期已校验点列不变量）
     * @param t        查询时刻，Unix 毫秒
     * @return {@code {经度, 纬度}}（经度统一回卷到 [-180, 180)）
     * @throws NullPointerException     快照或点列为 {@code null}
     * @throws IllegalArgumentException 点列为空
     */
    public static double[] positionAt(ShipTrackSnapshot snapshot, long t) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        List<ShipPoint> points = Objects.requireNonNull(snapshot.points(), "快照点列不能为空");
        if (points.isEmpty()) {
            throw new IllegalArgumentException("快照点列不能为空");
        }
        ShipPoint first = points.get(0);
        ShipPoint last = points.get(points.size() - 1);
        if (t <= first.t()) {
            return new double[] {TrackInterpolator.wrapLon(first.lon()), first.lat()};
        }
        if (t >= last.t()) {
            return new double[] {TrackInterpolator.wrapLon(last.lon()), last.lat()};
        }
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
        ShipPoint b = points.get(lo);
        ShipPoint a = points.get(lo - 1);
        double k = (double) (t - a.t()) / (b.t() - a.t());
        return new double[] {
                TrackInterpolator.wrapLon(a.lon() + k * TrackInterpolator.normalizeDLon(b.lon() - a.lon())),
                a.lat() + k * (b.lat() - a.lat())};
    }

    /**
     * 经差归一化到 [−180, 180]，跨 ±180° 走短侧。
     *
     * <p>前置：{@code |dLon| ≤ 360}（由 {@link ShipPoint} 的经纬度域保证）；恰好 ±180°
     * 时保留 {@code -180}，作为固定的边界取舍。
     */
    private static double normalizeDLon(double dLon) {
        if (dLon > 180.0) {
            return dLon - 360.0;
        }
        if (dLon < -180.0) {
            return dLon + 360.0;
        }
        return dLon;
    }

    /** 经度回卷到 [−180, 180) */
    private static double wrapLon(double lon) {
        double wrapped = (lon + 180.0) % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return wrapped - 180.0;
    }
}
