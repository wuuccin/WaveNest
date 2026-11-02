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
    public static B_HandoverConfirmation judge(HandoverCommand cmd) {
        Objects.requireNonNull(cmd, "cmd 不能为空");
        JudgeState state = new JudgeState(cmd);
        List<HandoverSample> samples = cmd.samples();
        int sampleCount = samples.size();
        long previousT = samples.get(0).t();
        for (int i = 1; i < sampleCount; i++) {
            HandoverSample sample = samples.get(i);
            double dtSec = (double) (sample.t() - previousT) / 1000.0;
            previousT = sample.t();
            if (state.hasCompleted(sample, dtSec)) {
                break;
            }
        }
        return state.toConfirmation(cmd);
    }





    /** 判定过程中需要跨样本保持的状态。抽出它是为了把单一样本的推进逻辑与状态存放分开。 */
    private static final class JudgeState {

        /** 每度纬差对应的米数（与 GeoApprox 同一近似） */
        private static final double METERS_PER_DEG = GeoApprox.METERS_PER_DEG;

        private final HandoverParams params;
        private final long originT;
        private HandoverPhase phase = HandoverPhase.APPROACH;
        private Long stableSince = null;
        private long transferStartT = 0L;
        private Double actualMeetLon = null;
        private Double actualMeetLat = null;
        private long handoverDoneT = 0L;
        private boolean success = false;
        private double prevDx;
        private double prevDy;





        private JudgeState(HandoverCommand cmd) {
            this.params = cmd.params();
            HandoverSample origin = cmd.samples().get(0);
            this.originT = origin.t();
            this.prevDx = JudgeState.relativeX(origin, JudgeState.cosLatOf(origin));
            this.prevDy = JudgeState.relativeY(origin);
            if (Math.hypot(this.prevDx, this.prevDy) < this.params.approachRadiusM()) {
                this.phase = HandoverPhase.MATCH;
                this.actualMeetLon = Double.valueOf(origin.droneLon());
                this.actualMeetLat = Double.valueOf(origin.droneLat());
            }
        }





        /**
         * 推进一个样本。
         *
         * @return {@code true} 表示本次交接已成功完成，调用方可以停止遍历
         */
        private boolean hasCompleted(HandoverSample sample, double dtSec) {
            // 每拍按「机位与船位的中纬」重算经度缩放，不能沿用首拍的余弦
            double sampleCosLat = JudgeState.cosLatOf(sample);
            double dx = JudgeState.relativeX(sample, sampleCosLat);
            double dy = JudgeState.relativeY(sample);
            double dist = Math.hypot(dx, dy);
            double relSpeed = Math.hypot(dx - this.prevDx, dy - this.prevDy) / dtSec;
            this.prevDx = dx;
            this.prevDy = dy;

            // 稳定判据（距离与速度均低于各自阈值）——MATCH 段与同拍 TRANSFER 共用同一判定
            boolean stable = dist < this.params.posErrorM() && relSpeed < this.params.speedErrorMs();

            if (this.phase == HandoverPhase.APPROACH) {
                if (dist < this.params.approachRadiusM()) {
                    this.phase = HandoverPhase.MATCH;
                    this.actualMeetLon = Double.valueOf(sample.droneLon());
                    this.actualMeetLat = Double.valueOf(sample.droneLat());
                    if (stable) {
                        this.stableSince = Long.valueOf(sample.t());
                    }
                }
                return false;
            }

            if (this.phase == HandoverPhase.MATCH) {
                if (!stable) {
                    this.stableSince = null;
                    return false;
                }
                if (this.stableSince == null) {
                    this.stableSince = Long.valueOf(sample.t());
                }
                if ((double) (sample.t() - this.stableSince.longValue()) >= this.params.stableSec() * 1000.0) {
                    this.phase = HandoverPhase.TRANSFER;
                    this.transferStartT = sample.t();
                }
            }

            // 有意落穿：MATCH 段在上方补满稳定窗口后同拍进入 TRANSFER，
            // 允许在该过渡拍即刻接受投放反馈（见 HandoverJudgeTest 同拍投放用例）。
            // 投放标记只在真实投放的那一拍为 true；本拍不满足风况或稳定性时，
            // 后续样本须以新的投放拍重试，生产方不得把一次投放闩锁到后续每一拍。
            if (this.phase == HandoverPhase.TRANSFER && sample.dropDone()
                    && stable && sample.windSpeedMs() <= this.params.windLimitMs()) {
                this.success = true;
                this.handoverDoneT = sample.t();
                return true;
            }
            return false;
        }





        private B_HandoverConfirmation toConfirmation(HandoverCommand cmd) {
            Integer escortTimeCost = null;
            Integer handoverTimeCost = null;
            Double energyCost = null;
            if (this.success) {
                escortTimeCost = Integer.valueOf((int) Math.min((long) Integer.MAX_VALUE,
                                                                (this.transferStartT - this.originT) / 1000L));
                handoverTimeCost = Integer.valueOf((int) Math.min((long) Integer.MAX_VALUE,
                                                                  (this.handoverDoneT - this.transferStartT) / 1000L));
                double totalSec = (double) (this.handoverDoneT - this.originT) / 1000.0;
                energyCost = Double.valueOf(Math.min(100.0, totalSec * this.params.energyRatePerSecPct()));
            }

            return new B_HandoverConfirmation(
                    cmd.taskId(),
                    cmd.droneId(),
                    this.actualMeetLon,
                    this.actualMeetLat,
                    cmd.plannedMeetLon(),
                    cmd.plannedMeetLat(),
                    cmd.plannedMeetT(),
                    this.success ? Long.valueOf(this.handoverDoneT) : null,
                    escortTimeCost,
                    handoverTimeCost,
                    energyCost,
                    this.success ? HandoverResult.SUCCESS : HandoverResult.FAILED
            );
        }





        /** 经度缩放的余弦值：取机位与船位的中纬，与 {@code GeoApprox} 的距离口径一致。 */
        private static double cosLatOf(HandoverSample sample) {
            return Math.cos(Math.toRadians((sample.droneLat() + sample.shipLat()) / 2.0));
        }





        private static double relativeX(HandoverSample sample, double cosLat) {
            double dLon = sample.droneLon() - sample.shipLon();
            if (dLon > 180.0) {
                dLon -= 360.0;
            } else if (dLon <= -180.0) {
                dLon += 360.0;
            }
            return dLon * JudgeState.METERS_PER_DEG * cosLat;
        }





        private static double relativeY(HandoverSample sample) {
            return (sample.droneLat() - sample.shipLat()) * JudgeState.METERS_PER_DEG;
        }
    }
}
