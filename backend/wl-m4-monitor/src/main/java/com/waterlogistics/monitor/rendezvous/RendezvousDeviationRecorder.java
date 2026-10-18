package com.waterlogistics.monitor.rendezvous;

import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会面偏差回流记录器（G 组功能 19，16 号简化版）。
 *
 * <p>职责：接收 M2 的 {@code B_HandoverConfirmation}，组装 13 字段偏差样本，
 * 发 MQ（rendezvous.deviation）＋ 内存留痕。不落库，落库留空实现。
 *
 * <p>16 号简化：滑动窗口聚合、分层统计、校准建议等完整版逻辑不做，
 * 只保留「采样 → 偏差计算 → 发 MQ」最小链路。
 *
 * <p>数据源：经 M2 的 {@code B_HandoverConfirmation}（22 §5 第103行），不新开主题。
 */
@Component
public class RendezvousDeviationRecorder {

    private static final Logger log = LoggerFactory.getLogger(RendezvousDeviationRecorder.class);

    /** 已产生的偏差样本流水（当前阶段放内存，完整版可接滑动窗口聚合） */
    private final List<RendezvousDeviation> deviationLog = new ArrayList<>();

    /** MQ 发送器（16 号接线注入） */
    private final RendezvousDeviationPublisher publisher;

    public RendezvousDeviationRecorder(RendezvousDeviationPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 接收交接完成确认，组装偏差样本并发送。
     *
     * @param confirmation 交接完成确认（含计划/实际会面点与时刻）
     * @param orderId      订单实体标识（任务方案 orderId；confirmation.taskId() 不可顶替）
     * @param model        机型
     * @param homeNestId   归属机巢标识
     * @param windCondition 风况段（可选）
     * @param shipSpeedRange 船速段（可选）
     * @return 组装好的偏差样本；无实际会面点（未完成接近）时返回 null
     */
    public RendezvousDeviation record(
            B_HandoverConfirmation confirmation,
            String orderId,
            String model,
            String homeNestId,
            String windCondition,
            String shipSpeedRange) {

        // 无实际会面点：未完成接近，不产生偏差样本
        if (confirmation.actualMeetLon() == null || confirmation.actualMeetLat() == null) {
            log.debug("rendezvous deviation skipped: no actual meet point, taskId={}", confirmation.taskId());
            return null;
        }

        RendezvousDeviation deviation = RendezvousDeviation.from(
                confirmation, orderId, model, homeNestId, windCondition, shipSpeedRange);

        deviationLog.add(deviation);
        publisher.publish(deviation);

        log.info("rendezvous deviation recorded: sampleId={}, droneId={}, orderId={}, dSpaceM={}m, dTimeS={}s",
                deviation.sampleId(), deviation.droneId(), deviation.orderId(),
                deviation.dSpaceM(), deviation.dTimeS());

        return deviation;
    }

    /** 获取偏差样本流水（当前阶段用，完整版可接滑动窗口聚合）。 */
    public List<RendezvousDeviation> deviationLog() {
        return Collections.unmodifiableList(deviationLog);
    }

    /** 清空流水（测试/复位用）。 */
    public void clear() {
        deviationLog.clear();
    }
}
