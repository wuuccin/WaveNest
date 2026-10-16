package com.waterlogistics.monitor.taskstate;

import com.waterlogistics.algo.measurement.RouteMeasurement;

/**
 * 进度与 ETA 计算（10-16 任务 3.1 之三）——<b>简化实现，不追求精度</b>。
 *
 * <p><b>边界：本类不重算位置投影、不重算剩余距离。</b>
 * 两者都是 F 组功能 20–23 的产出（{@code 85} §5.2 {@code progress_result}、
 * {@code 85} §6.2 {@code remaining_route_result}），已由
 * {@link RouteMeasurement#progress} 与 {@link RouteMeasurement#remaining} 实现。
 * 本类只做最后一步算术：<b>剩余距离 ÷ 地速 → 到达时刻</b>。
 *
 * <p>这样做是为了避免同一口径出现两套算法——重算一遍位置投影，
 * 监控侧与检测侧会对同一条航线给出不同的「进度」，事后无法判断该信谁。
 *
 * <p><b>口径硬约束（{@code 84} 第5章「易混点」）：</b>
 * 「返航或偏离航线时<b>不得用直线距离冒充本功能航程</b>，转交 E 功能 10 粗估、B 功能 16 精算」。
 * 故 {@link RouteMeasurement.Remaining} 在返航／偏航时 {@code computable=false}，
 * 本类<b>原样透传其 reason，不自行兜底、不拿直线距离凑一个 ETA</b>。
 *
 * <p><b>阈值一律由调用方注入</b>（《10-16 清单》§四「阈值一律读 {@code sys_param}，
 * 不确定就报组长不臆造」）：本类不读 {@code sys_param}、不硬编码任何阈值。
 *
 * @param computable          是否可算出 ETA；为 {@code false} 时其余数值字段无意义
 * @param etaMs               预计到达时刻，Unix 毫秒；不可算时为 {@code null}
 * @param reason              不可算的原因码；可算时为 {@code null}
 * @param remainingM          剩余距离，米（取自 F 组 {@code remaining_route_result}）
 * @param groundSpeedMs       本次采用的地速，米每秒；不可算时为 {@code null}
 * @param calculationQuality  几何品质标签，<b>原样透传 F 组口径</b>
 *                             （{@code observed_geometry}／{@code imputed_geometry}／{@code not_computable}），
 *                             本类不另立标签
 */
public record ProgressEta(
        boolean computable,
        Long etaMs,
        String reason,
        double remainingM,
        Double groundSpeedMs,
        String calculationQuality) {

    /** 原因码：F 组判剩余距离不可算（返航／偏航／航路版本不匹配／落点不可用等），原样透传其 reason */
    public static final String REASON_REMAINING_NOT_COMPUTABLE = "REMAINING_NOT_COMPUTABLE";

    /** 原因码：地速缺失、非有限或低于 {@code minGroundSpeedMs} 下限，不足以做除法 */
    public static final String REASON_SPEED_UNAVAILABLE = "SPEED_UNAVAILABLE";

    public ProgressEta {
        if (computable && (etaMs == null || groundSpeedMs == null || calculationQuality == null)) {
            throw new IllegalArgumentException("可算时 etaMs／groundSpeedMs／calculationQuality 必须在位");
        }
        if (!computable && etaMs != null) {
            throw new IllegalArgumentException("不可算时不得给出 etaMs，避免下游误当可用值");
        }
    }

    /**
     * 由 F 组剩余距离与当前地速估 ETA。
     *
     * @param remaining       F 组 {@code remaining_route_result}（{@code RouteMeasurement.remaining} 产出）
     * @param groundSpeedMs   当前地速，米每秒（建议取 F 组清洗后状态的口径，不取 M4 原始遥测）
     * @param minGroundSpeedMs 地速下限，米每秒；低于此值判 {@link #REASON_SPEED_UNAVAILABLE}。
     *                         <b>由调用方自 {@code sys_param} 注入</b>，本类不设默认值
     * @param nowMs           当前时刻，Unix 毫秒（注入而非读系统时钟，便于单测与回放复现）
     * @return ETA 结论；不可算时按上述两个原因码给出
     */
    public static ProgressEta estimate(RouteMeasurement.Remaining remaining,
                                       double groundSpeedMs,
                                       double minGroundSpeedMs,
                                       long nowMs) {
        if (remaining == null) {
            throw new IllegalArgumentException("remaining 必填：F 组 remaining_route_result 缺失时不得凭空造 ETA");
        }
        if (!remaining.computable() || remaining.distanceM() == null
                || !Double.isFinite(remaining.distanceM())) {
            // 返航／偏航等情形：F 组已判不可算，原样透传原因，不拿直线距离兜底
            return new ProgressEta(false, null, REASON_REMAINING_NOT_COMPUTABLE, 0.0, null,
                    remaining.calculationQuality() == null ? "not_computable" : remaining.calculationQuality());
        }
        if (!Double.isFinite(groundSpeedMs) || groundSpeedMs <= 0 || groundSpeedMs < minGroundSpeedMs) {
            return new ProgressEta(false, null, REASON_SPEED_UNAVAILABLE, remaining.distanceM(), null,
                    remaining.calculationQuality());
        }
        long eta = nowMs + Math.round(remaining.distanceM() / groundSpeedMs * 1000.0);
        return new ProgressEta(true, eta, null, remaining.distanceM(), groundSpeedMs,
                remaining.calculationQuality());
    }
}