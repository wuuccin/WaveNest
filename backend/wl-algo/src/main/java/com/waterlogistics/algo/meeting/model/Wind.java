package com.waterlogistics.algo.meeting.model;

/**
 * 风况取值——代表风况与最不利风况共用（《81-B组算法方案》§3.7 参数名目）。
 *
 * <p><b>风向口径（本组约定，联调核对项）</b>：取气象惯例的「风的来向」——风从哪个方位吹来，
 * 0° 为正北、顺时针增大；与航向（去向）参照系相同。两份库内文档未写死该口径，
 * 若 M1 仿真数据实为「去向」，在任一侧加 180° 换算即可（约定记入本节）。
 *
 * <p><b>约定已由单测钉死</b>：{@code dir == bearing} 为逆风、{@code dir == bearing + 180°}
 * 为顺风；接入侧若改传「去向」，须在入库前加 180° 换算。
 *
 * @param speedMs 风速，m/s
 * @param dirDeg  风向（来向），度
 * @param gustMs  阵风，m/s（0.0＝未提供；仅登记参数名目，当前无消费方）
 */
public record Wind(

        double speedMs,

        double dirDeg,

        double gustMs) {

    public Wind {
        if (!Double.isFinite(speedMs) || speedMs < 0.0) {
            throw new IllegalArgumentException("风速须为非负有限值，实际 " + speedMs);
        }
        if (!Double.isFinite(dirDeg)) {
            throw new IllegalArgumentException("风向须为有限值，实际 " + dirDeg);
        }
        if (!Double.isFinite(gustMs) || gustMs < 0.0) {
            throw new IllegalArgumentException("阵风须为非负有限值，实际 " + gustMs);
        }
        if (gustMs != 0.0 && gustMs < speedMs) {
            throw new IllegalArgumentException("阵风不应小于持续风速，实际 gust=" + gustMs + "、speed=" + speedMs);
        }
        // 仅接受合理的度值（含负角/超一圈的等价写法），其余视为上游单位或口径错误
        if (dirDeg < -360.0 || dirDeg > 720.0) {
            throw new IllegalArgumentException("风向超出合理范围（度），疑似单位/口径错误，实际 " + dirDeg);
        }
        // 归一化：360 → 0；-0.0 → 0.0（保证 record 等值语义一致）
        speedMs += 0.0;
        gustMs += 0.0;
        dirDeg = ((dirDeg % 360.0) + 360.0) % 360.0 + 0.0;
    }
}
