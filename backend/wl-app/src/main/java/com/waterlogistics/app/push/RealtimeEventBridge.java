package com.waterlogistics.app.push;

import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.contract.mq.UavTelemetry;
import com.waterlogistics.contract.state.AlertLevel;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.ws.AlarmFrame;
import com.waterlogistics.contract.ws.DroneStateFrame;
import com.waterlogistics.contract.ws.WsFrameType;
import com.waterlogistics.dispatch.order.OrderStateChangedEvent;
import com.waterlogistics.dispatch.prediction.PredictionUpdatedEvent;
import com.waterlogistics.dispatch.rendezvous.RendezvousPlanCommittedEvent;
import com.waterlogistics.dispatch.taskstate.FlightPhaseAdvancer;
import com.waterlogistics.dispatch.taskstate.FlightPhaseChangedEvent;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 已确认业务结果到 WebSocket 契约帧的接线层。
 *
 * <p>本类不做调度、检测或预测判断：订单状态与预测结果由 M2 产出，
 * 机位与告警分别按契约 MQ 主题消费，再交给唯一出口 {@link WsPushEndpoint} 广播。
 */
@Component
public final class RealtimeEventBridge {

    private final WsPushEndpoint endpoint;

    /** 可为 null：单元测试按旧签名构造时不查在飞订单，{@code activeOrderId} 取 null。 */
    private final FlightPhaseAdvancer flightPhaseAdvancer;

    public RealtimeEventBridge(WsPushEndpoint endpoint) {
        this(endpoint, null);
    }

    @Autowired
    public RealtimeEventBridge(WsPushEndpoint endpoint,
            FlightPhaseAdvancer flightPhaseAdvancer) {
        this.endpoint = endpoint;
        this.flightPhaseAdvancer = flightPhaseAdvancer;
    }

    /** 等状态事务提交后再发送已确认的订单视图；本层只转发。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderStateChanged(OrderStateChangedEvent event) {
        endpoint.publish(WsFrameType.ORDER_STATE, event.view());
    }

    /** M2 飞行阶段推进已写 task_log，本层只把飞行态视图转成订单状态帧。 */
    @EventListener
    public void onFlightPhaseChanged(FlightPhaseChangedEvent event) {
        endpoint.publish(WsFrameType.ORDER_STATE, event.view());
    }

    /** M2 已完成预测与契约投影，本层只转发。 */
    @EventListener
    public void onPredictionUpdated(PredictionUpdatedEvent event) {
        endpoint.publish(WsFrameType.SHIP_TRAJ, event.frame());
    }

    /** M2 在会面方案提交时发布其帧投影；本层不查询订单或推断是否锁定。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRendezvousPlanCommitted(RendezvousPlanCommittedEvent event) {
        endpoint.publish(WsFrameType.RENDEZVOUS, event.frame());
    }

    /** M5 独立队列消费机位；不与 M4 的检测队列竞争消息。 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m5-uav-telemetry-queue:wavenest.m5.data-uav-telemetry}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.DATA_UAV_TELEMETRY))
    public void onUavTelemetry(UavTelemetry telemetry) {
        endpoint.publish(WsFrameType.DRONE_STATE, new DroneStateFrame(
                telemetry.uavId(), telemetry.lon(), telemetry.lat(), telemetry.battery(),
                null, telemetry.ts(), telemetry.heading(), activeOrderIdOf(telemetry.uavId())));
    }

    /** C5 告警产出走 alert.event；本层只做字段投影与提示语拼装。 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m5-alert-event-queue:wavenest.m5.alert-event}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC,
                    durable = "true"),
            key = TopicName.ALERT_EVENT))
    public void onAlertEvent(AlertEventMessage event) {
        endpoint.publish(WsFrameType.ALARM, new AlarmFrame(
                AlertType.fromValue(event.alertType()),
                AlertLevel.fromValue(event.level()),
                event.droneId(),
                alertMessage(event),
                // 位置由告警发送侧（T1-3）写上；取不到就原样传 null，不补默认坐标。
                event.lon(), event.lat(),
                // 订单号：发送侧带了就用带上的；没带则按机号反查在飞订单（口径见 alertOrderIdOf）。
                alertOrderIdOf(event),
                event.eventId()));
    }

    /**
     * 告警帧的订单号：消息自带优先，为空则按机号反查当前在飞订单。
     *
     * <p>与 M2 侧 {@code AlertDrivenDispatcher} 的解析口径一致（自带优先、为空才反查）：
     * 发送侧带了就用发送侧的——那是检测层当轮的任务态，最准；没带才反查。
     * 这样「帧里的单号」与「重调度／返航复核实际用的单号」是同一个，不会各说各话。
     *
     * <p>反查只读飞行阶段推进器的内存阶段缓存（跳过已飞完的订单，零 DB 查询）；
     * 反查未命中时仍传 {@code null}——契约允许为空，不臆造单号。
     */
    private String alertOrderIdOf(AlertEventMessage event) {
        String carried = event.orderId();
        if (carried != null && !carried.isBlank()) {
            return carried;
        }
        return activeOrderIdOf(event.droneId());
    }

    /** 按机号查在飞订单号；缺推进器（单测场景）或该机无在飞订单时为 null。 */
    private String activeOrderIdOf(String droneId) {
        return flightPhaseAdvancer == null ? null : flightPhaseAdvancer.activeOrderIdOf(droneId);
    }

    private static String alertMessage(AlertEventMessage event) {
        StringBuilder text = new StringBuilder("无人机 ")
                .append(event.droneId()).append("：").append(event.alertType());
        if (event.currentValue() != null) {
            text.append("，当前值 ").append(event.currentValue());
        }
        if (event.thresholdValue() != null) {
            text.append("，阈值 ").append(event.thresholdValue());
        }
        return text.toString();
    }
}
