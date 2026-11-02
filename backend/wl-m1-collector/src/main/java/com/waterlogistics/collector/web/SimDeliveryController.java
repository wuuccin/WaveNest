package com.waterlogistics.collector.web;

import com.waterlogistics.collector.publisher.DataPublisher;
import com.waterlogistics.contract.mq.DeliveryReceipt;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放回执仿真端口：演示时手动让无人机上报一次投放结果。
 *
 * <p>真实场景下这条回执由飞控或投放装置发出；仿真期没有该装置，用本端点补一个来源，
 * 发出的仍是标准 {@code data.uav.delivery} 消息，消费侧无需区分来源。
 *
 * <p>用法：
 * <pre>
 *   POST /sim/delivery?taskId=ORD-1&amp;orderId=ORD-1&amp;uavId=UAV-01
 *   POST /sim/delivery?taskId=ORD-1&amp;orderId=ORD-1&amp;uavId=UAV-01&amp;result=failure
 * </pre>
 */
@RestController
@RequestMapping("/sim/delivery")
public class SimDeliveryController {

    private final DataPublisher publisher;

    public SimDeliveryController(DataPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 发布一条投放回执。
     *
     * @param taskId  任务编号
     * @param orderId 订单编号
     * @param uavId   无人机编号
     * @param result  投放结果，缺省为成功
     * @return 回显发布内容
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> publish(
            @RequestParam String taskId,
            @RequestParam String orderId,
            @RequestParam String uavId,
            @RequestParam(defaultValue = DeliveryReceipt.RESULT_SUCCESS) String result) {
        long ts = System.currentTimeMillis();
        DeliveryReceipt receipt = new DeliveryReceipt(taskId, orderId, uavId, ts, result);
        publisher.publishDeliveryReceipt(receipt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "publishDeliveryReceipt");
        body.put("topic", "data.uav.delivery");
        body.put("taskId", taskId);
        body.put("orderId", orderId);
        body.put("uavId", uavId);
        body.put("result", result);
        body.put("ts", ts);
        return ResponseEntity.ok(body);
    }
}
