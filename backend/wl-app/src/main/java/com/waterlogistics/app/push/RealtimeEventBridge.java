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
                // 位置与订单号由告警发送侧（T1-3）写上；取不到就原样传 null，不补默认坐标。
                event.lon(), event.lat(), event.orderId(), event.eventId()));
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
