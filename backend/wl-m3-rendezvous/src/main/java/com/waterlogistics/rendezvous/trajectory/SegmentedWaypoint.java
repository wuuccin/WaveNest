package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.algo.meeting.model.Waypoint;

import java.util.Objects;



/**
 * 带分段标签的航点——分段着色数据的最小单元。
 *
 * @param waypoint 航点（字段照《81-B组算法方案》§2.3 第（2）项）
 * @param segment  该点所属分段（《36-M5-可视化》§3 三段着色）
 */
@SuppressWarnings("MissingJavadoc")
public record SegmentedWaypoint(

        Waypoint waypoint,

        TrackSegment segment) {

    public SegmentedWaypoint {
        Objects.requireNonNull(waypoint, "waypoint 不能为空");
        Objects.requireNonNull(segment, "segment 不能为空");
    }
}
