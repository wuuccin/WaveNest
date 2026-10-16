package com.waterlogistics.algo.inflight;

/**
 * 意图变更判据（6b）——「改向」分支，依据《84-E组算法方案》§2.2 ★★★。
 *
 * <p><b>判据（《84》§2.2 原文）</b>：
 * 「滑窗内累计航向偏量 Θ(t) 超阈值，<b>且窗口内各帧同号</b>（滤除钟摆式抖动）」。
 * 两个条件必须<b>同时</b>成立：只超阈值而各帧异号，是船在左右摆动而非改向，
 * 不能判为意图变更。
 *
 * <p><b>阈值由调用方注入，本类不读 {@code sys_param}、不硬编码。</b>
 * 理由有二：① {@code wl-algo} 铁律是无 Spring、无 DB；
 * ② E 组 26 项目前在 {@code 002_sys_param.sql} 中「仅中文名目，无 camelCase 键名」
 * （该文件第 9、100 行明确登记未灌入），按《10-15开工当日任务清单》§5.7 第 5 条
 * 「键名不确定就留空报组长，不臆造」，今天不自造键名。
 *
 * <p><b>归口边界（《29》§3.5.3 红线①）</b>：本类只判「意图是否变更」，
 * <b>不做</b>重调度决策、<b>不</b>在飞行中改派／换机；
 * 处置结论（继续追／放弃本次会面）由功能 9／功能 10 判档，见
 * {@link IntentDisposition}。
 */
public final class IntentChangeJudge {

    /**
     * 《84》§8 参数名目表给出的建议初值：改向累计航向偏量 {@code Θ_th = 20°}
     * （参考区间 15–30°）。
     *
     * <p><b>仅供单测与仿真起步使用</b>，不是生产取值。
     * 生产取值须由模块层从 {@code sys_param} 读出后注入
     * {@link #judge(HeadingWindowResult, double)}；
     * 对应的 camelCase 键名待组长登记后再落库。
     */
    public static final double SUGGESTED_THETA_TH_DEG = 20.0;

    private IntentChangeJudge() {
    }

    /**
     * 判定意图是否发生变更（改向）。
     *
     * @param result      {@link HeadingWindowDelta#compute} 的结果
     * @param thetaThDeg  阈值 Θ_th（度，由调用方注入，取绝对值比较）
     * @return 持续监测信号，其中 {@code persistent} 为滤抖后的最终结论
     */
    public static IntentChangeSignal judge(HeadingWindowResult result, double thetaThDeg) {
        double theta = result.accumulatedDeg();
        boolean changed = Math.abs(theta) > thetaThDeg;
        boolean persistent = changed && result.sameSign();
        return new IntentChangeSignal(changed, persistent, theta, result.sameSign());
    }
}
