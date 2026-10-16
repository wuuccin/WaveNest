package com.waterlogistics.monitor.detect;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.algo.detection.HeartbeatLostDetector;
import com.waterlogistics.algo.detection.LowBatteryDetector;
import com.waterlogistics.algo.detection.OfftrackDetector;
import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.monitor.alert.AlertRecorder;

/**
 * 单机检测上下文：偏航/低电量/失联三个检测器 + 本轮任务态。
 *
 * <p>所有方法 synchronized：帧喂入（ingest 线程）与失联 sweep（调度线程）可能并发，
 * 同一架无人机的检测器状态必须串行更新。多机之间互不阻塞（各持各的锁）。</p>
 */
final class DroneDetectionContext {

    private final OfftrackDetector offtrack;
    private final LowBatteryDetector lowBattery;
    private final HeartbeatLostDetector heartbeat;

    /** 当前任务态（随样本更新；sweep 门控用） */
    private String orderId;
    private boolean missionActive;

    DroneDetectionContext(SysParamProvider params) {
        this.offtrack = new OfftrackDetector(params);
        this.lowBattery = new LowBatteryDetector(params);
        this.heartbeat = new HeartbeatLostDetector(params);
    }

    /** 喂入一帧：心跳（含恢复复位）→ 低电量 → 偏航，逐项检测并投递。 */
    synchronized void onSample(DetectionSample sample, AlertRecorder recorder) {
        this.orderId = sample.orderId();
        this.missionActive = sample.missionActive();

        // 心跳唯一来源是遥测打点 ts；失联后灭失前恢复 → 清除活跃失联事件
        HeartbeatLostDetector.HeartbeatOutcome outcome = heartbeat.heartbeat(sample.tsMs());
        if (outcome == HeartbeatLostDetector.HeartbeatOutcome.RECOVERED) {
            recorder.resolve(sample.droneId(),
                    com.waterlogistics.contract.state.AlertType.LOST_LINK);
        }

        if (sample.batteryPercent() != null) {
            dispatch(lowBattery.feed(sample.batteryPercent(), sample.tsMs()),
                    sample.droneId(), recorder);
        }
        if (sample.projection() != null) {
            dispatch(offtrack.feed(sample.projection(), sample.tsMs()),
                    sample.droneId(), recorder);
        }
    }

    /** 失联定时轮询（仅对在飞机生效，检测器内部做 missionActive 门控）。 */
    synchronized void sweep(long nowMs, AlertRecorder recorder, String droneId) {
        DetectionResult result = heartbeat.check(nowMs, missionActive);
        if (result.hasAlert()) {
            recorder.record(result, droneId, orderId);
        }
    }

    /** 任务结束：三检测器全部复位。 */
    synchronized void reset() {
        offtrack.reset();
        lowBattery.reset();
        heartbeat.reset();
        orderId = null;
        missionActive = false;
    }

    /** 检测结果分发：CLEARED 只复位活跃事件，其余有告警则记录（DURATION 由 recorder 去重）。 */
    private void dispatch(DetectionResult result, String droneId, AlertRecorder recorder) {
        if (result.phase() == DetectionResult.Phase.CLEARED) {
            recorder.resolve(droneId, result.alertType());
            return;
        }
        if (result.hasAlert()) {
            recorder.record(result, droneId, orderId);
        }
    }
}
