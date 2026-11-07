package com.waterlogistics.dispatch.delivery;

import com.waterlogistics.contract.mq.GoodsRecycleConfirmation;
import com.waterlogistics.contract.mq.TopicName;
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
 * 落巢回收确认消费端（11-07 弥补计划 D-03／D-09／D-16）。
 *
 * <p>真实场景由飞控或机巢上报；演示期由 {@code /sim/recycle} 仿真端点发布。
 * 本类只做去重与转发，货物写点与 {@code task_log} 由 {@link GoodsRecycleHandler} 落库。
 *
 * <p>去重键 {@code orderId|goodsId|uavId|nestId}；处理类另有货物前态兜底，
 * 重复确认最多写一次。任何异常只丢弃本条并记警告，不中断队列。
 */
@Component
public class GoodsRecycleListener {

    private static final Logger log = LoggerFactory.getLogger(GoodsRecycleListener.class);

    private final GoodsRecycleHandler handler;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public GoodsRecycleListener(GoodsRecycleHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "${wavenest.mq.m2-recycle-queue:wavenest.m2.uav-recycle}",
                    durable = "true"),
            exchange = @Exchange(
                    name = "${wavenest.mq.exchange:wavenest.data}",
                    type = ExchangeTypes.TOPIC),
            key = TopicName.DATA_UAV_RECYCLE))
    public void onConfirmation(GoodsRecycleConfirmation msg) {
        if (msg == null) {
            log.warn("忽略空的回收确认消息");
            return;
        }
        if (!seen.add(msg.idempotencyKey())) {
            log.info("重复回收确认，跳过：key={}", msg.idempotencyKey());
            return;
        }
        try {
            String result = handler.confirm(msg);
            if (!GoodsRecycleHandler.OK.equals(result)) {
                log.warn("回收确认未生效：key={} result={}", msg.idempotencyKey(), result);
            }
        } catch (Exception e) {
            log.warn("回收确认处理失败（只丢弃本条）：orderId={} err={}",
                    msg.orderId(), e.toString());
        }
    }
}
