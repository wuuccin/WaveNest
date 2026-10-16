package com.waterlogistics.algo.estimation;

import java.util.ArrayList;
import java.util.List;

/**
 * 匀速外推预测器（A 组 · estimation 子包）——<b>简化版</b>。
 *
 * <p>依据《编码分工表》§2.3② 与本日范围约定：15 号只做「<b>匀速外推 ＋ 固定噪声带</b>」，
 * 输出 {@code ShipTrajFrame} 所需载荷；完整的状态估计与多步预测（卡尔曼滤波、OU 过程、
 * 协方差传播、前提监测统计层）属 {@code 80-A组算法方案} §3.2 的<b>精算版</b>，
 * 排期顺延（《编码分工表》§六「A–G 算法精算版：完全不做」）。
 *
 * <p><b>为什么仍要独立成类、且参数全部注入</b>：精算版落地时只替换本类的实现，
 * 调用方签名与输出对象不变；且「换参数结果跟着变」是可验证的（见单测），
 * 避免被质疑写死。
 *
 * <p><b>本类为纯计算</b>：无 Spring、无 DB、无 MQ（《包结构设计》§二 铁律 4）。
 *
 * <p>模型：{@code p(τ) = p₀ + v·τ}，{@code v} 由对地航速/航向合成；
 * σ 线性增长 {@code σ(τ) = σ₀ + α·τ}（沿／横各一套参数）。平面近似按纬度缩放经度：
 * 1° 纬度 ≈ {@value #METERS_PER_DEG_LAT} m，1° 经度 ≈ 该值 × cos(φ)。
 */
public final class ConstantVelocityPredictor {

    /** 1° 纬度对应的米数（WGS84 球面近似，演示期足够）。 */
    public static final double METERS_PER_DEG_LAT = 111_320.0;

    /** 节 → m/s 的换算系数（1 kn = 1852 m / 3600 s）。 */
    public static final double KNOT_TO_MS = 1852.0 / 3600.0;

    /**
     * 低于此速度视为静止：不再外推，避免对地航速抖动被放大成虚假位移。
     */
    public static final double MIN_SPEED_MS = 0.05;

    private ConstantVelocityPredictor() {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 节 → m/s。
     *
     * <p>{@code AisMessage.sog} 保留源单位（节，见契约 javadoc），而算法层统一 SI，
     * 故由模块层在调用前用本方法换算。换算系数只此一处，禁止各处手写 0.514444。
     */
    public static double knotsToMs(double knots) {
        return knots * KNOT_TO_MS;
    }

    /**
     * 由一条船舶观测外推未来轨迹。
     *
     * @param mmsi    船舶 MMSI
     * @param lon     观测经度
     * @param lat     观测纬度
     * @param sogMs   对地航速，<b>m/s</b>（调用方已用 {@link #knotsToMs} 换算）
     * @param cogDeg  对地航向，度（自正北顺时针）
     * @param t0Ms    观测时刻，Unix 毫秒
     * @param params  预测参数（由调用方注入）
     * @return 预测结果；{@code points} 含 τ=0 的当前点
     * @throws IllegalArgumentException 输入超出物理/地理范围时抛出
     */
    public static PredictionResult predict(
            String mmsi,
            double lon,
            double lat,
            double sogMs,
            double cogDeg,
            long t0Ms,
            PredictionParams params) {

        validate(mmsi, lon, lat, sogMs, cogDeg);

        double courseDeg = normalizeDeg(cogDeg);
        double speedMs = sogMs < MIN_SPEED_MS ? 0.0 : sogMs;

        double courseRad = Math.toRadians(courseDeg);
        double vNorth = speedMs * Math.cos(courseRad);
        double vEast = speedMs * Math.sin(courseRad);

        // 经度方向的米/度；高纬处 cos(φ)→0，取 1 m 兜底避免除零
        double metersPerDegLon = Math.max(METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat)), 1.0);

        List<EstimatedPoint> points = new ArrayList<>(params.pointCount());
        for (long tauSec = 0; tauSec <= params.horizonSec(); tauSec += params.stepSec()) {
            double dNorth = vNorth * tauSec;
            double dEast = vEast * tauSec;

            double pointLat = lat + dNorth / METERS_PER_DEG_LAT;
            double pointLon = lon + dEast / metersPerDegLon;

            double sigmaAlongM = params.sigma0AlongM() + params.alphaAlongMs() * tauSec;
            double sigmaCrossM = params.sigma0CrossM() + params.alphaCrossMs() * tauSec;

            points.add(new EstimatedPoint(
                    t0Ms + tauSec * 1000L, pointLon, pointLat, sigmaAlongM, sigmaCrossM));
        }

        // 简化版恒判前提成立：完整前提监测（80 §3.2.8）排期顺延
        return new PredictionResult(
                mmsi, t0Ms, courseDeg, params.validitySec(), PremiseStatus.VALID, points);
    }

    /** 把任意角度归一到 [0, 360)。 */
    public static double normalizeDeg(double deg) {
        double normalized = deg % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static void validate(String mmsi, double lon, double lat, double sogMs, double cogDeg) {
        if (mmsi == null || mmsi.isBlank()) {
            throw new IllegalArgumentException("mmsi 不得为空");
        }
        if (!Double.isFinite(lon) || lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("经度超出范围：" + lon);
        }
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("纬度超出范围：" + lat);
        }
        if (!Double.isFinite(sogMs) || sogMs < 0.0) {
            throw new IllegalArgumentException("对地航速不得为负或非有限值：" + sogMs);
        }
        if (!Double.isFinite(cogDeg)) {
            throw new IllegalArgumentException("对地航向必须为有限值：" + cogDeg);
        }
    }
}
