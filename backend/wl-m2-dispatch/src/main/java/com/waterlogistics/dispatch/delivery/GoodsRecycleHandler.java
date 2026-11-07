package com.waterlogistics.dispatch.delivery;

import com.waterlogistics.contract.mq.GoodsRecycleConfirmation;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.dispatch.goods.GoodsLifecycleService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 落巢回收确认处理（11-07 弥补计划 D-03／D-09／D-16）。
 *
 * <p>「已装机 → 已回收」写点的唯一生产落点。此前该写点只挂在
 * {@code RescheduleService.requeueOrder} 上，而重入队前置又拒绝飞行三态，
 * 生产链路无可达入口；本类把回收改成独立事件，不经重入队。
 *
 * <p>错误码与重投语义见 {@code 1106-remediation-contract.md} §2。
 */
@Service
public class GoodsRecycleHandler {

    private static final Logger log = LoggerFactory.getLogger(GoodsRecycleHandler.class);
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /** 处理成功，货物已写「已回收」。 */
    public static final String OK = "OK";
    /** 订单不存在。 */
    public static final String ERR_ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    /** 货物行不存在或不在「已装机」，且也不是「已回收」。 */
    public static final String ERR_GOODS_NOT_LOADED = "GOODS_NOT_LOADED";
    /** 重复确认：货物已是「已回收」，不二次写入。 */
    public static final String ERR_DUPLICATE = "DUPLICATE_CONFIRMATION";
    /** 落点为野外迫降点，不得确认回收。 */
    public static final String ERR_LANDING_POINT_MISMATCH = "LANDING_POINT_MISMATCH";

    private static final String OP_TYPE_RECYCLE = "回收确认-货物已回收";

    private final GoodsLifecycleService goodsLifecycleService;
    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;

    public GoodsRecycleHandler(GoodsLifecycleService goodsLifecycleService,
            OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper) {
        this.goodsLifecycleService = goodsLifecycleService;
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
    }

    /**
     * 确认一次落巢回收。幂等键由监听器去重，此处再用货物前态兜底。
     *
     * @return 处理结果码（见本类常量）
     */
    @Transactional
    public String confirm(GoodsRecycleConfirmation msg) {
        if (isFieldLanding(msg.nestId())) {
            log.warn("回收确认拒绝：落点为野外迫降点，orderId={} nestId={}",
                    msg.orderId(), msg.nestId());
            return ERR_LANDING_POINT_MISMATCH;
        }
        OrderTaskEntity order = orderTaskMapper.selectById(msg.orderId());
        if (order == null) {
            log.warn("回收确认拒绝：订单不存在，orderId={}", msg.orderId());
            return ERR_ORDER_NOT_FOUND;
        }
        if (!goodsLifecycleService.isLoaded(msg.orderId(), msg.goodsId(), msg.uavId())) {
            if (goodsLifecycleService.isRecycled(msg.orderId(), msg.goodsId())) {
                log.info("重复回收确认，已回收不二次写入：orderId={} goodsId={}",
                        msg.orderId(), msg.goodsId());
                return ERR_DUPLICATE;
            }
            log.warn("回收确认拒绝：货物不在「已装机」，orderId={} goodsId={} uavId={}",
                    msg.orderId(), msg.goodsId(), msg.uavId());
            return ERR_GOODS_NOT_LOADED;
        }
        goodsLifecycleService.recycle(msg.orderId(), msg.goodsId(), msg.uavId(), msg.nestId());

        TaskLogEntity entry = new TaskLogEntity();
        entry.setOrderId(msg.orderId());
        entry.setOpType(OP_TYPE_RECYCLE);
        entry.setFromStatus(GoodsStatus.LOADED.getValue());
        entry.setToStatus(GoodsStatus.RECYCLED.getValue());
        entry.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(msg.ts()), DB_ZONE));
        taskLogMapper.insert(entry);
        log.info("落巢回收确认完成：orderId={} goodsId={} uavId={} nestId={}",
                msg.orderId(), msg.goodsId(), msg.uavId(), msg.nestId());
        return OK;
    }

    /** 野外迫降点不接回收确认：货物不在系统设施内，须人工回收（《21》§8）。 */
    private static boolean isFieldLanding(String nestId) {
        String normalized = nestId.trim().toUpperCase();
        return normalized.startsWith("FIELD") || normalized.contains("野外")
                || normalized.contains("迫降点");
    }
}
