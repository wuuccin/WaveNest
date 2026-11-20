package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.selection.FeasibleCandidatePicker;
import com.waterlogistics.contract.rest.CandidateSnapshotView;
import com.waterlogistics.dispatch.feasibility.CandidateFeasibilityPlanner;
import com.waterlogistics.storage.entity.CandidateSnapshotEntity;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.CandidateSnapshotMapper;
import com.waterlogistics.storage.repository.DroneMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 候选快照留痕（11-19 序号 3-1 · 张睿哲；裁定 7）。
 *
 * <h3>做什么</h3>
 * <p>一次派单决策把全部候选的九项取舍证据写进 {@code candidate_snapshot}：
 * 订单号、机型、机巢、机号、距离、会面时刻、落选原因、择优键值、快照版本。
 * 供 {@code GET /api/orders/{orderId}/candidates} 按订单回查——刷新页面后留痕仍在，
 * 不依赖前端内存或 WebSocket 帧。
 *
 * <h3>为什么不做成 task_log 行</h3>
 * <p>候选是「决策依据」，任务日志是「状态迁移」。一单候选常有十几个，混进 task_log
 * 会把状态流冲散，且日志页与时间轴都要过滤。故单独建表、单独端点，
 * <b>本类不依赖任何 task_log 写入入口</b>——结构上就不可能出现候选行。
 *
 * <h3>失败语义</h3>
 * <p>写快照是旁路证据，不是派单前置条件：落库失败只记 {@code WARN} 并返回 0，
 * 不抛异常、不回滚派单事务。表未建（脚本待点批）时每单打一条 WARN，现场可见。
 *
 * <h3>会面时刻口径</h3>
 * <p>只有中选候选落 {@code meetTime}（定案值，含错峰时移）。落选候选未进入会面求解，
 * 该列留空——不用「空机转场 ETA」冒充会面时刻，避免同一列两种语义。
 */
@Service
public class CandidateSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(CandidateSnapshotService.class);

    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 中选候选的「落选原因」列取值：让单列同时可读「谁中选、谁为什么没中」。 */
    public static final String CHOSEN_REASON = "中选";

    private final CandidateSnapshotMapper snapshotMapper;
    private final SysParamLoader sysParamLoader;
    private final DroneMapper droneMapper;

    public CandidateSnapshotService(CandidateSnapshotMapper snapshotMapper,
            SysParamLoader sysParamLoader,
            DroneMapper droneMapper) {
        this.snapshotMapper = snapshotMapper;
        this.sysParamLoader = sysParamLoader;
        this.droneMapper = droneMapper;
    }

    /**
     * 落一次派单决策的全部候选留痕（同一派单事务内调用）。
     *
     * @param order             订单（取期望时间，用于复算择优键的 deadline）
     * @param selection         选机结果（中选者与逐候选判定）
     * @param committedMeetTime 定案会面时刻（含错峰时移后的值）；只写到中选行
     * @param snapshotVersion   会面求解的快照版本（一致性锚）
     * @param nowMs             本次决策的当前时刻（epoch 毫秒）
     * @return 实际写入行数；失败时 0（fail-soft，已记 WARN）
     */
    public int record(OrderTaskEntity order,
            CandidateFeasibilityPlanner.FeasibleSelection selection,
            LocalDateTime committedMeetTime,
            String snapshotVersion,
            long nowMs) {
        if (order == null || order.getOrderId() == null || selection == null
                || selection.candidates().isEmpty()) {
            return 0;
        }
        String orderId = order.getOrderId();
        Long deadlineMs = order.getExpectedTime() == null ? null
                : order.getExpectedTime().atZone(DB_ZONE).toInstant().toEpochMilli();
        double delayPenaltyWeight = sysParamLoader.getDouble("delayPenaltyWeight", 1.0);
        String chosenComboId = selection.chosen() == null ? null : selection.chosen().comboId();

        List<CandidateSnapshotEntity> rows = new ArrayList<>(selection.candidates().size());
        try {
            Map<String, Double> cruiseByDrone = cruiseByDroneId(selection);
            for (CandidateFeasibilityPlanner.CandidateVerdict verdict : selection.candidates()) {
                boolean chosen = verdict.comboId() != null && verdict.comboId().equals(chosenComboId);
                rows.add(toEntity(orderId, verdict,
                        cruiseByDrone.getOrDefault(verdict.droneId(), Double.NaN),
                        chosen, committedMeetTime, snapshotVersion,
                        nowMs, deadlineMs, delayPenaltyWeight));
            }
            for (CandidateSnapshotEntity row : rows) {
                snapshotMapper.insert(row);
            }
        } catch (Exception e) {
            log.warn("候选快照落库失败（fail-soft，不影响派单）：orderId={} 候选 {} 条 err={}",
                    orderId, rows.size(), e.getMessage());
            return 0;
        }
        log.info("候选快照已落库：orderId={} 候选 {} 条（中选 {}）快照版本={}",
                orderId, rows.size(), chosenComboId, snapshotVersion);
        return rows.size();
    }

    /**
     * 按订单回查候选留痕。
     *
     * @param orderId 订单号（首尾空白容忍）
     * @return 九项视图列表；查无记录为空列表，不返回 null
     */
    public List<CandidateSnapshotView> listByOrder(String orderId) {
        String id = orderId == null ? null : orderId.trim();
        if (id == null || id.isEmpty()) {
            return List.of();
        }
        List<CandidateSnapshotEntity> rows = snapshotMapper.selectByOrderId(id);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<CandidateSnapshotView> views = new ArrayList<>(rows.size());
        for (CandidateSnapshotEntity row : rows) {
            views.add(new CandidateSnapshotView(
                    row.getOrderId(),
                    row.getModel(),
                    row.getNestId(),
                    row.getDroneId(),
                    row.getDistKm() == null ? null : row.getDistKm().doubleValue(),
                    row.getMeetTime() == null ? null
                            : row.getMeetTime().atZone(DB_ZONE).toInstant().toEpochMilli(),
                    row.getRejectReason(),
                    row.getRankKey(),
                    row.getSnapshotVersion()));
        }
        return views;
    }

    /** 本次决策涉及的机型巡航速度（m/s，未登记为 NaN），按机号一次性取，避免逐行查库。 */
    private Map<String, Double> cruiseByDroneId(
            CandidateFeasibilityPlanner.FeasibleSelection selection) {
        Set<String> droneIds = new LinkedHashSet<>();
        for (CandidateFeasibilityPlanner.CandidateVerdict verdict : selection.candidates()) {
            if (verdict.droneId() != null && !verdict.droneId().isBlank()) {
                droneIds.add(verdict.droneId());
            }
        }
        Map<String, Double> cruiseByDrone = new HashMap<>();
        if (droneIds.isEmpty()) {
            return cruiseByDrone;
        }
        List<DroneEntity> drones = droneMapper.selectBatchIds(droneIds);
        if (drones == null) {
            return cruiseByDrone;
        }
        for (DroneEntity drone : drones) {
            // 与 CandidateInputAssembler 同口径：DB 未登记给 NaN（不拿 0 冒充）
            cruiseByDrone.put(drone.getDroneId(),
                    drone.getCruiseSpeedMs() == null ? Double.NaN
                            : drone.getCruiseSpeedMs().doubleValue());
        }
        return cruiseByDrone;
    }

    private static CandidateSnapshotEntity toEntity(String orderId,
            CandidateFeasibilityPlanner.CandidateVerdict verdict,
            double cruiseSpeedMs,
            boolean chosen,
            LocalDateTime committedMeetTime,
            String snapshotVersion,
            long nowMs,
            Long deadlineMs,
            double delayPenaltyWeight) {
        CandidateSnapshotEntity row = new CandidateSnapshotEntity();
        row.setOrderId(orderId);
        row.setModel(verdict.model());
        row.setNestId(verdict.nestId());
        row.setDroneId(verdict.droneId());
        row.setDistKm(Double.isFinite(verdict.distKm())
                ? BigDecimal.valueOf(verdict.distKm()) : null);
        row.setMeetTime(chosen ? committedMeetTime : null);
        row.setRejectReason(chosen ? CHOSEN_REASON : String.join("；", verdict.reasons()));
        row.setRankKey(rankKey(verdict.distKm(), cruiseSpeedMs, deadlineMs,
                delayPenaltyWeight, nowMs));
        row.setSnapshotVersion(snapshotVersion);
        return row;
    }

    /**
     * 择优键值文本 {@code "delay=…;dist=…"}。
     *
     * <p>与 {@link FeasibleCandidatePicker} 的两级键同式：先比
     * {@code delayPenaltyWeight × lateness}，再比 {@code distKm}。这里复算一份
     * <b>仅用于留痕展示</b>，不参与择优判定；picker 的 {@code lateness} 是私有方法，
     * 为不改他人 owner 文件（wl-algo）在本地按同式实现，若日后改口径两处必须同改。
     *
     * <p>非有限距离写 {@code "-"}，不把 NaN/Infinity 落库。
     */
    static String rankKey(double distKm, double cruiseSpeedMs, Long deadlineMs,
            double delayPenaltyWeight, long nowMs) {
        if (!Double.isFinite(distKm)) {
            return "delay=-;dist=-";
        }
        double delay = 0.0;
        if (deadlineMs != null && delayPenaltyWeight > 0.0) {
            delay = delayPenaltyWeight * latenessMs(distKm, cruiseSpeedMs, deadlineMs, nowMs);
        }
        String delayText = Double.isFinite(delay)
                ? String.format(Locale.ROOT, "%.1f", delay) : "-";
        return String.format(Locale.ROOT, "delay=%s;dist=%.2f", delayText, distKm);
    }

    /**
     * 预计送达晚于死线的毫秒数（≥0），与 picker 的 {@code lateness} 同式：
     * {@code etaMs = nowMs + distKm × 1e6 ÷ cruiseSpeedMs}；巡航未登记（NaN/≤0）视为 +∞。
     */
    private static double latenessMs(double distKm, double cruiseSpeedMs, long deadlineMs,
            long nowMs) {
        if (!Double.isFinite(cruiseSpeedMs) || cruiseSpeedMs <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double etaMs = nowMs + distKm * 1_000_000.0 / cruiseSpeedMs;
        return Math.max(0.0, etaMs - deadlineMs);
    }
}
