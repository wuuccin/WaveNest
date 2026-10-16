package com.waterlogistics.algo.inflight;

/**
 * 滑窗航向差的计算结果，同时给出「平均」与「累计」两个口径。
 *
 * <p>之所以要两个口径，是因为《84》两处用到的量不同：
 * <ul>
 *   <li><b>§2.5 的特征</b>是滑窗<b>平均</b>航向差 → {@link #averageDeg()}；</li>
 *   <li><b>§2.2 的判据量</b>是滑窗内<b>累计</b>航向偏量 Θ(t) → {@link #accumulatedDeg()}。</li>
 * </ul>
 * 两者相差一个窗口长度因子，故一并输出，避免下游自行换算时口径分叉。
 *
 * @param averageDeg     §2.5 的平滑特征：窗口内各帧航向差的平均值（度，带符号）
 * @param accumulatedDeg §2.2 的判据量 Θ(t)：窗口内各帧航向差的累计和（度，带符号）
 * @param sameSign       窗口内各帧是否同号（滤除钟摆式抖动的前提，见 §2.2）
 * @param effectiveFrames 参与统计的有效帧数（低速冻结剔除后）
 */
public record HeadingWindowResult(double averageDeg, double accumulatedDeg,
                                  boolean sameSign, int effectiveFrames) {
}
