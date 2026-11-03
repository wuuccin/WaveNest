package com.waterlogistics.contract.ws;

import java.util.List;

/**
 * 规划航迹帧 {@code plan_track}——依据《22-接口契约》§4。
 *
 * <p>它把一次派发产出的三段航迹（去程／末段交接／返程）推给前端，供大屏按分段着色。
 * 与 {@code ship_traj} 的分工：本帧是<b>计划航迹</b>（机巢到会面点、会面点到交接点、
 * 交接点到归巢），{@code ship_traj} 是<b>船舶预测轨迹</b>，两者不可互相替代。
 *
 * <p><b>摘要口径</b>：{@code opType} 取 {@value #OP_TYPE}，写 {@code task_log} 的
 * {@code op_type} 列；一条派发只写一条摘要，不逐点落库。
 *
 * @param orderId  订单号
 * @param routeId  航迹编号，与 {@code B_FlyableRoute.routeId} 同源
 * @param comboId  组合编号，未组批时为 {@code null}
 * @param opType   摘要操作词，见 {@link #OP_TYPE}
 * @param segments 三段航迹，顺序固定为去程、末段交接、返程
 */
public record PlanTrackFrame(
        String orderId,
        String routeId,
        String comboId,
        String opType,
        List<Segment> segments) {

    /** 本帧在 {@code task_log} 摘要里使用的操作词。 */
    public static final String OP_TYPE = "规划航迹";

    /** 分段口径词，与《36-M5-可视化》§3 的三段着色一一对应。 */
    public static final String SEGMENT_OUTBOUND = "去程";
    public static final String SEGMENT_TERMINAL = "末段交接";
    public static final String SEGMENT_RETURN = "返程";

    public PlanTrackFrame {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 不能为空：规划航迹帧须可归属到订单");
        }
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("routeId 不能为空：规划航迹帧须可追溯航迹版本");
        }
        if (opType == null || opType.isBlank()) {
            throw new IllegalArgumentException("opType 不能为空：摘要操作词是 task_log 的落库口径");
        }
        if (segments == null) {
            throw new IllegalArgumentException("segments 不得为 null：无航迹应不发帧，而不是发空帧");
        }
        segments = List.copyOf(segments);
    }

    /**
     * 一段航迹。
     *
     * @param kind   分段口径词，取 {@link #SEGMENT_OUTBOUND}、{@link #SEGMENT_TERMINAL}、{@link #SEGMENT_RETURN}
     * @param points 该段航点，按时刻升序
     */
    public record Segment(String kind, List<Point> points) {

        public Segment {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("kind 不能为空：分段着色依赖分段口径词");
            }
            if (points == null) {
                throw new IllegalArgumentException("points 不得为 null：分段无点应不出现该段");
            }
            points = List.copyOf(points);
        }
    }

    /**
     * 航点。
     *
     * @param lon     经度，十进制度
     * @param lat     纬度，十进制度
     * @param altM    高度，米
     * @param speedMs 速度，米每秒
     * @param tMs     到达该点时刻，Unix 毫秒
     */
    public record Point(double lon, double lat, double altM, double speedMs, long tMs) {

        public Point {
            if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
                throw new IllegalArgumentException("经度须为 [-180,180] 内的有限数，实际 " + lon);
            }
            if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
                throw new IllegalArgumentException("纬度须为 [-90,90] 内的有限数，实际 " + lat);
            }
            if (!Double.isFinite(altM) || altM < 0.0) {
                throw new IllegalArgumentException("高度须为非负有限数，实际 " + altM);
            }
            if (!Double.isFinite(speedMs) || speedMs < 0.0) {
                throw new IllegalArgumentException("速度须为非负有限数，实际 " + speedMs);
            }
        }
    }
}
