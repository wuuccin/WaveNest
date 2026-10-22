package com.waterlogistics.rendezvous.handover;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;

import java.util.List;
import java.util.Objects;



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
 * 相对速度<b>持续</b>达标满稳定时长；交接完成＝投放反馈出现、该拍仍满足位置/速度阈值、
 * 且当时风况满足交接风阈值（首版对投放拍复核稳定性的收紧口径）。
 * 相对距离与相对速度在局部米制平移系下计算（同 {@code GeoApprox} 近似）。
 *
 * <p>阈值严格性口径（与 §3.6 用词对齐，系有意为之）：距离／速度用「低于／小于」⇒
 * <b>严格 &lt;</b>；风况用「满足阈值」⇒ <b>≤</b>（含等号）。
 */
public final class HandoverJudge {

    /**
     * 每度纬差对应的米数（与 GeoApprox 同一近似）
     */
    private static final double METERS_PER_DEG = GeoApprox.METERS_PER_DEG;





    private HandoverJudge() {
    }





    /**
     * 判定一次末段会面与交接。
     *
     * @param cmd 判定入参（计划基准＋采样＋阈值）
     * @return 交接完成确认；失败时完成时刻与开销字段为 null，已完成接近的失败仍给实际会面点
     * @throws NullPointerException cmd 为 null（样本非空与时间严格升序已由
     *                              {@link HandoverCommand} 构造期拒绝）
     */
    @SuppressWarnings("ObjectAllocationInLoop")
    public static B_HandoverConfirmation judge(HandoverCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");
        List<HandoverSample> samples = cmd.samples();
        HandoverParams params = cmd.params();

        HandoverSample origin = samples.get(0);
        double cosLat = Math.cos(Math.toRadians(origin.droneLat()));

        HandoverPhase phase = HandoverPhase.APPROACH;
        Long stableSince = null;
        long transferStartT = 0L;
        Double actualMeetLon = null;
        Double actualMeetLat = null;
        long handoverDoneT = 0L;
        boolean success = false;

        double prevDx = HandoverJudge.relativeX(origin, cosLat);
        double prevDy = HandoverJudge.relativeY(origin);
        if (Math.hypot(prevDx, prevDy) < params.approachRadiusM()) {
            phase = HandoverPhase.MATCH;
            actualMeetLon = Double.valueOf(origin.droneLon());
            actualMeetLat = Double.valueOf(origin.droneLat());
        }

        int sampleCount = samples.size();
        for (int i = 1; i < sampleCount; i++) {
            HandoverSample sample = samples.get(i);
            long dtMs = sample.t() - samples.get(i - 1).t();

            double dx = HandoverJudge.relativeX(sample, cosLat);
            double dy = HandoverJudge.relativeY(sample);
            double dist = Math.hypot(dx, dy);
            double relSpeed = Math.hypot(dx - prevDx, dy - prevDy) / ((double) dtMs / 1000.0);
            prevDx = dx;
            prevDy = dy;

            // 稳定判据（距离与速度均低于各自阈值）——MATCH 段与同拍 TRANSFER 共用同一判定
            boolean stable = dist < params.posErrorM() && relSpeed < params.speedErrorMs();

            if (phase == HandoverPhase.APPROACH) {
                if (dist < params.approachRadiusM()) {
                    phase = HandoverPhase.MATCH;
                    actualMeetLon = Double.valueOf(sample.droneLon());
                    actualMeetLat = Double.valueOf(sample.droneLat());
                }
                // 进入 MATCH 的过渡拍只记实际会面点、不接受投放，但稳定窗口须从该拍起算
                if (stable) {
                    stableSince = Long.valueOf(sample.t());
                }
                continue;
            }

            if (phase == HandoverPhase.MATCH) {
                if (!stable) {
                    stableSince = null;
                    continue;
                }
                if (stableSince == null) {
                    stableSince = Long.valueOf(sample.t());
                }
                if ((double) (sample.t() - stableSince.longValue()) >= params.stableSec() * 1000.0) {
                    phase = HandoverPhase.TRANSFER;
                    transferStartT = sample.t();
                }
            }

            // 有意落穿：MATCH 段在上方补满稳定窗口后同拍进入 TRANSFER，
            // 允许在该过渡拍即刻接受投放反馈（见 HandoverJudgeTest 同拍投放用例）。
            if (phase == HandoverPhase.TRANSFER && sample.dropDone()) {
                if (stable && sample.windSpeedMs() <= params.windLimitMs()) {
                    success = true;
                    handoverDoneT = sample.t();
                    break;
                }
            }
        }

        Integer escortTimeCost = null;
        Integer handoverTimeCost = null;
        Double energyCost = null;
        if (success) {
            escortTimeCost = Integer.valueOf((int) Math.min((long) Integer.MAX_VALUE, (transferStartT - origin.t()) / 1000L));
            handoverTimeCost = Integer.valueOf((int) Math.min((long) Integer.MAX_VALUE, (handoverDoneT - transferStartT) / 1000L));
            double totalSec = (double) (handoverDoneT - origin.t()) / 1000.0;
            energyCost = Double.valueOf(Math.min(100.0, totalSec * params.energyRatePerSecPct()));
        }

        return new B_HandoverConfirmation(
                cmd.taskId(),
                cmd.droneId(),
                actualMeetLon,
                actualMeetLat,
                cmd.plannedMeetLon(),
                cmd.plannedMeetLat(),
                cmd.plannedMeetT(),
                success ? Long.valueOf(handoverDoneT) : null,
                escortTimeCost,
                handoverTimeCost,
                energyCost,
                success ? HandoverResult.SUCCESS : HandoverResult.FAILED
        );
    }





    private static double relativeX(HandoverSample sample, double cosLat) {
        return (sample.droneLon() - sample.shipLon()) * HandoverJudge.METERS_PER_DEG * cosLat;
    }





    private static double relativeY(HandoverSample sample) {
        return (sample.droneLat() - sample.shipLat()) * HandoverJudge.METERS_PER_DEG;
    }
}
