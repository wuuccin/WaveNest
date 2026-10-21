package com.waterlogistics.algo.meeting.model;

import java.util.List;

/**
 * 冲突结论 ＋ 绕行航迹（{@code B_} 前缀＝算法层 B 组产出）——字段照《81-B组算法方案》
 * §2.3 第（4）项一字不改。
 *
 * <p>边界（§2.3 口径）：本组只给<b>检测结论 ＋ 绕行几何</b>；<b>错峰挪时刻归 D 功能 7</b>，
 * 本对象不含「错峰后的起飞时刻」。{@code checksumVersion} 指向被查航迹的
 * {@code B_FlyableRoute.routeVersion}（判新旧只认版本字段）。
 *
 * <p>首版留桩（《43-编码分工说明》§1 简化口径）：{@code detourRoute} 为 {@code null}——
 * 绕行重规划（把对手航迹膨胀为动态障碍，§3.4 步骤 5）属精算版。
 *
 * @param conflictFlag      是否冲突
 * @param conflictSpaceTime 冲突时空区间（conflictFlag=true 时给出）
 * @param cpaPoint          最近接近点（包围盒粗筛或时间窗短路时为 null）
 * @param cpaT              最近接近点时刻，Unix 毫秒（同上）
 * @param cpaDistanceM      最近接近距离（三维），米（同上）
 * @param detourRoute       绕行航迹（时空参数化，结构同 waypoints[i]；首版留桩为 null）
 * @param checksumVersion   二查所用航迹版本（取被查航迹的 routeVersion）
 */
public record B_ConflictResult(

        boolean conflictFlag,

        ConflictSpaceTime conflictSpaceTime,

        CpaPoint cpaPoint,

        Long cpaT,

        Double cpaDistanceM,

        List<Waypoint> detourRoute,

        String checksumVersion) {

    public B_ConflictResult {
        if (checksumVersion == null || checksumVersion.isBlank()) {
            throw new IllegalArgumentException("checksumVersion 不能为空");
        }
        if (conflictFlag != (conflictSpaceTime != null)) {
            throw new IllegalArgumentException("冲突标志与冲突区间必须一致");
        }
        boolean cpaAllNull = cpaPoint == null && cpaT == null && cpaDistanceM == null;
        boolean cpaAllSet = cpaPoint != null && cpaT != null && cpaDistanceM != null;
        if (!cpaAllNull && !cpaAllSet) {
            throw new IllegalArgumentException("CPA 三字段须同时存在或同时为空");
        }
        if (cpaT != null && cpaT < 0L) {
            throw new IllegalArgumentException("CPA 时刻须为非负的 Unix 毫秒值，实际 " + cpaT);
        }
        if (cpaT != null && conflictSpaceTime != null
                && (cpaT < conflictSpaceTime.tBegin() || cpaT > conflictSpaceTime.tEnd())) {
            throw new IllegalArgumentException("CPA 时刻须落在冲突区间内，实际 cpaT=" + cpaT
                    + ", tBegin=" + conflictSpaceTime.tBegin()
                    + ", tEnd=" + conflictSpaceTime.tEnd());
        }
        if (cpaDistanceM != null && (!Double.isFinite(cpaDistanceM) || cpaDistanceM < 0.0)) {
            throw new IllegalArgumentException("CPA 距离须为非负有限数，实际 " + cpaDistanceM);
        }
        if (detourRoute != null) {
            if (!conflictFlag) {
                throw new IllegalArgumentException("无冲突时不应给出绕行航迹");
            }
            if (detourRoute.isEmpty()) {
                throw new IllegalArgumentException("绕行航迹不得为空列表");
            }
            for (int i = 0; i < detourRoute.size(); i++) {
                Waypoint waypoint = detourRoute.get(i);
                if (waypoint == null) {
                    throw new IllegalArgumentException("绕行航迹第 " + i + " 个航点为空");
                }
                if (i > 0 && waypoint.t() <= detourRoute.get(i - 1).t()) {
                    throw new IllegalArgumentException("绕行航迹须按时刻严格升序，下标 " + i + " 处不满足");
                }
            }
            detourRoute = List.copyOf(detourRoute);
        }
    }
}
