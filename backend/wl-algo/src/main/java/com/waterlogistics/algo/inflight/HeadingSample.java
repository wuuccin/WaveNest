package com.waterlogistics.algo.inflight;

/**
 * 滑窗内的一个样本：一次「实测 COG vs 预测航迹切向」的配对观测。
 *
 * <p>依据《84-E组算法方案》§2.5：把「预测航迹」视作虚拟源，算实测 COG 与
 * 预测航迹切向的滑窗平均航向差，作为「改向」的平滑特征。
 *
 * <p><b>归口铁律（《包结构设计》§二行 145）</b>：本样本量的是<b>船</b>，
 * 属于 E 组 {@code inflight/} 自产自用的<b>船侧</b>偏差特征；
 * 与 F 组 {@code measurement/}（宋冰鑫）的机侧偏航距离<b>同名不同物</b>
 * （量的是机、不是船），两者互不替代、不得互抢。
 *
 * <p><b>上游来源</b>：预测切向最终来自 A 组 {@code PredictionSnapshot}，
 * 且必须经 M2 常驻层取用完整对象（{@code 29} §3.5.2），
 * <b>不得从 WS 帧 {@code ship_traj} 反推</b>。
 * 当前契约中尚无 {@code PredictionSnapshot} 对象，故本类只接收已配对好的
 * （实测 COG，预测切向）数值对，使序 7 不被尚未交付的上游阻塞——
 * 按《10-15开工当日任务清单》§2.1 第 3 条「依赖没到一律用 stub 顶，不许空等」。
 *
 * @param cogDeg            实测航向 COG（度，正北为 0，顺时针增大）
 * @param predictedTangentDeg 同一时刻预测航迹的切向（度，同为顺时针口径）
 * @param sogMs             实测对地航速 SOG（m/s），仅用于低速冻结判定
 * @param tsMs              观测时刻（epoch 毫秒），仅用于追溯与日志
 */
public record HeadingSample(double cogDeg, double predictedTangentDeg, double sogMs, long tsMs) {
}
