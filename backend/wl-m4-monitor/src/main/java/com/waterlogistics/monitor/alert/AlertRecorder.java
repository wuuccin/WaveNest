package com.waterlogistics.monitor.alert;

import com.waterlogistics.algo.detection.DetectionResult;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.storage.entity.AlertEntity;
import com.waterlogistics.storage.repository.AlertMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警记录与投递。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>写 alert 表（37 §5.12 八列；经 wl-storage {@link AlertMapper}，M6 不做业务判断）</li>
 *   <li>发 alert.event（MQ 推 M2，帧交给 wl-app/push 推 WS）</li>
 * </ul>
 *
 * <p>落库口径（37 §5.12 高于 86 旧映射注，2026-10-16）：RAISED 首次触发 insert 一行，
 * {@code alert_id} 复用事件 eventId（UUID ≤ 64 字符），类型/级别取契约中文枚举值，
 * {@code trigger_time} 取检测层首次触发时刻（Asia/Shanghai），{@code handle_status=未处理}；
 * LEVEL_CHANGED 按 alert_id update level（G-4：级别字段可更新）。
 * duration/current_value/threshold_value 三字段只随 alert.event 消息携带（G-2 九字段），
 * alert 表无此三列，不写入、不拼文本序列化。CLEARED/失联恢复只做内存去重复位，
 * 不改 handle_status（23 §10 四态无「已清除」，处置状态由人工/处置链推进）。</p>
 *
 * <p>去重与事件身份口径（86 G-01/G-4，2026-10-16 失联裁决方案 B）：按「无人机 ＋ 告警类型」追踪一条活跃告警——
 * 同一告警<b>一个 eventId 贯穿始终</b>；仅在以下相位产生事件：
 * 首次触发 {@link DetectionResult.Phase#RAISED}、
 * 级别变化 {@link DetectionResult.Phase#LEVEL_CHANGED}（升级即时／降级迟滞达标，复用原 eventId）、
 * 灭失推定 {@link DetectionResult.Phase#LOSS_DECLARED}（失联与灭失同为严重，不升级；凭此相位强制补发一次，
 * 复用原 eventId，M2 据此触发货物灭失/订单异常终止，G-12）。
 * 同级持续帧（{@link DetectionResult.Phase#DURATION}）不重复入流水，duration 只在检测层逐帧刷新；
 * 告警清除由接线层在 {@code phase() == CLEARED} 或失联恢复时调 {@link #resolve} 复位，
 * 复位后同型告警再次越阈以新 eventId 重新起事件。</p>
 *
 * <p>并发：单例 Bean，被多无人机检测线程并发调用；活跃表用 {@link ConcurrentHashMap}，
 * 流水用 {@link CopyOnWriteArrayList}。落库/MQ 失败 fail-soft（记日志、不阻断检测节拍）。</p>
 */
@Component
public class AlertRecorder {

    private static final Logger log = LoggerFactory.getLogger(AlertRecorder.class);

    /** 告警时间落库时区（与 DB serverTimezone=Asia/Shanghai 一致） */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 一条活跃告警的身份：事件 id ＋ 当前级别 ＋ 首次触发时刻。 */
    private record ActiveAlert(String eventId, AlertLevel level, long triggeredAtMs) {}

    /** 已产生的 alert.event 列表（内存留痕，供查询/测试；正式查询走 alert 表） */
    private final List<AlertEventMessage> eventLog = new CopyOnWriteArrayList<>();

    /** 活跃告警去重：key = 无人机|告警类型，value = 活跃告警身份；级别变化/灭失强制发复用 eventId */
    private final Map<String, ActiveAlert> activeAlerts = new ConcurrentHashMap<>();

    /** alert.event MQ 发送器 */
    private final AlertEventPublisher publisher;

    /** alert 表 Mapper（纯单测/无库环境可为 null，此时只发 MQ + 内存留痕） */
    private final AlertMapper alertMapper;

    @Autowired
    public AlertRecorder(AlertEventPublisher publisher, AlertMapper alertMapper) {
        this.publisher = publisher;
        this.alertMapper = alertMapper;
    }

    /** 无库构造（单元测试/内存兜底；生产由 Spring 使用双参构造） */
    public AlertRecorder(AlertEventPublisher publisher) {
        this(publisher, null);
    }

    private static String activeKey(String droneId, AlertType type) {
        return droneId + "|" + type.name();
    }

    /**
     * 记录一次告警检测结果。
     *
     * @param result  检测结果（级别/类型为契约枚举，相位见 {@link DetectionResult.Phase}）
     * @param droneId 无人机标识
     * @param orderId 订单标识（可空，如失联早于订单绑定）
     * @return 新产生的 AlertEventMessage；无告警、同级持续（去重）时返回 null
     */
    public AlertEventMessage record(DetectionResult result, String droneId, String orderId) {
        if (!result.hasAlert()) return null;

        String key = activeKey(droneId, result.alertType());
        ActiveAlert previous = activeAlerts.get(key);
        boolean forceEmit = result.phase() == DetectionResult.Phase.LOSS_DECLARED;
        // 同级持续去重；灭失推定（方案 B：与失联同级，凭相位标识深化）必补发一次
        if (previous != null && previous.level() == result.level() && !forceEmit) {
            return null;
        }
        // 同一告警一个 eventId 贯穿：首次触发新起 id，级别变化/灭失补发复用原 id
        String eventId = previous == null ? UUID.randomUUID().toString() : previous.eventId();
        activeAlerts.put(key, new ActiveAlert(eventId, result.level(), result.triggeredAtMs()));

        AlertEventMessage event = new AlertEventMessage(
                eventId,
                result.alertType().getValue(),
                result.level().getValue(),
                (int) result.durationSeconds(),
                droneId,
                orderId,
                result.triggeredAtMs(),
                result.currentValue(),
                result.thresholdValue()
        );
        eventLog.add(event);
        publisher.publish(event);
        persist(previous, event);
        return event;
    }

    /**
     * 告警清除复位：接线层在检测器返回 CLEARED 或失联恢复时按机/类型调用，
     * 复位后同型告警再次越阈可重新产生事件。alert 表处置状态不在此推进。
     */
    public void resolve(String droneId, AlertType alertType) {
        activeAlerts.remove(activeKey(droneId, alertType));
    }

    /** 获取告警事件流水（当前阶段用，替代 alert 表查询）。 */
    public List<AlertEventMessage> eventLog() { return Collections.unmodifiableList(eventLog); }

    /** RAISED 落库 insert；LEVEL_CHANGED/灭失补发按 eventId update（灭失同级时值不变，跳过）。 */
    private void persist(ActiveAlert previous, AlertEventMessage event) {
        if (alertMapper == null) return;
        try {
            if (previous == null) {
                AlertEntity entity = new AlertEntity();
                entity.setAlertId(event.eventId());
                entity.setDroneId(event.droneId());
                entity.setOrderId(event.orderId());
                entity.setAlertType(event.alertType());
                entity.setLevel(event.level());
                entity.setTriggerTime(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(event.timestamp()), DB_ZONE));
                entity.setHandleStatus("未处理");
                alertMapper.insert(entity);
            } else if (!previous.level().getValue().equals(event.level())) {
                // 列名照 37 §5.12 DDL；用字面量列名而非 Lambda 包装器——
                // 后者依赖 SqlSessionFactory 初始化的实体 lambda 缓存，无库单测会失效
                alertMapper.update(null, new UpdateWrapper<AlertEntity>()
                        .eq("alert_id", event.eventId())
                        .set("level", event.level()));
            }
        } catch (Exception e) {
            // fail-soft：落库失败不阻断 MQ 与检测节拍，留排障线索（MQ 已先行发出）
            log.warn("alert persist failed: eventId={}, droneId={}, type={}, err={}",
                    event.eventId(), event.droneId(), event.alertType(), e.getMessage());
        }
    }
}
