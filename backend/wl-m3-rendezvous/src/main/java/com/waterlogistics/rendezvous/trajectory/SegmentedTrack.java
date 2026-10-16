package com.waterlogistics.rendezvous.trajectory;

import java.util.List;

/**
 * 三段任务航迹（带分段标签）——大屏分段着色的演示数据结构
 * （《43-编码分工说明》§6.3）。时间整体单调不减，点序即飞行顺序。
 *
 * @param points 全部航点（去程 → 末段交接 → 返程 顺序拼接）
 */
public record SegmentedTrack(

        List<SegmentedWaypoint> points) {
}
