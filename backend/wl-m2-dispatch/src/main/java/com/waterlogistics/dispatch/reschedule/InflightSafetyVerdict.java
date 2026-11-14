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
 *   <tr><td>{@link Verdict#TERMINATE}</td><td>严重级，本次任务作废，交功能 10 定落点</td><td>不动</td><td>发</td></tr>
 * </table>
 *
 * <p><b>终止档为什么不动订单状态</b>：《23》§3 约束 2「飞行中途的 6b 意图变更、风速突变、
 * 临时禁飞不新增任务状态，只触发调度侧重跑；状态仍停在去程中」。会话作废的落点由
 * 功能 10 判定后写无人机状态，订单状态继续由 {@code FlightPhaseAdvancer} 推进。
 *
 * @param verdict     三值结论
 * @param eventId     告警事件标识（回指原始告警）
 * @param orderId     订单标识，可为空（告警未带单号且未反查到）
 * @param droneId     无人机标识
 * @param windSpeedMs 判档时的风速（m/s）
 * @param alertLevel  判档时的告警级别
 * @param reason      判档说明，写入 {@code task_log} 备注列
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

        /** 严重级；本次任务作废，交功能 10 定落点，留痕并广播 */
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
     *   <li>级别为「严重」→ 终止（C-11 的「持续恶化」在《86》§扩.3 第 6 条已判成严重，
     *       M2 不另设时长阈值键）；</li>
     *   <li>其余（级别为「警告」）→ 调整（C-11「默认档位＝调整，不直通、不直达终止」）。</li>
     * </ol>
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
                return "严重级风速 " + wind + " m/s：按《86》§扩.3 第 6 条已判成严重，"
                        + "本次任务作废，交功能 10 定落点，订单状态不动";
            default:
                // 枚举加值时不静默兜底：判档说明缺失会让 task_log 出现空备注
                throw new IllegalStateException("未覆盖的复核结论：" + verdict);
        }
    }
}
