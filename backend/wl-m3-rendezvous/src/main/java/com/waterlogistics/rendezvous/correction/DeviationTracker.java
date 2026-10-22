package com.waterlogistics.rendezvous.correction;

import com.waterlogistics.algo.inflight.CrossTrackError;

import java.util.List;
import java.util.Locale;
import java.util.Objects;



/**
 * 去程段追踪与纠偏循环体（功能 17）——<b>判据只出「前提是否成立」，不出修正指令</b>。
 *
 * <p>落款：《包结构设计》§3.3 行 107
 * 「{@code correction/  # ★ 去程段追踪与纠偏循环体（吴／罗，U-01：循环体在本模块侧、
 * 「每收到一次新状态」触发；判据调 algo.inflight）}」，负责人吴可欣，属 {@code wl-m3-rendezvous}。
 *
 * <p><b>U-01 裁决（去程纠偏循环体归 M3）</b>：循环体在模块三、<b>每收到一次新状态触发</b>，
 * 不由算法层主动发起；<b>FC 不直连 TP／RS</b>。本类即该循环体的触发与判据编排。
 *
 * <p><b>关键边界：本类不产出纠偏指令</b>。《84》§3.6 硬约束 1 与《51》§21.13 均已写明——
 * 6a 的动作形式是「<b>重算会面点请求（调 M3 服务）＋ 选择指令类型</b>」，
 * <b>不是自己算航向/速度修正量</b>。自算修正量会与 B 组的路径规划产生两个真相源，
 * 且违背《84》全文档口径。故本类只判「船是否还在按预测走」：
 * <ul>
 *   <li>仍按预测走 → <b>前提成立</b> → 继续原计划，无需动作；</li>
 *   <li>不再按预测走 → <b>前提失效</b> → 上报 M2，由 M2 决定走 6a 重算还是 6b 意图变更。</li>
 * </ul>
 *
 * <p><b>判据用哪个量</b>：《84》§2.1 船侧主偏差量是 <b>XTE（船位到预测航迹的横向偏差，带符号）</b>，
 * 退化版（无航道中心线）。<b>不是</b>机侧偏航距离——后者是功能 24 的量，
 * 《44》§3.7 已明确「同名不同物、互不替代」。
 *
 * <p><b>不做迟滞、不做滤波</b>：《84》§3.1 的迟滞双门限与 CUSUM 属功能 9 重调度的输出端复位，
 * 不在本循环体内；本类只做单周期判定，滤波与抑噪由 M2 侧编排。
 */
public final class DeviationTracker {

    private DeviationTracker() {
    }





    /**
     * 单周期判定：船是否仍沿预测航迹行驶。
     *
     * <p><b>触发时机（U-01）</b>：每收到一次船位新状态即调用一次，<b>不设内部定时器</b>。
     *
     * <p>本方法只判<b>横向偏差</b>（XTE）；沿航迹进度/ETA 不在覆盖范围，由调用方另行校验，
     * 不得仅凭本结论推断「时间上仍按预测走」。
     *
     * @param shipLon      船实测经度
     * @param shipLat      船实测纬度
     * @param forecastLons 预测航迹经度列（时间升序，长度 ≥ 2）
     * @param forecastLats 预测航迹纬度列（与经度列等长）
     * @param xtlM         当前航段走廊半宽（米），<b>由调用方按航段类型从 {@code sys_param} 选定</b>
     * @return 前提成立性判定结果
     * @throws NullPointerException     航迹列为 null
     * @throws IllegalArgumentException 航迹列长度不一致、点数不足、含非法数值或跨 ±180° 经线；走廊半宽非正
     */
    public static PremiseVerdict track(double shipLon, double shipLat,
                                       List<Double> forecastLons, List<Double> forecastLats, double xtlM) {
        Objects.requireNonNull(forecastLons, "forecastLons 不能为空");
        Objects.requireNonNull(forecastLats, "forecastLats 不能为空");
        if (!Double.isFinite(shipLon) || !Double.isFinite(shipLat)
                || shipLon < -180.0 || shipLon > 180.0 || shipLat < -90.0 || shipLat > 90.0
                || !Double.isFinite(xtlM) || xtlM <= (double) 0) {
            throw new IllegalArgumentException(
                    "船位与走廊半宽非法（经纬度须在合法范围内、半宽须为正的有限数），实际 shipLon="
                            + shipLon + ", shipLat=" + shipLat + ", xtlM=" + xtlM
            );
        }
        DeviationTracker.validateForecast(forecastLons, forecastLats);
        double signedXte = CrossTrackError.signedM(shipLon, shipLat, forecastLons, forecastLats);
        if (!Double.isFinite(signedXte)) {
            throw new IllegalArgumentException("横向偏差计算结果非有限，预测航迹入参非法");
        }
        double absXte = Math.abs(signedXte);
        boolean outside = CrossTrackError.outsideCorridor(absXte, xtlM);

        String absXteText = String.format(Locale.ROOT, "%.3f", Double.valueOf(absXte));
        String xtlText = String.format(Locale.ROOT, "%.3f", Double.valueOf(xtlM));
        return new PremiseVerdict(!outside, absXte, signedXte, xtlM, outside
                ? "偏出走廊：|XTE|=" + absXteText + "m > XTL=" + xtlText
                + "m，船不再沿预测航迹行驶，会面前提失效"
                : "走廊内：|XTE|=" + absXteText + "m ≤ XTL=" + xtlText + "m，前提仍成立");
    }





    /**
     * 校验预测航迹列——长度一致、点数不少于 2、逐点合法（非 null、有限数、经纬度范围）、不跨 ±180° 经线。
     *
     * @param forecastLons 预测航迹经度列（时间升序）
     * @param forecastLats 预测航迹纬度列（与经度列等长）
     * @throws IllegalArgumentException 任一校验不通过
     */
    private static void validateForecast(List<Double> forecastLons, List<Double> forecastLats) {
        int pointCount = DeviationTracker.requireUsableForecast(forecastLons, forecastLats);
        for (int i = 0; i < pointCount; i++) {
            Double lonI = forecastLons.get(i);
            Double latI = forecastLats.get(i);
            if (lonI == null || latI == null) {
                throw new IllegalArgumentException("预测航迹第 " + i + " 点经纬度不能为 null");
            }
            if (!Double.isFinite(lonI.doubleValue()) || !Double.isFinite(latI.doubleValue())
                    || lonI.doubleValue() < -180.0 || lonI.doubleValue() > 180.0
                    || latI.doubleValue() < -90.0 || latI.doubleValue() > 90.0) {
                throw new IllegalArgumentException("预测航迹第 " + i + " 点经纬度必须为合法范围内的有限数");
            }
        }
        for (int i = 1; i < pointCount; i++) {
            if (Math.abs(forecastLons.get(i).doubleValue() - forecastLons.get(i - 1).doubleValue()) > 180.0) {
                throw new IllegalArgumentException(
                        "预测航迹跨 ±180° 经线，请先归一化经度：index " + (i - 1) + " -> " + i);
            }
        }
    }





    /**
     * 校验航迹列规模——长度一致且不少于 2 点，返回点数。
     *
     * @param forecastLons 预测航迹经度列
     * @param forecastLats 预测航迹纬度列（与经度列等长）
     * @return 航迹点数（不少于 2）
     * @throws IllegalArgumentException 长度不一致或点数不足
     */
    private static int requireUsableForecast(List<Double> forecastLons, List<Double> forecastLats) {
        if (forecastLons.size() != forecastLats.size()) {
            throw new IllegalArgumentException("预测航迹经纬度列长度不一致：" + forecastLons.size() + " vs " + forecastLats.size());
        }
        if (forecastLons.size() < 2) {
            throw new IllegalArgumentException("预测航迹至少需 2 点，实为 " + forecastLons.size());
        }
        return forecastLons.size();
    }





    /**
     * 前提成立性判定结果。
     *
     * @param premiseHolds 预测前提是否仍成立（true＝继续原计划；false＝须上报 M2 决策）
     * @param absXteM      横向偏差绝对值（米）
     * @param signedXteM   横向偏差带符号值（米；正＝船在航迹左侧）
     * @param xtlM         判定所用走廊半宽（米）
     * @param reason       结论说明（供日志与 task_log 追溯）
     *
     *                     <p>前置：{@code absXteM == Math.abs(signedXteM)}（不得单独按展示精度取整），
     *                     且 {@code premiseHolds == (absXteM <= xtlM)}。
     */
    public record PremiseVerdict(
            boolean premiseHolds,
            double absXteM,
            double signedXteM,
            double xtlM,
            String reason) {

        @SuppressWarnings("MissingJavadoc")
        public PremiseVerdict {
            if (!Double.isFinite(absXteM) || !Double.isFinite(signedXteM) || !Double.isFinite(xtlM)) {
                throw new IllegalArgumentException(
                        "偏差与走廊半宽必须为有限数，实为 absXteM=" + absXteM + ", signedXteM=" + signedXteM + ", xtlM=" + xtlM
                );
            }
            if (xtlM <= (double) 0) {
                throw new IllegalArgumentException("走廊半宽须为正（米），实为 " + xtlM);
            }
            if (absXteM < (double) 0) {
                throw new IllegalArgumentException("横向偏差绝对值不得为负，实为 " + absXteM);
            }
            if (Math.abs(absXteM - Math.abs(signedXteM)) > 1.0e-9) {
                throw new IllegalArgumentException("横向偏差绝对值与带符号值不一致：absXteM=" + absXteM + ", signedXteM=" + signedXteM);
            }
            if (premiseHolds == CrossTrackError.outsideCorridor(absXteM, xtlM)) {
                throw new IllegalArgumentException(
                        "前提成立性与偏差／走廊半宽矛盾：absXteM=" + absXteM + ", xtlM=" + xtlM + ", premiseHolds=" + premiseHolds
                );
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("判定结果须给出结论说明");
            }
        }





        /**
         * 船是否偏在预测航迹左侧（供配合 ΔCOG 判改向意图，《84》§2.1 缺点③要求交叉判）。
         *
         * @return {@code true}＝带符号横向偏差为正，即船在预测航迹左侧；零偏差（正对航迹）
         * 与右侧同为 {@code false}，需区分时直接读 {@code signedXteM}
         */
        public boolean isLeftOfTrack() {
            return this.signedXteM > (double) 0;
        }
    }






}