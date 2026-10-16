package com.waterlogistics.monitor.detect;

import com.waterlogistics.algo.detection.SysParamProvider;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.monitor.alert.AlertRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检测编排器（{@link DetectionSink} 唯一生产实现）：每架无人机一个 {@link DroneDetectionContext}，
 * 把帧喂入与失联定时轮询接到 {@link AlertRecorder}。
 *
 * <p>接线位置：彭紫情 ingest/track 侧清洗出帧 → {@link #onSample}；
 * 失联 sweep 由 {@code @Scheduled} 按 heartbeatCheckInterval 同量级周期驱动
 * （演示期固定 5s，可用 wavenest.m4.heartbeat-sweep-ms 覆盖；窗口阈值本身仍读 sys_param）。
 * 告警 → AlertRecorder → alert.event MQ ＋ alert 表；WS 帧由 wl-app/push 侧适配，本类不自开 socket。</p>
 */
@Component
public class DetectionOrchestrator implements DetectionSink {

    private static final Logger log = LoggerFactory.getLogger(DetectionOrchestrator.class);

    private final AlertRecorder recorder;
    private final SysParamProvider params;
    private final Map<String, DroneDetectionContext> drones = new ConcurrentHashMap<>();

    public DetectionOrchestrator(AlertRecorder recorder, SysParamProvider params) {
        this.recorder = recorder;
        this.params = params;
    }

    @Override
    public void onSample(DetectionSample sample) {
        if (sample == null || sample.droneId() == null) return;
        try {
            drones.computeIfAbsent(sample.droneId(), k -> new DroneDetectionContext(params))
                    .onSample(sample, recorder);
        } catch (Exception e) {
            // 单帧异常不打断 ingest 节拍
            log.warn("detection onSample failed: droneId={}, err={}",
                    sample.droneId(), e.getMessage());
        }
    }

    @Override
    public void onMissionEnd(String droneId) {
        DroneDetectionContext ctx = drones.remove(droneId);
        if (ctx == null) return;
        ctx.reset();
        // 检测器复位之外，还须清 AlertRecorder 的活跃去重表：否则下一单同型告警首帧
        // 会被误判为「同级持续」而丢弃（DetectionSink 契约：下一单重新起事件）。
        // resolve 对不存在的键为 no-op，三类统一清一遍。
        recorder.resolve(droneId, AlertType.DEVIATION);
        recorder.resolve(droneId, AlertType.LOW_BATTERY);
        recorder.resolve(droneId, AlertType.LOST_LINK);
    }

    /**
     * 失联定时轮询（默认 5s；sys_param 三个窗口仍由检测器读）。
     */
    @Scheduled(fixedDelayString = "${wavenest.m4.heartbeat-sweep-ms:5000}",
               initialDelayString = "${wavenest.m4.heartbeat-sweep-ms:5000}")
    public void scheduledSweep() {
        sweep(System.currentTimeMillis());
    }

    /** 按给定时刻对全部在飞无人机跑失联检测（包级可见，供测试注入时钟）。 */
    void sweep(long nowMs) {
        drones.forEach((droneId, ctx) -> {
            try {
                ctx.sweep(nowMs, recorder, droneId);
            } catch (Exception e) {
                log.warn("heartbeat sweep failed: droneId={}, err={}", droneId, e.getMessage());
            }
        });
    }
}
