package com.waterlogistics.algo.inflight;

import com.waterlogistics.algo.meeting.GeoApprox;
import java.util.List;

/**
 * 偏航距 XTE（横向偏差）计算——《84》§2.1「偏航距走廊判据 XTE / XTL」的退化版。
 *
 * <p><b>算的是船侧偏差，不是无人机偏航线</b>：把<b>上游预测航迹</b>当「计划航线」，
 * 每周期算<b>船位到预测航迹的垂直距离</b>，作船侧主偏差量。《44》§3.7 已专门提醒：
 * 宋冰鑫算的是<b>机侧</b>偏航距离（功能 24），本类是<b>船侧</b>特征，
 * <b>同名不同物、互不替代</b>，接口上一旦混用会污染 E 组纠偏标定。
 *
 * <p><b>为什么是「退化版」</b>：ECDIS 原判据是「船位到<b>航道中心线</b>的偏航距」，
 * 但本项目<b>无航道中心线 GIS 图层</b>（台账只有 {@code airspace_restriction} 与
 * {@code emergency_landing_point}），故退化为「到预测航迹的距离」——<b>失去航道语义</b>。
 * 这是《84》§2.1 写明的取舍，不是实现偷懒。
 *
 * <p><b>几何：ENU 局部平面 ＋ 叉积定符号</b>。
 * <ul>
 *   <li>经纬转米用<b>等距圆球近似</b>（度纬恒长、度经随纬度收缩），
 *       与 {@code GeoApprox} 同一口径，<b>不另立第二套米制定义</b>；</li>
 *   <li>符号取<b>叉积 {@code d × v}</b>：航迹向量 d 与「起点→船」向量 v 在 ENU 平面
       （x=东、y=北）的叉积为正 ⇔ 船在航迹<b>左侧</b>；</li>
 *   <li>垂足落在段外时退化为到端点距离，此时符号按「末段方向」继续沿用，避免
       「距离一样但符号跳变」导致下游按符号判改向意图时抖动。</li>
 * </ul>
 *
 * <p><b>不要用「航向差」定符号</b>：航向差（船的 COG 减航迹 COG）的正负与「左／右」
 * <b>不是一回事</b>，且随二者夹角越过 ±90° 翻转。用它定符号会让「北偏判成右偏」。
 * 这一点已由单测钉死。
 */
public final class CrossTrackError {

    /** 船位于航迹左侧时符号为正（航海惯例） */
    public static final double SIGN_LEFT = 1.0;

    /** 船位于航迹右侧时符号为负 */
    public static final double SIGN_RIGHT = -1.0;

    /** 每度对应的米数 */
    private static final double METERS_PER_DEG = GeoApprox.METERS_PER_DEG;

    private CrossTrackError() {
    }

    /**
     * 算船位到预测航迹的横向偏差（带符号）。
     *
     * <p>逐段取最小垂距：船位投影可能落在多段折线上，取最近的那段才是真垂距。
     *
     * @param shipLon      船实测经度
     * @param shipLat      船实测纬度
     * @param forecastLons 预测航迹经度列（时间升序，长度 ≥ 2）
     * @param forecastLats 预测航迹纬度列（与经度列等长）
     * @return 带符号横向偏差（米）；正＝船在航迹左侧
     * @throws IllegalArgumentException 航迹点数不足或两列长度不一致
     */
    public static double signedM(double shipLon, double shipLat,
            List<Double> forecastLons, List<Double> forecastLats) {
        if (forecastLons.size() != forecastLats.size()) {
            throw new IllegalArgumentException("预测航迹经纬度列长度不一致："
                    + forecastLons.size() + " vs " + forecastLats.size());
        }
        if (forecastLons.size() < 2) {
            throw new IllegalArgumentException("预测航迹至少需 2 点才能构成线段，实为 "
                    + forecastLons.size() + " 点：单点无方向、无垂距可言");
        }

        double bestAbs = Double.MAX_VALUE;
        double bestSigned = 0.0;

        for (int i = 0; i + 1 < forecastLons.size(); i++) {
            double lonA = forecastLons.get(i);
            double latA = forecastLats.get(i);
            double lonB = forecastLons.get(i + 1);
            double latB = forecastLats.get(i + 1);

            double[] a = toLocal(lonA, latA, lonA, latA);
            double[] b = toLocal(lonB, latB, lonA, latA);
            double[] p = toLocal(shipLon, shipLat, lonA, latA);
            double aE = a[0];
            double aN = a[1];
            double bE = b[0];
            double bN = b[1];
            double pE = p[0];
            double pN = p[1];

            double dE = bE - aE;
            double dN = bN - aN;
            double vE = pE - aE;
            double vN = pN - aN;

            double segLenSq = dE * dE + dN * dN;
            if (segLenSq <= 0) {
                // 退化段（两点重合）：无法构成方向，跳过而非当作零长度线段
                continue;
            }
            double segLen = Math.sqrt(segLenSq);

            // 垂足参数：投影落在段内取垂距，段外取到端点距离
            double t = (vE * dE + vN * dN) / segLenSq;
            double abs;
            double cross;
            if (t >= 0.0 && t <= 1.0) {
                // 正交投影落在段内：垂距 ＝ |叉积| / |d|
                cross = dE * vN - dN * vE;
                abs = Math.abs(cross) / segLen;
            } else if (t < 0.0) {
                // 垂足在起点之前：取到起点的距离
                abs = Math.sqrt(vE * vE + vN * vN);
                cross = dE * vN - dN * vE;
            } else {
                // 垂足在终点之后：取到终点的距离
                double wE = pE - bE;
                double wN = pN - bN;
                abs = Math.sqrt(wE * wE + wN * wN);
                cross = dE * wN - dN * wE;
            }

            if (abs < bestAbs) {
                bestAbs = abs;
                bestSigned = (cross >= 0 ? SIGN_LEFT : SIGN_RIGHT) * abs;
            }
        }

        if (bestAbs == Double.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "预测航迹各段均退化（相邻两点重合），无法计算横向偏差");
        }
        return bestSigned;
    }

    /** 横向偏差绝对值（米）——判「是否越出走廊」用它，符号无关 */
    public static double absM(double shipLon, double shipLat,
            List<Double> forecastLons, List<Double> forecastLats) {
        return Math.abs(signedM(shipLon, shipLat, forecastLons, forecastLats));
    }

    /**
     * 是否越出走廊半宽 XTL。
     *
     * <p>《84》§2.1：走廊半宽<b>按航段类型分段</b>（直行收紧、分汊口与港区放宽），
     * 不同航段预测质量不同、<b>不共用一值</b>；故 XTL 由调用方按当前航段类型选定后传入，
     * 本方法不内置任何默认走廊宽度（阈值一律读 {@code sys_param}）。
     *
     * @param xteM 横向偏差绝对值（米）
     * @param xtlM 当前航段走廊半宽（米），须为正
     * @return 是否越限
     */
    public static boolean outsideCorridor(double xteM, double xtlM) {
        if (xtlM <= 0) {
            throw new IllegalArgumentException("走廊半宽 XTL 须为正（米），实为 " + xtlM);
        }
        return xteM > xtlM;
    }

    /**
     * 经纬转 ENU 局部平面坐标（米），返回 {@code [东, 北]}。
     *
     * <p>度纬恒长、度经按本段起点纬度收缩（等距圆球近似），
     * 与 {@code GeoApprox.distanceM} 同一口径，故本类算出的米值与 B 组会面求解可互相印证。
     *
     * @param lon         目标点经度
     * @param lat         目标点纬度
     * @param originLon   本段起点经度
     * @param originLat   本段起点纬度
     * @return {@code [东向米数, 北向米数]}，原点为 {@code (originLon, originLat)}
     */
    private static double[] toLocal(double lon, double lat, double originLon, double originLat) {
        double midLatRad = Math.toRadians((lat + originLat) / 2.0);
        double east = (lon - originLon) * METERS_PER_DEG * Math.cos(midLatRad);
        double north = (lat - originLat) * METERS_PER_DEG;
        return new double[] {east, north};
    }
}