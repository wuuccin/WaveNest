package com.waterlogistics.dispatch.reschedule;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.dispatch.candidate.CandidateSelector;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 重派服务（10-16 闭环第 4 环，序 4 核心）。
 *
 * <p>触发：吴可欣「放弃会面 → 订单重入队」事件。流程严格照分水岭口径
 * （《10-16》四·硬口径）：订单先重入队、再重派一架新机，**不是**让原机改派
 * （回收后重派，不支持中途换机）。
 *
 * <p>步骤：
 * <ol>
 *   <li>{@link TaskQueueService#requeue} 重入队（清原机，状态回待调度）；</li>
 *   <li>{@link CandidateSelector#selectBest} 重新选机；</li>
 *   <li>{@link TaskQueueService#startDispatch} + {@link TaskQueueService#dispatch} 派发新机；</li>
 *   <li>起飞时刻简化用「当前时间 + 缓冲」，与罗才辉回填口径一致（正式由 6a 回填）。</li>
 * </ol>
 * 无可用候选时抛异常，由调用方决定回退或转人工。
 */
@Service
public class RescheduleService {

    /** 起飞时刻缓冲分钟数（演示简化；正式由 6a 精算回填） */
    private static final int TAKEOFF_BUFFER_MINUTES = 5;

    private final TaskQueueService taskQueueService;
    private final CandidateSelector candidateSelector;
    private final OrderTaskMapper orderTaskMapper;

    public RescheduleService(TaskQueueService taskQueueService,
                             CandidateSelector candidateSelector,
                             OrderTaskMapper orderTaskMapper) {
        this.taskQueueService = taskQueueService;
        this.candidateSelector = candidateSelector;
        this.orderTaskMapper = orderTaskMapper;
    }

    /**
     * 重派入口：重入队 → 选机 → 派发。
     *
     * @param orderId 需重派的订单
     * @return 新派发的 droneId
     */
    @Transactional
    public String reschedule(String orderId) {
        // 第 1 步：重入队（清原机 + 状态回待调度 + task_log 留痕）
        taskQueueService.requeue(orderId);

        // 第 2 步：重新选机
        OrderTaskEntity order = orderTaskMapper.selectById(orderId);
        CandidateGenerator.CandidateCombo best = candidateSelector.selectBest(order);
        if (best == null) {
            throw new IllegalStateException("订单 " + orderId + " 重派失败：无可用候选组合");
        }

        // 第 3 步：调度中 → 派发新机
        taskQueueService.startDispatch(orderId);
        LocalDateTime takeoffTime = LocalDateTime.now().plusMinutes(TAKEOFF_BUFFER_MINUTES);
        taskQueueService.dispatch(orderId, best.droneId(), takeoffTime);

        return best.droneId();
    }
}
