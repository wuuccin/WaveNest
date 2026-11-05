package com.waterlogistics.algo.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * C 组·候选机型/机巢/无人机生成。
 *
 * <p>规则（《编码分工表》杨添爻 16 号任务）：读空闲且健康的无人机，按规则过滤，
 * 不做精算剪枝（两关剪枝留接口、实现顺延）。
 *
 * <h3>★ 11-05 改为「巢 × 机」双层枚举（阶段三 · 序号 1 · 硬判据 1）</h3>
 * <p><b>原缺陷</b>：{@code nestMap.get(d.nestId())} 把机巢<b>锁死为无人机所在巢</b>，
 * 一架机只产出一个候选，候选数恒等于机数。于是「货在北巢、机在南巢」这类真实场景下，
 * 北巢的机没有被评估的机会，候选集里不可能出现「非无人机当前所在巢」的组合。
 *
 * <p><b>新口径</b>：外层遍历 {@code input.nests()}、内层遍历 {@code input.drones()}，
 * 每个「巢 × 机」组合按下面的过滤链逐条判；通过即产出一个候选。
 * 候选数＝巢 × 机的<b>可行</b>组合数，不再是机数。
 *
 * <p>过滤链（顺序即短路顺序，先便宜后贵）：
 * <ol>
 *   <li>实例状态＝空闲（占用/故障/充电中剔除）；</li>
 *   <li>电量 ≥ 最低保留（参数注入，演示默认 30）——<b>电量未登记不再按 0 静默淘汰</b>（MED-BUG.12）；</li>
 *   <li>载荷能力 ≥ 订单载荷——<b>载荷未登记同上</b>（MED-BUG.12）；</li>
 *   <li>机巢允许该机型；</li>
 *   <li>该巢还有空位（容量闸）；</li>
 *   <li>机巢到<b>货点</b>距离 ≤ 半径 R（参数注入，演示默认 5km）。</li>
 * </ol>
 *
 * <p>输出候选三元组 (机型, 机巢, 无人机实例)＋巢到货点距离——与 62 算法方案的
 * CandidateCombo 语义一致。
 *
 * <h3>★ 距离基准 11-05 起改为「巢 → 货点」</h3>
 * <p>原基准是「巢 → 船位」。船一直在动，同一张订单在不同时刻算出的 distKm 会漂，
 * 而真正决定这架机来不来得及的是「从巢飞到货点取货」这段。有货点用货点，
 * 没给货点（旧口径调用方）才退回船位——见 {@link CandidateInput#distanceBaseLon()}。
 *
 * <h3>★ 输入全部无效时抛而不是返回空</h3>
 * <p>{@code nests} 或 {@code drones} 为空时返回空列表（调用方已在上游判过，此处只是
 * 不制造异常）；<b>货巢已指定却查不到坐标</b>（{@link CandidateInput#hasUnresolvedCargoNest()}）
 * 时也返回空列表，且<b>不退回船位口径</b>——上游 {@code CandidateInputAssembler} 已打 WARN
 * 写明是哪个货巢，生成端不再二次判断、只负责不产出（见卢肄文 11-05 字段表）。
 */
public class CandidateGenerator {

    /** 生成结果 */
    public record CandidateCombo(
            String comboId,
            String type,
            String nestId,
            String droneId,
            /** 机巢到货点距离（km，粗估直线） */
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
            double cruiseSpeedMs,
            /**
             * 该组合的<b>起点机巢</b>在巢视图（11-05 新增）。
             *
             * <p>为什么把巢视图塞进组合：巢×机枚举后，「哪一架机、从哪个巢出发」
             * 是两个独立维度，下游（航迹起点、会面点求解）必须拿到 <b>候选自己的巢</b>，
             * 不能再去 {@code drone.nestId()} 反查——那正是被本次改掉的旧假设。
             * 同时容量、可用机型等台账属性也随它走，下游不必再查一次库。
             */
            CandidateInput.NestView nest) {

        /** 兼容构造：不带巢视图（旧调用方/测试桩）。 */
        public CandidateCombo(String comboId, String type, String nestId, String droneId,
                double distKm, double battery, double windLimitMs, double cruiseSpeedMs) {
            this(comboId, type, nestId, droneId, distKm, battery, windLimitMs, cruiseSpeedMs, null);
        }
    }

    /** 标定参数（由调用方注入，读 sys_param，不硬编码——此处为注入默认值的载体） */
    public record Params(
            double minBatteryPercent,
            double maxNestDistanceKm) {

        public static Params defaults() {
            return new Params(30.0, 5.0);
        }
    }

    /**
     * 生成候选。
     *
     * @param input  候选输入（含巢列表、机列表、货点）
     * @param params 标定参数
     * @return 候选列表，按 {@code distKm} 升序；无可行组合时为空列表（<b>不为 null</b>）
     * @throws IllegalArgumentException {@code input}／{@code params} 为 {@code null}
     */
    public List<CandidateCombo> generate(CandidateInput input, Params params) {
        if (input == null) {
            throw new IllegalArgumentException("input 不能为 null：调用方前置校验缺失");
        }
        if (params == null) {
            throw new IllegalArgumentException("params 不能为 null：调用方前置校验缺失");
        }

        final double baseLon = input.distanceBaseLon();
        final double baseLat = input.distanceBaseLat();
        if (input.hasUnresolvedCargoNest()) {
            // 货巢已指定但查不到坐标（「脏货巢」）：一个组合都不产出。
            // 不能退回船位口径——那会产出「巢 → 船位」的候选，距离基准错且现场看不出异常
            // （卢肄文 11-05 字段表：脏货巢应不提供该组合，不要退化到「无人机所在巢」）。
            // 上游 CandidateInputAssembler 已打 WARN 写明是哪个货巢，这里只负责不产出，
            // 避免「订单说货在北巢、候选却按船位算距离」这种静默错配。
            return List.of();
        }
        if (!isUsablePoint(baseLon, baseLat)) {
            // 货点与船位都拿不到 → 距离无从计算。返回空而不是抛：这不是调用方缺陷，
            // 是「订单没给船位且没给货巢」的数据问题，上游 CandidateInputAssembler
            // 已对 ship_lon/lat 做过快速失败守卫，能走到这里说明是旧口径调用方。
            return List.of();
        }

        List<CandidateCombo> out = new ArrayList<>();
        int seq = 0;
        // ★ 外层巢、内层机（11-05）：候选数＝巢×机可行数，不再是机数
        for (CandidateInput.NestView nest : input.nests()) {
            if (!isUsablePoint(nest.lon(), nest.lat())) {
                continue;
            }
            if (!nest.hasFreeSlotFor()) {
                continue;
            }
            // 距离只与巢和货点有关，与机无关——提到内层循环外算一次
            double distKm = haversineKm(nest.lon(), nest.lat(), baseLon, baseLat);
            if (distKm > params.maxNestDistanceKm()) {
                continue;
            }
            for (CandidateInput.DroneView d : input.drones()) {
                // 1) 状态过滤（照 23 §7 状态词）
                if (!"空闲".equals(d.status())) {
                    continue;
                }
                // 2) 电量过滤——未登记（NaN）时不再当 0 淘汰（MED-BUG.12），改由上游告警
                if (d.hasBattery() && d.battery() < params.minBatteryPercent()) {
                    continue;
                }
                // 3) 载荷过滤——未登记（NaN）同上，不再静默淘汰
                if (d.hasMaxPayload() && d.maxPayloadKg() < input.payloadKg()) {
                    continue;
                }
                // 4) 机巢允许该机型
                //    空列表 = 未登记 = 不限制机型（评审 MEDIUM.18）。
                //    原判断只看 null，而调用方在「字段为空白」时给的是空列表——
                //    于是「未登记」被当成「任何机型都不允许」，该巢的候选被静默清零。
                //    两种写法（null / 空列表）必须同义，否则同一份台账换个写法结果就变了。
                if (!nest.allowedTypes().isEmpty() && !nest.allowedTypes().contains(d.type())) {
                    continue;
                }

                seq++;
                out.add(new CandidateCombo(
                        "CMB-" + String.format("%03d", seq),
                        d.type(), nest.nestId(), d.droneId(),
                        round2(distKm), d.battery(),
                        // 机型能力随实例透传到组合：抗风/速度是判定端区分机型差异的唯一依据
                        d.windLimitMs(), d.cruiseSpeedMs(), nest));
            }
        }
        // 按距离升序；同距离时按「机归属巢优先」次排——货巢＝机巢的组合排在前面，
        // 这样「货巢＝机巢时结果与现状一致」在排序上也成立（硬判据 1 后半句）。
        out.sort((a, b) -> {
            int byDist = Double.compare(a.distKm(), b.distKm());
            if (byDist != 0) {
                return byDist;
            }
            return Boolean.compare(!isHomeNestCombo(input, a), !isHomeNestCombo(input, b));
        });
        return out;
    }

    /** 该组合的巢是否就是该机所在巢。 */
    private static boolean isHomeNestCombo(CandidateInput input, CandidateCombo combo) {
        for (CandidateInput.DroneView d : input.drones()) {
            if (d.droneId().equals(combo.droneId())) {
                return combo.nestId() != null && combo.nestId().equals(d.nestId());
            }
        }
        return false;
    }

    private static boolean isUsablePoint(double lon, double lat) {
        return Double.isFinite(lon) && Double.isFinite(lat)
                && lon >= -180.0 && lon <= 180.0 && lat >= -90.0 && lat <= 90.0;
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
