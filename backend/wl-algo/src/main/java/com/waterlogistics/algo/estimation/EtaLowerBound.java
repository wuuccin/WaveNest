package com.waterlogistics.algo.estimation;

import java.util.List;

/**
 * 时间可行性下界 {@code etaMin}——受理判定的<b>核心算法量</b>之一（{@code 80} §3.1.3）。
 *
 * <h3>算法</h3>
 * <pre>
 *   etaMin = t_handover_min + 2 · min_{t ∈ 预测点列} min_{j ∈ 机巢} dist(ship(t), nest_j) / v_max_cruise
 * </pre>
 * <p><b>为什么它是一个"下界"</b>：预测时域内的最近距离必然不超过真实会面点到最近巢的距离，
 * 故右侧 ≤ 真实飞行时间 ≤ 真实任务时长。也就是说：<b>若连这个下界都赶不上期望送达时间，
 * 那一定赶不上</b>（判 {@code TIME_INFEASIBLE} 是安全的；反过来不能据此判"一定赶得上"）。
 *
 * <p><b>为什么乘 2</b>：一趟任务至少"去程 + 返程"两段飞行（§3.1.3 原文口径）。
 *
 * <p><b>复杂度</b>：{@code O(N·M)}，{@code N} 为预测点数、{@code M} 为机巢数；
 * 典型 180 × 5 = 900 次距离计算，可忽略。
 *
 * <p><b>可解释性</b>：返回值同时给出 {@code nearestDistanceM} 与 {@code nearestNestId}，
 * 供调度结果解释"为什么判时间不可行"（门槛三要求）。
 */
public final class EtaLowerBound {

    /** 平面近似：1° 纬度对应米数。 */
    private static final double METERS_PER_DEG_LAT = ConstantVelocityPredictor.METERS_PER_DEG_LAT;

    private EtaLowerBound() {
    }

    /**
     * 机巢位置。
     *
     * @param nestId 机巢标识
     * @param lon    经度（项目统一坐标基准）
     * @param lat    纬度
     */
    public record NestPosition(String nestId, double lon, double lat) {
    }

    /**
     * 下界结果。
     *
     * @param etaLowerBoundSec 最短任务时长下界，秒
     * @param nearestDistanceM 预测时域内"船—最近机巢"的最小直线距离，m
     * @param nearestNestId    取到该最小距离的机巢标识；无可用机巢时为 {@code null}
     */
    public record Result(double etaLowerBoundSec, double nearestDistanceM, String nearestNestId) {
    }

    /**
     * 计算时间可行性下界。
     *
     * @param points         预测点列（至少 1 个点）
     * @param nests          候选机巢（至少 1 个）
     * @param vMaxCruiseMs   最大巡航速度，m/s（须为正；取机队上限，保证是"下界"）
     * @param tHandoverMinSec 最短交接耗时，秒（含接近/匹配/交接/脱离的最小合计）
     * @return 下界结果
     * @throws IllegalArgumentException 入参不满足计算前提时抛出
     */
    public static Result compute(List<EstimatedPoint> points, List<NestPosition> nests,
            double vMaxCruiseMs, double tHandoverMinSec) {

        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("预测点列不得为空——无预测则无法估计时间下界");
        }
        if (nests == null || nests.isEmpty()) {
            throw new IllegalArgumentException("候选机巢不得为空——无机巢则任务不可执行");
        }
        if (!(vMaxCruiseMs > 0.0) || !Double.isFinite(vMaxCruiseMs)) {
            throw new IllegalArgumentException("最大巡航速度必须为正的有限值：" + vMaxCruiseMs);
        }
        if (tHandoverMinSec < 0.0 || !Double.isFinite(tHandoverMinSec)) {
            throw new IllegalArgumentException("最短交接耗时不得为负：" + tHandoverMinSec);
        }

        double nearestDistance = Double.MAX_VALUE;
        String nearestNestId = null;

        for (NestPosition nest : nests) {
            for (EstimatedPoint point : points) {
                double distance = planarDistanceM(point.lon(), point.lat(), nest.lon(), nest.lat());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestNestId = nest.nestId();
                }
            }
        }

        double etaMinSec = tHandoverMinSec + 2.0 * nearestDistance / vMaxCruiseMs;
        return new Result(etaMinSec, nearestDistance, nearestNestId);
    }

    /**
     * 用下界判断"时间上是否可行"。
     *
     * @param deadlineTsMs     期望送达时刻，Unix 毫秒
     * @param nowTsMs          当前时刻，Unix 毫秒
     * @param etaLowerBoundSec 下界，秒
     * @return {@code true} 表示<b>下界就已超出</b>——必然赶不上，可安全判 {@code TIME_INFEASIBLE}
     */
    public static boolean isTimeInfeasible(long deadlineTsMs, long nowTsMs, double etaLowerBoundSec) {
        double availableSec = (deadlineTsMs - nowTsMs) / 1000.0;
        return availableSec < etaLowerBoundSec;
    }

    /** 两点平面近似距离，m（x=东、y=北，经度按平均纬度余弦缩放）。 */
    static double planarDistanceM(double lon1, double lat1, double lon2, double lat2) {
        double latRef = (lat1 + lat2) / 2.0;
        double dx = (lon2 - lon1) * METERS_PER_DEG_LAT * Math.cos(Math.toRadians(latRef));
        double dy = (lat2 - lat1) * METERS_PER_DEG_LAT;
        return Math.hypot(dx, dy);
    }
}
