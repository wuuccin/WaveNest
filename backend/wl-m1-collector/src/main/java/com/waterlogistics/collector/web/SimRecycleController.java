package com.waterlogistics.collector.web;

import com.waterlogistics.collector.publisher.DataPublisher;
import com.waterlogistics.contract.mq.GoodsRecycleConfirmation;
import com.waterlogistics.contract.mq.TopicName;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 落巢回收确认仿真端口（11-07 弥补计划 D-16）。
 *
 * <p>真实场景由飞控或机巢上报；仿真期没有该装置，用本端点补一个来源，
 * 发出的仍是标准 {@code data.uav.recycle} 消息，消费侧无需区分来源。
 *
 * <p><b>只发布 MQ，不写任何库表</b>：货物写点与 {@code task_log} 由 M2 消费端落库，
 * 端点自身不持有 Mapper，避免出现「仿真把业务状态提前改掉」的双写点。
 *
 * <p>用法：
 * <pre>
 *   POST /sim/recycle?orderId=ORD-1&amp;goodsId=G-ORD-1-1&amp;uavId=UAV-01&amp;nestId=NEST-01
 * </pre>
 */
@RestController
@RequestMapping("/sim/recycle")
public class SimRecycleController {

    private final DataPublisher publisher;

    public SimRecycleController(DataPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 发布一条落巢回收确认。
     *
     * @param orderId 订单编号
     * @param goodsId 货物标识
     * @param uavId   原执行机编号
     * @param nestId  落巢标识
     * @return 回显发布内容
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> publish(
            @RequestParam String orderId,
            @RequestParam String goodsId,
            @RequestParam String uavId,
            @RequestParam String nestId) {
        long ts = System.currentTimeMillis();
        GoodsRecycleConfirmation msg =
                new GoodsRecycleConfirmation(orderId, goodsId, uavId, nestId, ts);
        publisher.publishGoodsRecycleConfirmation(msg);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "publishGoodsRecycleConfirmation");
        body.put("topic", TopicName.DATA_UAV_RECYCLE);
        body.put("orderId", orderId);
        body.put("goodsId", goodsId);
        body.put("uavId", uavId);
        body.put("nestId", nestId);
        body.put("ts", ts);
        return ResponseEntity.ok(body);
    }
}
