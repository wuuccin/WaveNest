package com.waterlogistics.dispatch.reschedule;

import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.algo.inflight.HeadingWindowDelta;
import com.waterlogistics.algo.inflight.IntentChangeJudge;
import com.waterlogistics.algo.inflight.IntentChangeSignal;
import java.util.List;

/**
 * 飞行过程中的重新调度（6b 闭合点）——骨架。
 *
 * <p>落款：《包结构设计》§二行 83
 * 「{@code reschedule/  # ★ 飞行过程中的重新调度（吴）：意图变更判据入口→放弃会面→订单重入队}」，
 * 负责人吴可欣，属 {@code wl-m2-dispatch}。
 *
 * <p><b>链路</b>：意图变更判据入口 → 放弃会面 → 原机返航 → 订单重入队。
 * 对应《29》§3.5.1(1b)：6b 出口的处置结论为「继续追 ／ 放弃本次会面」，
 * 分别交功能 9（继续追）与功能 10（放弃本次会面 → 返航复核与归巢资源判定）。
 *
 * <p><b>今天只定接口签名</b>——《10-15开工当日任务清单》§3.1 C 段的完成判据是
 * 「接口签名定下来，让罗才辉/张睿哲知道 16 号怎么对接」。
 * 因此本类刻意<b>不 import</b> 尚未交付的 {@code homecoming}（返航复核）与
 * 张睿哲的 {@code queue}（任务队列），对接点一律用 TODO 标注；
 * 否则编译失败会连带阻塞全组。
 *
 * <p><b>红线（《29》§3.5.3 红线①）</b>：本类<b>不在飞行中改派／换机</b>。
 * 「原机返航」的"原机"即当前执飞的那架，货物已装机后空中不可转移
 * （《23》§6 约束 1：{@code 已装机} 后货物绑死在该机上）。
 */
public class RescheduleService {

    /**
     * 6b 意图变更判据入口（今天即已可用）。
     *
     * <p>模块层只做编排，判据本身调 {@code wl-algo} 的纯函数。
     * 阈值 <b>由调用方从 {@code sys_param} 读出后注入</b>，本方法不读库、不硬编码
     * ——E 组 26 项目前无 camelCase 键名（《10-15》§5.7 第 5 条：不臆造）。
     * 起步可先用 {@link IntentChangeJudge#SUGGESTED_THETA_TH_DEG}（《84》§8 建议 20°）。
     *
     * @param window     船侧滑窗样本（实测 COG vs 预测航迹切向），由调用方维护定长窗口
     * @param thetaThDeg 阈值 Θ_th（度）
     * @return 持续监测信号；采信 {@link IntentChangeSignal#persistent()}（滤抖后结论）
     */
    public IntentChangeSignal evaluateIntentChange(List<HeadingSample> window, double thetaThDeg) {
        return IntentChangeJudge.judge(HeadingWindowDelta.compute(window), thetaThDeg);
    }

    /**
     * 放弃本次会面 → 原机返航（骨架，16 号接链）。
     *
     * <p>后续待接：由 {@code dispatch.homecoming}（吴可欣，返航复核与归巢资源判定）
     * 做三级落点判定——回原巢 → 迫停他巢 → 迫降野外，
     * <b>按序降级、迫降是最后一级、不可跳级</b>（《29》§3.5.1 降级口径）。
     *
     * @param orderId 订单标识
     * @param droneId 当前执飞的无人机标识（原机，不得换机）
     */
    public void abandonMeetAndReturnHome(String orderId, String droneId) {
        // TODO(16 号)：① 调 dispatch.homecoming 做返航复核与三级落点判定；
        //              ② 落点结论交 B 组生成返航航迹与精算返程电量（《29》§3.5.1(3)）；
        //              ③ 迫停他巢时向 D 组发起占位请求（仅第二级，《84》C-12）；
        //              ④ 返航执行指令下发 M4。
        throw new UnsupportedOperationException(
                "骨架未接线：放弃会面 → 原机返航，16 号接 dispatch.homecoming（orderId=" + orderId
                        + ", droneId=" + droneId + "）");
    }

    /**
     * 订单重入队（骨架，16 号接链）。
     *
     * <p>后续待接：张睿哲的 {@code dispatch.queue}（任务队列与状态管理），
     * 照《23》推进订单/任务两套状态机；写入方口径为
     * 「<b>检测归 M4，决策归 M2，落库归 M6</b>」（《23》§8 原则）。
     *
     * <p>注意《23》§5：订单与任务取值在任务存续期间保持一致，
     * {@code mission.status} 的任何变更须在<b>同一事务内</b>回写 {@code order_task.status}。
     *
     * @param orderId 订单标识
     */
    public void requeueOrder(String orderId) {
        // TODO(16 号)：① 调 dispatch.queue（张睿哲）按《23》§3 推进订单状态；
        //              ② 货物状态按《23》§6 回退（已装机 → 已回收 需落巢回收完成）；
        //              ③ 由 M6 在同一事务内落库；
        //              ④ 由 wl-app/push 推 order_state 帧。
        throw new UnsupportedOperationException(
                "骨架未接线：订单重入队，16 号接 dispatch.queue（orderId=" + orderId + "）");
    }
}
