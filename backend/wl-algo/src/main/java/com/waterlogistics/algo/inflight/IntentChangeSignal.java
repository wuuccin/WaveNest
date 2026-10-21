package com.waterlogistics.algo.inflight;

/**
 * 意图变更持续监测信号，依据《29-各组算法层接口定义》§3.5.1(1b)。
 *
 * <p>{@code 29} 原文：「意图变更持续监测信号 | 船侧偏差是否持续成立
 * （含持续成立标志） | 6b 判据／功能 9」。故本信号必须<b>同时</b>给出
 * 「超阈值」与「持续成立」两层结论，不能只给一个布尔。
 *
 * @param changed        Θ(t) 是否超过阈值（未滤抖的粗判据）
 * @param persistent     是否持续成立＝超阈值<b>且</b>窗口内各帧同号；
 *                        这是《84》§2.2 的完整判据，也是功能 9 应采信的结论
 * @param accumulatedDeg 判据量 Θ(t)（度，带符号）
 * @param sameSign       窗口内各帧是否同号
 */
public record IntentChangeSignal(boolean changed, boolean persistent,
                                 double accumulatedDeg, boolean sameSign) {
}
