package com.waterlogistics.dispatch.plan;

import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 规划航迹落库（《1112-功能做实批次｜接口约定》v1.2 §2.2）。
 *
 * <p>调用点在 {@link DispatchOrchestrator} 的同一派单事务内、组帧之后推帧之前：
 * 落库失败抛异常并回滚派单事务，禁止吞异常、禁止先推帧后落库。
 * 一条派发一次 {@code insertBatch}；{@code task_log} 的摘要仍由编排器写一条。
 *
 * <p>本类只做帧 → 实体映射与行数核对，不改航迹几何、不做业务判定。
 */
@Component
public class PlanTrackWriter {

    private final PlanTrackMapper planTrackMapper;

    public PlanTrackWriter(PlanTrackMapper planTrackMapper) {
        this.planTrackMapper = planTrackMapper;
    }

    /**
     * 把一帧计划航迹全部计划点写入 {@code plan_track}。
     *
     * @param frame 规划航迹帧（已在派单事务内定案，可能已完成错峰时移）
     * @return 实际写入行数
     * @throws IllegalStateException 帧无计划点，或写入行数与点数不一致
     */
    public int write(PlanTrackFrame frame) {
        List<PlanTrackEntity> points = new ArrayList<>();
        for (PlanTrackFrame.Segment segment : frame.segments()) {
            int seq = 1;
            for (PlanTrackFrame.Point point : segment.points()) {
                PlanTrackEntity entity = new PlanTrackEntity();
                entity.setOrderId(frame.orderId());
                entity.setRouteId(frame.routeId());
                entity.setComboId(frame.comboId());
                entity.setSegmentKind(segment.kind());
                entity.setSeq(seq++);
                entity.setLon(BigDecimal.valueOf(point.lon()));
                entity.setLat(BigDecimal.valueOf(point.lat()));
                entity.setAltM(BigDecimal.valueOf(point.altM()));
                entity.setSpeedMs(BigDecimal.valueOf(point.speedMs()));
                entity.setTMs(point.tMs());
                points.add(entity);
            }
        }
        if (points.isEmpty()) {
            throw new IllegalStateException(
                    "订单 " + frame.orderId() + " 规划航迹帧无计划点，不得落库后推帧");
        }
        int rows = planTrackMapper.insertBatch(points);
        if (rows != points.size()) {
            throw new IllegalStateException("订单 " + frame.orderId()
                    + " 计划点落库行数 " + rows + " 与点数 " + points.size() + " 不一致，回滚派单事务");
        }
        return rows;
    }
}
