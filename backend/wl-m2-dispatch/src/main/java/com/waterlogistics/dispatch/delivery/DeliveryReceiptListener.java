package com.waterlogistics.dispatch.delivery;

import com.waterlogistics.contract.mq.DeliveryReceipt;
import com.waterlogistics.contract.mq.TopicName;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 投放回执消费端（阶段一 18）——M2 消费 {@code data.uav.delivery}。
 *
 * <p>回执只做一件事：把「货已投放」变成订单状态推进。
 * <ul>
 *   <li><b>成功</b>：订单从「末段交接中」推进到「返航中」，不再等时间兜底；</li>
 *   <li><b>失败</b>：记警告，订单保持原状态，由时间兜底判交接超时，避免在飞订单被重排；</li>
 *   <li><b>重复</b>：去重键 {@code taskId + uavId + ts}，同一次投放只处理一次。</li>
 * </ul>
 *
 * <p>任何异常只丢弃本条消息并记警告，不中断队列。
 */
@Component
public class DeliveryReceiptListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReceiptListener.class);

    private final TaskQueueService taskQueueService;

    /** 已处理过的回执键，防止链路重发导致重复推进。 */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public DeliveryReceiptListener(TaskQueueService taskQueueService) {
        this.taskQueueService = taskQueueService;
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m2-delivery-queue:wavenest.m2.uav-delivery}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC),
            key = TopicName.DATA_UAV_DELIVERY))
    public void onReceipt(DeliveryReceipt receipt) {
        try {
            String key = receipt.taskId() + "|" + receipt.uavId() + "|" + receipt.ts();
            if (!seen.add(key)) {
                log.info("重复投放回执，跳过：orderId={} uavId={} ts={}",
                        receipt.orderId(), receipt.uavId(), receipt.ts());
                return;
            }
            if (receipt.success()) {
                taskQueueService.markHandoverDone(receipt.orderId());
                log.info("投放成功回执已推进脱离返航：orderId={} uavId={}",
                        receipt.orderId(), receipt.uavId());
            } else {
                // 在飞订单不允许重排，失败保持原状态，由时间兜底判交接超时
                log.warn("投放失败回执，订单保持原状态待时间兜底：orderId={} uavId={} result={}",
                        receipt.orderId(), receipt.uavId(), receipt.result());
            }
        } catch (Exception e) {
            log.warn("投放回执处理失败（只丢弃本条）：orderId={} err={}",
                    receipt.orderId(), e.toString());
        }
    }
}
