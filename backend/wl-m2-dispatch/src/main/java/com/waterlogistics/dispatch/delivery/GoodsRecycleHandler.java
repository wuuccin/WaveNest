package com.waterlogistics.dispatch.delivery;

import com.waterlogistics.contract.mq.GoodsRecycleConfirmation;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.goods.GoodsLifecycleService;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.entity.TaskLogEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import com.waterlogistics.storage.repository.TaskLogMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
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

    /**
     * 回收完成后需要回写「待调度」的订单状态。
     *
     * <p>这四个状态是「货物随原机回巢、尚未交付」的全部可能：已派发（未起飞即中止）、
     * 去程中、末段交接中（中止返航）、返航中（已在中止返航路上）。
     * <b>不在此集合的一律不动订单</b>——已完成（正常交付后回收）、已拒绝／失败／异常终止
     * 都不该被回收事件改回待调度。
     */
    private static final Set<OrderStatus> REQUEUE_AFTER_RECYCLE = EnumSet.of(
            OrderStatus.DISPATCHED, OrderStatus.OUTBOUND,
            OrderStatus.HANDOVER, OrderStatus.RETURNING);

    private final GoodsLifecycleService goodsLifecycleService;
    private final OrderTaskMapper orderTaskMapper;
    private final TaskLogMapper taskLogMapper;
    private final TaskQueueService taskQueueService;

    public GoodsRecycleHandler(GoodsLifecycleService goodsLifecycleService,
            OrderTaskMapper orderTaskMapper, TaskLogMapper taskLogMapper,
            TaskQueueService taskQueueService) {
        this.goodsLifecycleService = goodsLifecycleService;
        this.orderTaskMapper = orderTaskMapper;
        this.taskLogMapper = taskLogMapper;
        this.taskQueueService = taskQueueService;
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

        requeueAfterRecycle(order);
        log.info("落巢回收确认完成：orderId={} goodsId={} uavId={} nestId={}",
                msg.orderId(), msg.goodsId(), msg.uavId(), msg.nestId());
        return OK;
    }

    /**
     * 回收确认的第二步：订单回「待调度」，让《21》§8 的「落巢回收 → 重派」走得通。
     *
     * <p><b>不补这一步的后果</b>：中止返航／迫停他巢的单子在回收后仍停在「返航中」，
     * {@code RescheduleService.requeueOrder} 的 {@code requireNotInFlight} 与
     * {@link TaskQueueService#requeue} 的白名单各挡一次——同一票货永远重派不出去。
     * 这里的写点就是 §8 那张表里「回收确认 → 订单回待调度」那一格的落点。
     *
     * <p><b>复用 {@link TaskQueueService#requeue} 而不是直接改状态</b>：它一并在同一事务里
     * 清派单字段（机号／会面点）、把无人机置空闲、写「重入队」审计并发状态事件。
     * 直接 {@code update order_task.status} 会留下「机号还绑着、机还占着」的脏行。
     *
     * <p>状态不在 {@link #REQUEUE_AFTER_RECYCLE} 内时只记日志、不动订单：
     * 正常交付后（已完成）的回收不走重派。
     */
    private void requeueAfterRecycle(OrderTaskEntity order) {
        OrderStatus status;
        try {
            status = OrderStatus.fromValue(order.getStatus());
        } catch (RuntimeException e) {
            log.warn("回收确认后不回写订单：状态不可解析 orderId={} status={}",
                    order.getOrderId(), order.getStatus());
            return;
        }
        if (!REQUEUE_AFTER_RECYCLE.contains(status)) {
            log.info("回收确认后不动订单：当前状态「{}」不属中止返航路径 orderId={}",
                    status.getValue(), order.getOrderId());
            return;
        }
        taskQueueService.requeue(order.getOrderId());
        log.info("回收确认后订单回「待调度」，等待重派：orderId={} 原状态={}",
                order.getOrderId(), status.getValue());
    }

    /** 野外迫降点不接回收确认：货物不在系统设施内，须人工回收（《21》§8）。 */
    private static boolean isFieldLanding(String nestId) {
        String normalized = nestId.trim().toUpperCase();
        return normalized.startsWith("FIELD") || normalized.contains("野外")
                || normalized.contains("迫降点");
    }
}
