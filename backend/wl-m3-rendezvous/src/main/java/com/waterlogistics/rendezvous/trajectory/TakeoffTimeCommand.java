package com.waterlogistics.rendezvous.trajectory;

import java.util.Objects;

/**
 * 起飞时刻回填入参——「一个反推算式」（《81-B组算法方案》§3.3）：起飞时刻是输出不是输入；
 * 会面时刻来自 12、精算飞行时长来自 13，本项只用后者（两笔时长账不混）。
 *
 * <p>地面准备时间（装机耗时）按《50-决议记录》决议 2 ④ 暂不进入本算式；时刻精度粒度
 * 与占位时间窗同粒度（《37-M6-数据库》§5.5）。
 *
 * @param comboId    绑定的候选组合标识
 * @param meetTMs    会面时刻（来自会面点解的 t*），Unix 毫秒
 * @param tauFineSec 精算飞行时长 τ_fine，秒（来自 {@code B_FlyableRoute}）
 * @param nowMs      当前时刻，Unix 毫秒（不可行判定基准）
 * @param timeGridMs 时刻精度粒度，毫秒（取整对齐用；来源 {@code sys_param}）
 */
public record TakeoffTimeCommand(

        String comboId,

        long meetTMs,

        int tauFineSec,

        long nowMs,

        long timeGridMs) {

    public TakeoffTimeCommand {
        Objects.requireNonNull(comboId, "comboId 不能为空");
        if (comboId.isBlank()) {
            throw new IllegalArgumentException("comboId 不能为空白字符串");
        }
        if (tauFineSec <= 0) {
            throw new IllegalArgumentException("精算飞行时长必须为正，实际 " + tauFineSec);
        }
        if (timeGridMs <= 0) {
            throw new IllegalArgumentException("时刻精度粒度必须为正，实际 " + timeGridMs);
        }
    }
}
