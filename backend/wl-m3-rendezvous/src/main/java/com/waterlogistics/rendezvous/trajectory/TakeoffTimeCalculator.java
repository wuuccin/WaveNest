package com.waterlogistics.rendezvous.trajectory;

import com.waterlogistics.algo.meeting.model.B_TakeoffTimeResult;
import com.waterlogistics.algo.meeting.model.TakeoffInfeasibleReason;

import java.util.Objects;



/**
 * 起飞时刻回填（算式部分）——一个反推算式 ＋ 不可行回退判定（《81-B组算法方案》§3.3、
 * 《33-M3-路径规划》§3.3）。
 *
 * <p>口径（照文档）：
 * <ul>
 *   <li>基本式：起飞时刻 ＝ 会面时刻 − 精算飞行时长（装机耗时取 0，《50》决议 2 ④）；</li>
 *   <li>时间取整：<b>向下对齐</b>到时刻精度粒度网格（对齐到所在时隙起点，与占位时间窗
 *       同粒度——《37》§5.5；如收口方另有裁示可调）；</li>
 *   <li>不可行判定：对齐后结果<b>早于</b>当前时刻即不可行——精算时长已超出会面窗口可
 *       容忍的余量，退回上一环而不勉强派出（§3.3 要点 2）；</li>
 *   <li>只算不定案：时刻定案、回写占位、二次资源校验归 D 功能 7（§3.3 划界）。</li>
 * </ul>
 */
public final class TakeoffTimeCalculator {

    private TakeoffTimeCalculator() {
    }





    /**
     * 反推一个候选组合的起飞时刻。
     *
     * @param cmd 回填入参（全量注入；时长与粒度等前置由 {@link TakeoffTimeCommand} 构造期校验）
     * @return 算式结果；不可行时 {@code feasible=false}、原因「早于当前时刻」，
     *         {@code takeoffT} 仍给算式值（追溯用）
     */
    public static B_TakeoffTimeResult compute(TakeoffTimeCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");

        long rawT = cmd.meetTMs() - (long) cmd.tauFineSec() * 1000L;
        long takeoffT = rawT - Math.floorMod(rawT, cmd.timeGridMs());
        boolean feasible = takeoffT >= cmd.nowMs();

        return new B_TakeoffTimeResult(cmd.comboId(), takeoffT, feasible,
                                       feasible ? null : TakeoffInfeasibleReason.BEFORE_NOW, cmd.tauFineSec());
    }
}
