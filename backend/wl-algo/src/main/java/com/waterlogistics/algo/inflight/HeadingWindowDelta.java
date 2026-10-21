package com.waterlogistics.algo.inflight;

import java.util.List;

/**
 * 滑窗平均航向差（预测—实测一致性），依据《84-E组算法方案》§2.5 ★★★。
 *
 * <p><b>算法</b>：把「预测航迹」视作虚拟源，逐帧取
 * {@code 实测 COG − 预测航迹切向}，做<b>角度环绕</b>归一化后，
 * 在窗口内求平均（§2.5 特征）与累计（§2.2 判据量 Θ(t)）。
 *
 * <p><b>为什么不用单帧 ΔCOG</b>：§2.5 原文指出它「比单帧 ΔCOG 抗噪、
 * 天然连续量」，且「比单帧 ΔCOG 更适合作 CUSUM 输入」。
 *
 * <p><b>低速冻结</b>：§2.5 明确指出「低速时同样冻结」——低速时 COG 不可信，
 * 须冻结 COG 判据、改由位移矢量反算航迹向。此处以 {@code minSogMs} 参数化，
 * 低于该速度的样本直接剔除；<b>默认 0 表示不冻结</b>。
 * 该阈值的 {@code sys_param} 键名目前未定（E 组 26 项仅有中文名目），
 * 故参数化而不硬编码，守《10-15开工当日任务清单》§5.7 第 5 条「不臆造」。
 *
 * <p><b>设计：无状态纯函数</b>。本类不持有环形缓冲，窗口由调用方（模块层）维护。
 * 理由：① {@code wl-algo} 铁律是纯计算、可脱离全项目单测；
 * ② 滑窗的推进与保留属模块层职责（检测归 M4、决策归 M2），
 * 算法层不应持有飞行中的运行时状态。
 *
 * <p><b>归口铁律（《包结构设计》§二行 145）</b>：本类量的是<b>船侧</b>偏差，
 * 与 F 组 {@code measurement/} 的机侧偏航量<b>同名不同物</b>，不得互抢。
 */
public final class HeadingWindowDelta {

    private HeadingWindowDelta() {
    }

    /**
     * 角度环绕归一化，把任意角度差折算到 {@code [-180, 180)}。
     *
     * <p>§2.5 原文提醒「角度环绕有小坑」，典型坑是 350° 与 10° 相减得 ±340°，
     * 不做环绕会得到完全错误的量级。本方法保证：
     * {@code 350 − 10 = 340 → −20}（而非 −340/340）。
     *
     * <p>加 540 再取模 360，是为了让被除数恒为非负（航向差实际落在 {@code (-360, 360)}），
     * 规避 Java {@code %} 对负数返回负结果导致的第二次环绕错误。
     *
     * @param rawDeg 未归一化的角度差（度）
     * @return 归一化到 {@code [-180, 180)} 的带符号角度差（度）
     */
    public static double normalizeDeltaDeg(double rawDeg) {
        return ((rawDeg + 540.0) % 360.0) - 180.0;
    }

    /**
     * 计算滑窗航向差（不做低速冻结）。
     *
     * @param window 窗口样本，按时间由旧到新
     * @return 平均／累计／同号／有效帧数
     */
    public static HeadingWindowResult compute(List<HeadingSample> window) {
        return compute(window, 0.0);
    }

    /**
     * 计算滑窗航向差，可指定低速冻结阈值。
     *
     * <p>「同号」判定：窗口内所有帧的带符号航向差同为非负，或同为非正。
     * 取等号两侧皆相容，是为了让「完全贴合预测航迹」（各帧恒为 0）不被误判为异号；
     * 但其累计量 Θ(t) 为 0，本就不会过阈值。
     *
     * <p>空窗口返回全 0 且 {@code sameSign = true}（空集上的 vacuous truth），
     * 累计量为 0，因此下游不会判出意图变更。
     *
     * @param window    窗口样本，按时间由旧到新
     * @param minSogMs  低速冻结阈值（m/s）；{@code <= 0} 表示不冻结
     * @return 平均／累计／同号／有效帧数
     */
    public static HeadingWindowResult compute(List<HeadingSample> window, double minSogMs) {
        double sum = 0.0;
        int effective = 0;
        boolean allNonNegative = true;
        boolean allNonPositive = true;

        for (HeadingSample sample : window) {
            if (minSogMs > 0.0 && sample.sogMs() < minSogMs) {
                continue;
            }
            double delta = normalizeDeltaDeg(sample.cogDeg() - sample.predictedTangentDeg());
            sum += delta;
            effective++;
            if (delta < 0.0) {
                allNonNegative = false;
            }
            if (delta > 0.0) {
                allNonPositive = false;
            }
        }

        double average = effective == 0 ? 0.0 : sum / effective;
        return new HeadingWindowResult(average, sum, allNonNegative || allNonPositive, effective);
    }
}
