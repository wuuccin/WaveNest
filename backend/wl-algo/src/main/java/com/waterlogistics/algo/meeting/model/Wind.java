package com.waterlogistics.algo.meeting.model;

/**
 * 风况取值——代表风况与最不利风况共用（《81-B组算法方案》§3.7 参数名目）。
 *
 * <p><b>风向口径（本组约定，联调核对项）</b>：取气象惯例的「风的来向」——风从哪个方位吹来，
 * 0° 为正北、顺时针增大；与航向（去向）参照系相同。两份库内文档未写死该口径，
 * 若 M1 仿真数据实为「去向」，在任一侧加 180° 换算即可（约定记入本节）。
 *
 * @param speedMs 风速，m/s
 * @param dirDeg  风向（来向），度
 * @param gustMs  阵风，m/s（仅登记参数名目，当前无消费方）
 */
public record Wind(

        double speedMs,

        double dirDeg,

        double gustMs) {

    public Wind {
        if (!Double.isFinite(speedMs) || speedMs < 0.0) {
            throw new IllegalArgumentException("风速须为非负有限值，实际 " + speedMs);
        }
        if (!Double.isFinite(dirDeg) || dirDeg < 0.0 || dirDeg >= 360.0) {
            throw new IllegalArgumentException("风向须为 [0,360) 内的有限值，实际 " + dirDeg);
        }
        if (!Double.isFinite(gustMs) || gustMs < 0.0) {
            throw new IllegalArgumentException("阵风须为非负有限值，实际 " + gustMs);
        }
    }
}
