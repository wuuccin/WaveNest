package com.waterlogistics.dispatch.reschedule;

import com.waterlogistics.contract.state.AlertLevel;
import java.util.Locale;
import java.util.Optional;

/**
 * 在飞安全复核结论（气象超限路径）。
 *
 * <p><b>四档为何裁成三值</b>：《84》§4.1 的四档是继续／调整／重调度／终止，而 §3.3 边界
 * 明写「不在飞行中改派／换机」，重调度档在飞行中退化为终止，故气象路径<b>不设</b>
 * 重调度档。这不是漏项。
 *
 * <p><b>三值的下游差别</b>：
 * <table border="1">
 *   <caption>结论—状态—广播</caption>
 *   <tr><th>结论</th><th>含义</th><th>订单状态</th><th>广播</th></tr>
 *   <tr><td>{@link Verdict#CONTINUE}</td><td>告警已解除，不构成在飞风险</td><td>不动</td><td>不发</td></tr>
 *   <tr><td>{@link Verdict#ADJUST}</td><td>警告级，按 C-11 走调整档</td><td>不动</td><td>发</td></tr>
 *   <tr><td>{@link Verdict#TERMINATE}</td><td>严重级，本次任务作废，交功能 10 定落点</td><td>有可执行落点时回写「返航中」；迫降野外由功能 10 置「失败」；功能 10 返回空则不动</td><td>发</td></tr>
 * </table>
 *
 * <p><b>终止档为什么回写订单状态</b>：《23》§4 约束 2 原写「风速突变不新增任务状态、
 * 状态仍停在去程中」，本次加中止返航例外并在 §8 登记「订单 去程中 → 返航中」。
 * 不回写时 {@code FlightPhaseAdvancer} 不看无人机是否已中止，仍按 {@code meet_time}
 * 时间轴把订单推成「末段交接中」，{@code task_log} 会多出一条没发生的交接迁移。
 * 判档与回写分离：本类只出结论，状态落库由 {@code InflightSafetyService} 在
 * 功能 10 返回可执行落点后完成。
 *
 * @param verdict     三值结论
 * @param eventId     告警事件标识（回指原始告警）
 * @param orderId     订单标识，可为空（告警未带单号且未反查到）
 * @param droneId     无人机标识
 * @param windSpeedMs 判档时的风速（m/s）
 * @param alertLevel  判档时的告警级别
 * @param reason      判档说明（进广播事件与运行日志；{@code task_log} 无备注列，不落库）
 */
public record InflightSafetyVerdict(
        Verdict verdict,
        String eventId,
        String orderId,
        String droneId,
        double windSpeedMs,
        AlertLevel alertLevel,
        String reason) {

    /** 复核三值结论。{@code opType} 即落 {@code task_log.op_type} 的文本。 */
    public enum Verdict {

        /** 告警已解除，气象不构成在飞风险；只留痕、不改状态、不广播 */
        CONTINUE("继续", "在飞安全复核-继续"),

        /** 警告级；按 C-11 走调整档，留痕并广播，不改状态 */
        ADJUST("调整", "在飞安全复核-调整"),

        /** 严重级；C-11 例外句构成返航可行性否决，留痕并广播，落点交功能 10 定 */
        TERMINATE("终止", "在飞安全复核-终止");

        private final String value;
        private final String opType;

        Verdict(String value, String opType) {
            this.value = value;
            this.opType = opType;
        }

        /** 返回中文取值 */
        public String getValue() {
            return value;
        }

        /** 返回落 {@code task_log.op_type} 的文本 */
        public String getOpType() {
            return opType;
        }
    }

    public InflightSafetyVerdict {
        if (verdict == null) {
            throw new IllegalArgumentException("复核结论不得为空：空结论用 Optional.empty 表达，不构造本对象");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("告警事件标识不得为空");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("无人机标识不得为空");
        }
    }

    /** 是否构成在飞风险（调整与终止为真，继续为假）——供前端与告警板区分展示 */
    public boolean riskMarked() {
        return verdict != Verdict.CONTINUE;
    }

    /**
     * 判档（按序命中，纯函数、无 IO）。
     *
     * <p>判档顺序：
     * <ol>
     *   <li>{@code cleared} 为真 → 继续（《86》§扩.3 第 4 条迟滞清除：连续
     *       {@code weatherClearDebounce} 点低于预警阈值且无突变）；</li>
     *   <li>级别为「严重」→ 终止。《29》§5 C-11 例外句：G 判定的严重档视为已构成
     *       返航可行性否决，功能 9 可出终止档，落点仍交功能 10 三级判定。严重档依据是
     *       《86》§扩.3 第 2 条（风速绝对值达严重阈值 14.0 m／s，高于机队最高抗风上限
     *       12.0）与第 6 条（突变触发），M4 {@code WeatherViolationDetector} 已按此实现；
     *       不是「持续恶化」的结论。</li>
     *   <li>其余（级别为「警告」）→ 调整（C-11「默认档位＝调整，不直通、不直达终止」）。</li>
     * </ol>
     *
     * <p><b>{@code durationSec} 不参与判档</b>：它只用于构造时把负数归零，本方法不读它。
     * 「持续时长／持续恶化」由 M4 检测器按迟滞采样点判完并折叠进 {@code alertLevel}，
     * M2 不另设时长阈值键，也不在这里二次判定。
     *
     * <p><b>本方法不查库</b>：订单是否存在、是否处于去程中／末段交接中（前置 P1、P2）
     * 由调用方在传入前保证——本层是纯判档，便于单测钉死三条判档与六类失败。
     *
     * <p><b>空结论的含义</b>：返回 {@link Optional#empty()} 表示「没判」，
     * 不等于「判过且安全」。与 {@link RescheduleService#reschedule} 用
     * {@code Optional<String>} 区分「延后」与「派发成功」的写法一致。
     *
     * @param request 告警快照；为 {@code null} 时返回空结论
     * @return 判档结论；入参不可判时为空
     */
    public static Optional<InflightSafetyVerdict> judge(InflightSafetyRequest request) {
        if (request == null) {
            return Optional.empty();
        }
        // P3：风速须为有限值且不小于零。非有限（NaN／±Infinity）与负数一律不判，
        // 不能兜成「继续」——那是把坏数据伪装成安全结论。
        if (!Double.isFinite(request.windSpeedMs()) || request.windSpeedMs() < 0) {
            return Optional.empty();
        }
        // P4：级别须在气象两档内；Info 与漏填都不判。
        if (!InflightSafetyRequest.isWeatherAlertLevel(request.alertLevel())) {
            return Optional.empty();
        }
        // 观测时刻无效则无法留痕排序，不判。
        if (request.observedAtMs() <= 0) {
            return Optional.empty();
        }

        Verdict verdict;
        if (request.cleared()) {
            verdict = Verdict.CONTINUE;
        } else if (request.alertLevel() == AlertLevel.CRITICAL) {
            verdict = Verdict.TERMINATE;
        } else {
            verdict = Verdict.ADJUST;
        }
        return Optional.of(new InflightSafetyVerdict(verdict, request.eventId(), request.orderId(),
                request.droneId(), request.windSpeedMs(), request.alertLevel(),
                describe(verdict, request)));
    }

    private static String describe(Verdict verdict, InflightSafetyRequest request) {
        String wind = String.format(Locale.ROOT, "%.1f", request.windSpeedMs());
        switch (verdict) {
            case CONTINUE:
                return "气象告警已解除（连续去抖点低于预警阈值且无突变，《86》§扩.3 第 4 条），"
                        + "本次不构成在飞风险，状态不动";
            case ADJUST:
                return "警告级风速 " + wind + " m/s：按《29》§5 C-11 默认档位＝调整，"
                        + "不直通、不直达终止，状态不动";
            case TERMINATE:
                return "严重级风速 " + wind + " m/s：风速达严重阈值或突变触发"
                        + "（《86》§扩.3 第 2、6 条），按《29》§5 C-11 例外句"
                        + "视为已构成返航可行性否决，本次任务作废，落点交功能 10 三级判定";
            default:
                // 枚举加值时不静默兜底：判档说明缺失会让 task_log 出现空备注
                throw new IllegalStateException("未覆盖的复核结论：" + verdict);
        }
    }
}
