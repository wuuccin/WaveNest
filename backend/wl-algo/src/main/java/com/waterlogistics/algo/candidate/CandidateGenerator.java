package com.waterlogistics.algo.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * C 组·候选机型/机巢/无人机生成（简化版，10-16）。
 *
 * <p>规则（《编码分工表》杨添爻 16 号任务）：读空闲且健康的无人机，按规则过滤，
 * 不做精算剪枝（两关剪枝留接口、实现顺延）。
 * 过滤链：
 * <ol>
 *   <li>实例状态＝空闲（占用/故障/充电中剔除）；</li>
 *   <li>电量 ≥ 最低保留（参数注入，演示默认 30）；</li>
 *   <li>载荷能力 ≥ 订单载荷；</li>
 *   <li>所在机巢允许该机型；</li>
 *   <li>机巢到取货点距离 ≤ 半径 R（参数注入，演示默认 5km）。</li>
 * </ol>
 * 输出候选三元组 (机型, 机巢, 无人机实例)＋粗略距离——与 62 算法方案的 CandidateCombo 语义一致（简化）。
 *
 * <p>换参数结果会变（真算法判据的弱化版）；后续升级为两关剪枝时只改本类内部，调用方零改动。
 */
public class CandidateGenerator {

    /** 生成结果 */
    public record CandidateCombo(
            String comboId,
            String type,
            String nestId,
            String droneId,
            /** 机巢到取货点距离（km，粗估直线） */
            double distKm,
            /** 剩余电量（%） */
            double battery,
            /**
             * 机型抗风上限（m/s，随实例从 {@link CandidateInput.DroneView#windLimitMs()} 透传）。
             *
             * <p><b>为什么要透传到组合上</b>：抗风是<b>机型级</b>属性，不是一个全局阈值。
             * 若可行性判定只吃一个调用方传入的统一上限，那么「同一风速下 A 型淘汰、B 型保留」
             * 永远不可能发生——因为两架机比的是同一个数。抗风上限必须跟着组合走到判定端，
             * 判定端才可能对不同机型得出不同结论（10-20 B1）。
             */
            double windLimitMs,
            /** 机型巡航速度（m/s，同 {@link #windLimitMs()} 透传；未登记为 {@code NaN}） */
            double cruiseSpeedMs) {
    }

    /** 标定参数（由调用方注入，读 sys_param，不硬编码——此处为注入默认值的载体） */
    public record Params(
            double minBatteryPercent,
            double maxNestDistanceKm) {

        public static Params defaults() {
            return new Params(30.0, 5.0);
        }
    }

    public List<CandidateCombo> generate(CandidateInput input, Params params) {
        List<CandidateCombo> out = new ArrayList<>();
        // nestId → nest 索引
        Map<String, CandidateInput.NestView> nestMap = new HashMap<>();
        for (CandidateInput.NestView n : input.nests()) {
            nestMap.put(n.nestId(), n);
        }

        int seq = 0;
        for (CandidateInput.DroneView d : input.drones()) {
            // 1) 状态过滤（照 23 §7 状态词）
            if (!"空闲".equals(d.status())) {
                continue;
            }
            // 2) 电量过滤
            if (d.battery() < params.minBatteryPercent()) {
                continue;
            }
            // 3) 载荷过滤
            if (d.maxPayloadKg() < input.payloadKg()) {
                continue;
            }
            CandidateInput.NestView nest = nestMap.get(d.nestId());
            if (nest == null) {
                continue;
            }
            // 4) 机巢允许该机型
            //    空列表 = 未登记 = 不限制机型（评审 MEDIUM.18）。
            //    原判断只看 null，而调用方在「字段为空白」时给的是空列表——
            //    于是「未登记」被当成「任何机型都不允许」，该巢的候选被静默清零。
            //    两种写法（null / 空列表）必须同义，否则同一份台账换个写法结果就变了。
            if (nest.allowedTypes() != null && !nest.allowedTypes().isEmpty()
                    && !nest.allowedTypes().contains(d.type())) {
                continue;
            }
            // 5) 距离过滤（等距直线粗估）
            double distKm = haversineKm(nest.lon(), nest.lat(), input.pickupLon(), input.pickupLat());
            if (distKm > params.maxNestDistanceKm()) {
                continue;
            }

            seq++;
            out.add(new CandidateCombo(
                    "CMB-" + String.format("%03d", seq),
                    d.type(), d.nestId(), d.droneId(),
                    round2(distKm), d.battery(),
                    // 机型能力随实例透传到组合：抗风/速度是判定端区分机型差异的唯一依据
                    d.windLimitMs(), d.cruiseSpeedMs()));
        }
        // 按距离升序（简化排序；正式排序键 rank_key 实现顺延）
        out.sort((a, b) -> Double.compare(a.distKm(), b.distKm()));
        return out;
    }

    private static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
