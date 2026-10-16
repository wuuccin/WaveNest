package com.waterlogistics.rendezvous.handover;

import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import java.util.List;

/**
 * 末段会面与交接判定（简化版）——把「船体随动坐标系下的前馈＋反馈控制」简化为
 * <b>相对量序列判据推进</b>（《81-B组算法方案》§3.6、《32-M3-动态会面》§3.4／§3.5）。
 *
 * <p><b>简化口径（《43-编码分工说明》§1；答辩口径「已规划、实现顺延」）</b>：
 * <ul>
 *   <li>首版不建控制回路，按采样序列判四子阶段推进（阶段为内部过程，`23` v0.4 裁定
 *       不建独立任务状态）；</li>
 *   <li>「投放机构反馈完成」简化为样本上的投放标记；<b>重试／悬停等待简化</b>为
 *       样本耗尽即放弃（重试次数与悬停上限留桩）；</li>
 *   <li>甲板参考点与姿态首版不入参（B-11）——只用船位定义参考点；</li>
 *   <li><b>不自行决策兜底</b>：失败一律以上报 M2 的确认对象返回（§3.6）。</li>
 * </ul>
 *
 * <p>判据（照 §3.6）：接近退出＝相对距离低于收敛阈值；匹配退出＝相对位置误差与
 * 相对速度<b>持续</b>达标满稳定时长；交接完成＝投放反馈出现<b>且</b>当时风况满足交接
 * 风阈值。相对距离与相对速度在局部米制平移系下计算（同 {@code GeoApprox} 近似）。
 */
public final class HandoverJudge {

    /** 每度纬差对应的米数（与 GeoApprox 同一近似） */
    private static final double METERS_PER_DEG = Math.PI * 6_371_000.0 / 180.0;

    private HandoverJudge() {
    }

    /**
     * 判定一次末段会面与交接。
     *
     * @param cmd 判定入参（计划基准＋采样＋阈值）
     * @return 交接完成确认；失败时完成时刻与开销字段为 null，已完成接近的失败仍给实际会面点
     * @throws IllegalArgumentException 样本为空或时间非严格升序
     */
    public static B_HandoverConfirmation judge(HandoverCommand cmd) {
        List<HandoverSample> samples = cmd.samples();
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("末段样本序列不能为空");
        }
        HandoverParams params = cmd.params();

        HandoverSample origin = samples.get(0);
        double cosLat = Math.cos(Math.toRadians(origin.droneLat()));

        HandoverPhase phase = HandoverPhase.APPROACH;
        Long stableSince = null;
        Long transferStartT = null;
        Double actualMeetLon = null;
        Double actualMeetLat = null;
        Long handoverDoneT = null;
        boolean success = false;

        double prevDx = relativeX(origin, origin, cosLat);
        double prevDy = relativeY(origin, origin);

        for (int i = 1; i < samples.size(); i++) {
            HandoverSample s = samples.get(i);
            long dtMs = s.t() - samples.get(i - 1).t();
            if (dtMs <= 0) {
                throw new IllegalArgumentException("样本时间必须严格升序，第 " + i + " 个样本时间非增");
            }

            double dx = relativeX(s, origin, cosLat);
            double dy = relativeY(s, origin);
            double dist = Math.hypot(dx, dy);
            double relSpeed = Math.hypot(dx - prevDx, dy - prevDy) / (dtMs / 1000.0);
            prevDx = dx;
            prevDy = dy;

            if (phase == HandoverPhase.APPROACH) {
                if (dist < params.approachRadiusM()) {
                    phase = HandoverPhase.MATCH;
                    actualMeetLon = s.droneLon();
                    actualMeetLat = s.droneLat();
                }
                continue;
            }

            if (phase == HandoverPhase.MATCH) {
                boolean stable = dist < params.posErrorM() && relSpeed < params.speedErrorMs();
                if (!stable) {
                    stableSince = null;
                    continue;
                }
                if (stableSince == null) {
                    stableSince = s.t();
                }
                if (s.t() - stableSince >= params.stableSec() * 1000.0) {
                    phase = HandoverPhase.TRANSFER;
                    transferStartT = s.t();
                }
                continue;
            }

            if (phase == HandoverPhase.TRANSFER && s.dropDone()) {
                if (s.windSpeedMs() <= params.windLimitMs()) {
                    success = true;
                    handoverDoneT = s.t();
                    phase = HandoverPhase.DEPART;
                }
                break;
            }
        }

        Integer escortTimeCost = null;
        Integer handoverTimeCost = null;
        Double energyCost = null;
        if (success) {
            escortTimeCost = (int) ((transferStartT - origin.t()) / 1000);
            handoverTimeCost = (int) ((handoverDoneT - transferStartT) / 1000);
            energyCost = (escortTimeCost + handoverTimeCost) * params.energyRatePerSecPct();
        }

        return new B_HandoverConfirmation(cmd.taskId(), cmd.droneId(),
                actualMeetLon, actualMeetLat,
                cmd.plannedMeetLon(), cmd.plannedMeetLat(), cmd.plannedMeetT(),
                handoverDoneT, escortTimeCost, handoverTimeCost, energyCost,
                success ? HandoverResult.SUCCESS : HandoverResult.FAILED);
    }

    private static double relativeX(HandoverSample s, HandoverSample origin, double cosLat) {
        return (s.droneLon() - origin.droneLon()) * METERS_PER_DEG * cosLat
                - (s.shipLon() - origin.droneLon()) * METERS_PER_DEG * cosLat;
    }

    private static double relativeY(HandoverSample s, HandoverSample origin) {
        return (s.droneLat() - origin.droneLat()) * METERS_PER_DEG
                - (s.shipLat() - origin.droneLat()) * METERS_PER_DEG;
    }
}
