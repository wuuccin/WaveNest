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
     * @param snapshot 船舶预测快照（点列时间严格升序、至少一点；空点列属入参退化，由调用方拦截；非升序在此拒绝）
     * @param t        查询时刻，Unix 毫秒
     * @return {@code {经度, 纬度}}
     */
    public static double[] positionAt(ShipTrackSnapshot snapshot, long t) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        List<ShipPoint> points = snapshot.points();
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("快照点列不能为空");
        }
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).t() <= points.get(i - 1).t()) {
                throw new IllegalArgumentException("快照点列时间必须严格升序");
            }
        }
        ShipPoint first = points.get(0);
        ShipPoint last = points.get(points.size() - 1);
        if (t <= first.t()) {
            return new double[] {first.lon(), first.lat()};
        }
        if (t >= last.t()) {
            return new double[] {last.lon(), last.lat()};
        }
        for (int i = 1; i < points.size(); i++) {
            ShipPoint b = points.get(i);
            if (t <= b.t()) {
                ShipPoint a = points.get(i - 1);
                double k = (double) (t - a.t()) / (b.t() - a.t());
                return new double[] {
                        a.lon() + k * (b.lon() - a.lon()),
                        a.lat() + k * (b.lat() - a.lat())};
            }
        }
        throw new IllegalStateException("内部不变量破坏：升序校验后仍未能定位插值区间");
    }
}
