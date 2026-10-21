package com.waterlogistics.dispatch.reschedule;

import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.algo.inflight.HeadingWindowDelta;
import com.waterlogistics.algo.inflight.HomecomingDecision;
import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.IntentChangeJudge;
import com.waterlogistics.algo.inflight.IntentChangeSignal;
import com.waterlogistics.algo.inflight.NestCandidate;
import com.waterlogistics.dispatch.homecoming.HomecomingService;
import com.waterlogistics.dispatch.plan.DispatchOrchestrator;
import com.waterlogistics.dispatch.queue.TaskQueueService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 飞行过程中的重新调度（<b>6b 闭合点</b>）＋ 落巢回收后的<b>重派</b>（序 4）。
 *
 * <p>本类由两人并行开发后合并而成，两类职责同属「重调度」故保留在一个类里：
 * <ul>
 *   <li><b>6b 桥段（吴可欣）</b>：{@link #evaluateIntentChange} 判据入口 →
 *       {@link #abandonMeetAndReturnHome} 放弃会面 → 原机返航 →
 *       {@link #requeueOrder} 订单重入队；</li>
 *   <li><b>重派（张睿哲）</b>：{@link #reschedule} 重入队 → 选机 → 派发新机。</li>
 * </ul>
 *
 * <p><b>合并时的关键裁决</b>：重入队能力<b>以张睿哲的 {@link TaskQueueService#requeue} 为准</b>，
 * 我原先定义的 {@code OrderRequeuePort} 端口<b>已删除</b>——他已把该能力做进
 * {@code TaskQueueService}（清原机 ＋ 状态回待调度 ＋ {@code task_log} 留痕），
 * 且两者同在 {@code wl-m2-dispatch} 模块内，直接调用即可，无需再经端口反转。
 * 但 {@link #requeueOrder} 的<b>物理前置校验保留</b>（《21》§8，见该方法 javadoc），
 * 那是「能不能重派」的裁决，不是落地动作。
 *
 * <p><b>红线（《29》§3.5.3 红线①）</b>：本类<b>不在飞行中改派／换机</b>。
 * 「原机返航」的"原机"即当前执飞的那架，货物已装机后空中不可转移
 * （《23》§6 约束 1：{@code 已装机} 后货物绑死在该机上）。
 * 重派只能走「货物回收后重派」（《21》§8），不得写成改派／接力。
 */
@Service
public class RescheduleService {

    /** 返航复核与三级落点判定（模块层编排，落《包结构设计》行 84） */
    private final HomecomingService homecoming;

    /** 重入队（张睿哲归口，序 4） */
    private final TaskQueueService taskQueueService;

    /** 选机→会面→派发 共用编排（C0 抽出，10-21；首派与重派同一串法） */
    private final DispatchOrchestrator dispatchOrchestrator;

    public RescheduleService(TaskQueueService taskQueueService,
            HomecomingService homecoming,
            DispatchOrchestrator dispatchOrchestrator) {
        this.taskQueueService = taskQueueService;
        this.homecoming = homecoming;
        this.dispatchOrchestrator = dispatchOrchestrator;
    }

    /**
     * 6b 意图变更判据入口。
     *
     * <p>模块层只做编排，判据本身调 {@code wl-algo} 的纯函数。
     * 阈值 <b>由调用方从 {@code sys_param} 读出后注入</b>，本方法不读库、不硬编码。
     * E 组键名已于 2026-10-16 补登记（《37》§5.14.1，见 {@code thetaThDeg}）；
     * 落库前起步可先用 {@link IntentChangeJudge#SUGGESTED_THETA_TH_DEG}（《84》§8 建议 20°）。
     *
     * <p><b>滑窗样本由谁配</b>：<b>预测侧 {@code PredictionService} 负责配对，本方法只消费</b>。
     * 单条实测 AIS 观测 → {@code PredictionService.toHeadingSample(AisMessage)}（卢肄文 10-16 实现）
     * 配成一个 {@link HeadingSample}（实测 COG ＋ 预测航迹切向 ＋ SOG ＋ 时刻）；
     * 调用方把若干条攒成定长滑窗后传进来。
     *
     * <p><b>纪律（勿在本层重复造配对逻辑）</b>：预测航迹切向必须由 <b>M2 常驻层取完整
     * {@code PredictionSnapshot} 对象</b>算出，<b>不得从 WS 帧 {@code ship_traj} 反推</b>
     * ——《29》§3.5.2 与 {@code HeadingSample} javadoc 均有明文。
     *
     * <p><b>为什么不在本层按时刻严格对齐</b>：船的 AIS 上报间隔与预测点间隔本就不一致，
     * 严格对齐会大量丢样本；正确做法是「取最接近实测时刻的那个预测点 → 其下一点」的方位，
     * 这正是预测侧 {@code tangentDegAt} 的做法。2026-10-16 曾在本包写过一个
     * {@code IntentChangeWiring} 做严格对齐配对，与该实现重复且会把 SOG 填成 0
     * （令依赖 SOG 的判据失效），<b>已删除，勿改回</b>。
     *
     * @param window     船侧滑窗样本（实测 COG vs 预测航迹切向），由调用方维护定长窗口
     * @param thetaThDeg 阈值 Θ_th（度）
     * @return 持续监测信号；采信 {@link IntentChangeSignal#persistent()}（滤抖后结论）
     */
    public IntentChangeSignal evaluateIntentChange(List<HeadingSample> window, double thetaThDeg) {
        return IntentChangeJudge.judge(HeadingWindowDelta.compute(window), thetaThDeg);
    }

    /**
     * 放弃本次会面 → 原机返航（<b>16 号已接 {@code dispatch.homecoming}</b>）。
     *
     * <p>《29》§3.5.1(1b)：6b 处置结论为「放弃本次会面」时交功能 10 做返航复核与三级落点判定，
     * 降级顺序为回原巢 → 迫停他巢 → 迫降野外，<b>按序降级、迫降是最后一级、不可跳级</b>。
     *
     * <p>本方法只做链路第 1 步：接上落点判定并返回三级结论。落巢回收后的<b>订单重入队</b>
     * 是第 2 步，见 {@link #requeueOrder}。
     *
     * <p><b>红线</b>：货物随原机返航、落巢即回收（《84》第5章「货机同行，落巢即回收」），
     * 本方法不换机、不接力。
     *
     * @param input      功能10 入口输入快照（含 orderId、droneId、homeNestId、剩余电量等）
     * @param params     阈值参数，<b>须由调用方从 {@code sys_param} 读出后注入</b>
     * @param candidates 候选他巢台账，<b>须由调用方从 D 组 {@code occupancy_slot} 读出后注入</b>
     * @return 三级落点结论（含候选他巢逐项打叉审计记录）
     */
    public HomecomingDecision abandonMeetAndReturnHome(HomecomingInput input, HomecomingParams params,
            List<NestCandidate> candidates) {
        return homecoming.reviewAndDecide(input, params, candidates);
    }

    /**
     * 订单重入队（<b>16 号已接链</b>）：原机落巢、货物回收后把订单放回「待调度」等待重派新机。
     *
     * <p><b>重派的前置是物理约束，不是流程节点（《21》§8）</b>：货物同一时刻只能处在一个载体上，
     * 货物装机后绑死在原机上、空中不可转移（《23》§6 约束 1）。所以「放弃本次会面」<b>不能</b>
     * 直接重入队——那一刻货物还在原机上。按 §8 的落点—重派表，本方法逐条硬校验：
     *
     * <ul>
     *   <li><b>落原巢／迫停他巢</b>：落巢 ＋ 回收确认 → 货物「已回收」，订单回「待调度」，<b>可重派</b>；
     *   <li><b>迫降野外</b>：落在系统设施之外 → 订单走「失败」、任务走「迫降待回收」，
     *       <b>须人工回收后再入队</b>，本方法抛异常而不是假重入队；
     *   <li><b>失联灭失</b>：货物随之灭失 → 订单走「异常终止」，同样不重入队。
     * </ul>
     *
     * <p><b>落地动作委托 {@link TaskQueueService#requeue}</b>（张睿哲归口，序 4）：
     * 清原机、状态回待调度、写 {@code task_log} 事件流、追加 {@code order_state} 时间线节点。
     * 本方法只做<b>前置条件裁决</b>，不碰 Mapper、不落库（《23》§8「落库归 M6」）。
     *
     * @param cmd 重入队入参（含原机落点、落巢与回收确认位）
     * @throws IllegalStateException 落点为野外迫降／灭失，或尚未落巢、尚未回收时——
     *                               此时<b>不得</b>放行重派，须按《21》§8 走人工回收或终止
     */
    public void requeueOrder(OrderRequeueCommand cmd) {
        requireRequeueable(cmd);
        taskQueueService.requeue(cmd.orderId());
    }

    /**
     * 重入队前置条件裁决（《21》§8 落点—重派表的代码化）。
     *
     * <p>三条全部按「宁可抛异常也不放行」处理：一旦这里放过不该重派的单子，
     * 后果是<b>同一票货被派给第二架机</b>，而货物还在第一架机上——这在物理上无法交付，
     * 属《10》§9 禁止的「空中转运／接力」类错误。
     */
    private void requireRequeueable(OrderRequeueCommand cmd) {
        if (cmd.lossConfirmed()) {
            throw new IllegalStateException("订单 " + cmd.orderId() + " 的货物已灭失，重入队无意义："
                    + "按《21》§8 灭失订单走「异常终止」，由 M4 判定上报、M2 终止");
        }
        if (!cmd.landedAtNest()) {
            throw new IllegalStateException("订单 " + cmd.orderId() + " 的原机 " + cmd.droneId()
                    + " 尚未确认落巢，不得重入队：货物装机后绑死在该机上、空中不可转移"
                    + "（《23》§6 约束 1、《21》§8「唯一可行的转移途径是成功降落到机巢」）");
        }
        if (!cmd.recyclingConfirmed()) {
            throw new IllegalStateException("订单 " + cmd.orderId() + " 落点 " + cmd.landingPointId()
                    + " 尚未确认货物回收：落巢只让货物「可被回收」，"
                    + "回收确认才把货物转为可用并构成重派唯一入口（《21》§8）");
        }
    }

    /**
     * 重派入口（张睿哲，序 4）：重入队 → 派发编排（选机 → 会面 → 派发）。
     *
     * <p>触发：吴可欣「放弃会面 → 订单重入队」事件之后。流程严格照分水岭口径
     * （《10-16》四·硬口径）：订单先重入队、再重派一架新机，<b>不是</b>让原机改派
     * （回收后重派，不支持中途换机）。
     *
     * <p>步骤：
     * <ol>
     *   <li>{@link TaskQueueService#requeue} 重入队（清原机，状态回待调度）；</li>
     *   <li>{@link DispatchOrchestrator#orchestrate} 共用派发编排（C0 抽出）：
     *       组批 → 选机 → 会面求解 → 派发 → 回填会面点，
     *       与首次派发（{@code DispatchTrigger}）同一串法。</li>
     * </ol>
     *
     * <p><b>顺序口径变化（2026-10-21，勿改回）</b>：此前「会面求解」排在
     * 「组批（{@code startDispatch}）」之前，理由是「无解时不得把订单推进到
     * 『调度中』再回退」。共用段改为<b>组批在前</b>（B3 闸门检查内含于组批，
     * 熔断时延后为空跑求解会误伤待唤醒语义）；无解／无候选抛异常后由
     * {@link Transactional} 整体回滚，「调度中」不会残留为脏状态，
     * 旧顺序的防回退动机由事务兜底，不再成立。
     *
     * <p><b>注意</b>：本方法走的是「已确认落巢回收」之后的路径，
     * 若尚未回收应改调 {@link #requeueOrder}（含《21》§8 的物理前置校验），
     * 二者的前置条件不同，不可混用。
     *
     * @param orderId 需重派的订单
     * @return 新派发的 droneId；<b>闸门熔断时返回 {@code null}</b>（订单转「已受理待唤醒」，
     *         待闸门解除后重入队再编排）
     * @throws IllegalStateException 无可用候选组合／会面求解无解／订单状态不符
     */
    @Transactional
    public String reschedule(String orderId) {
        // 第 1 步：重入队（清原机 + 状态回待调度 + task_log 留痕）
        taskQueueService.requeue(orderId);

        // 第 2 步：共用派发编排（组批 → 选机 → 会面 → 派发 → 回填会面点）
        return dispatchOrchestrator.orchestrate(orderId);
    }
}
