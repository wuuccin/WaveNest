package com.waterlogistics.algo.meeting.model;

import java.util.List;

/**
 * 船舶预测快照（M3 侧视图）——算法层 A 组完整 {@code PredictionSnapshot} 的<b>子集</b>
 * （《29-各组算法层接口定义》§3.1.1、《80-A组算法方案》§2.3）。
 *
 * <p>由 {@code TrajectorySnapshotPort} 取用（《42-包结构设计》§3.3：端口声明属 M3、
 * 实现属 M2、装配在 {@code wl-app}）；投影只裁字段、不改语义（《22-接口契约》§2.1）。
 *
 * <p><b>版本一致性是硬约束</b>：同一轮调度内所有候选组合共用同一版本快照，版本不一致
 * 即拒绝求解（《81-B组算法方案》§2.1 硬要求 1、《32-M3-动态会面》§4.2）；
 * 判新旧一律看 {@code version} 字段，不靠时间戳推测。
 *
 * @param version     快照版本号（一致性锚）
 * @param shipMmsi    目标船标识（对应 A 组快照字段 {@code shipMMSI}）
 * @param t0          快照生成时刻，Unix 毫秒
 * @param validitySec 预测有效期，秒
 * @param points      时间严格升序的预测点列（不含空元素；构造时防御拷贝；可为 null 表示无点列）
 */
public record ShipTrackSnapshot(

        String version,

        String shipMmsi,

        long t0,

        int validitySec,

        List<ShipPoint> points) {

    public ShipTrackSnapshot {
        if (points != null) {
            try {
                points = List.copyOf(points);
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("快照点列不能包含空元素", e);
            }
            for (int i = 1; i < points.size(); i++) {
                if (points.get(i).t() <= points.get(i - 1).t()) {
                    throw new IllegalArgumentException("快照点列时间必须严格升序");
                }
            }
        }
    }
}
