package com.waterlogistics.rendezvous.trajectory;



import java.util.List;
import java.util.Objects;



/**
 * 三段任务航迹（带分段标签）——大屏分段着色的演示数据结构
 * （《43-编码分工说明》§6.3）。时间整体单调不减，点序即飞行顺序。
 *
 * @param points 全部航点（去程 → 末段交接 → 返程 顺序拼接；构造时防御拷贝，外部再改不影响本值）
 */
@SuppressWarnings("MissingJavadoc")
public record SegmentedTrack(

        List<SegmentedWaypoint> points) {

    public SegmentedTrack {
        Objects.requireNonNull(points, "points 不能为空");
        for (SegmentedWaypoint point : points) {
            if (point == null) {
                throw new IllegalArgumentException("points 不能包含 null");
            }
        }
        points = List.copyOf(points);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("points 不能为空列表");
        }
        long prevT = Long.MIN_VALUE;
        for (SegmentedWaypoint point : points) {
            long t = point.waypoint().t();
            if (t <= prevT) {
                throw new IllegalArgumentException("航点时刻必须严格递增，实际 " + t + " 不晚于 " + prevT);
            }
            prevT = t;
        }
    }
}
