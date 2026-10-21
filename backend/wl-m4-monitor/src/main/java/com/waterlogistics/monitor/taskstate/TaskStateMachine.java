package com.waterlogistics.monitor.taskstate;

import com.waterlogistics.contract.state.TaskStatus;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态流转（模块四功能 2.2.1）——骨架，照《34-M4-飞行任务监控》§3.2，
 * 状态集与流转规则以《23-状态机定义》§4／§8 为准。
 *
 * <p>落款：《包结构设计》§二行 112
 * 「{@code taskstate/  # 任务状态流转、进度与 ETA 计算、投送确认（吴）}」，
 * 负责人吴可欣，属 {@code wl-m4-monitor}。
 *
 * <p><b>状态集一律复用 {@link TaskStatus} 八态，不自造状态词</b>
 * （《23》§9 禁用写法表：落库枚举一律用《23》§3–§7 写法）。
 *
 * <p>流转表出处：
 * <ul>
 *   <li>正向（{@code 23} §8 写入方 M4）：
 *       {@code 已派发 → 去程中 → 末段交接中 → 返航中 → 已完成}；</li>
 *   <li>异常分支（{@code 23} §4）：{@code 迫停中}（落他巢后转 {@code 已完成}）、
 *       {@code 迫降待回收}、{@code 异常终止}；</li>
 *   <li>写入方（{@code 23} §8）：{@code → 迫停中}／{@code 迫降待回收} 为
 *       <b>M4 上报、M2 决策</b>；原则「检测归 M4，决策归 M2，落库归 M6」。</li>
 * </ul>
 *
 * <p><b>两处必须记住的约束</b>：
 * <ol>
 *   <li>《23》§4 约束 2：飞行中途的 <b>6b 意图变更</b>、风速突变、临时禁飞
 *       <b>不新增任务状态</b>，只触发调度侧重跑，状态仍停在 {@code 去程中}——
 *       所以 6b 判据成立<b>不会</b>在本表产生任何转移。</li>
 *   <li>《23》§4 约束 3：末段内部的四子阶段（接近、匹配、交接、脱离）
 *       不建为独立任务状态，属 {@code 末段交接中} 的内部过程。</li>
 * </ol>
 */
public final class TaskStateMachine {

    /**
     * 合法转移表。
     *
     * <p>关于 {@code 异常终止} 的源状态：《23》§4 只写「无人机灭失判定成立」，
     * 未限定源状态。此处按「灭失＝空中失事」的语义取<b>保守口径</b>——
     * 只允许从三个在飞状态（{@code 去程中}／{@code 末段交接中}／{@code 返航中}）转入；
     * {@code 迫停中} 与 {@code 迫降待回收} 已落地，不再转 {@code 异常终止}。
     * 若后续组长裁定不同，改这一处即可。
     */
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = buildTransitions();

    private TaskStateMachine() {
    }

    private static Map<TaskStatus, Set<TaskStatus>> buildTransitions() {
        Map<TaskStatus, Set<TaskStatus>> table = new EnumMap<>(TaskStatus.class);
        table.put(TaskStatus.DISPATCHED, Set.of(TaskStatus.OUTBOUND));
        table.put(TaskStatus.OUTBOUND, Set.of(TaskStatus.HANDOVER, TaskStatus.ABORTED));
        table.put(TaskStatus.HANDOVER, Set.of(TaskStatus.RETURNING, TaskStatus.ABORTED));
        table.put(TaskStatus.RETURNING, Set.of(
                TaskStatus.FINISHED, TaskStatus.DIVERTED,
                TaskStatus.AWAITING_RECOVERY, TaskStatus.ABORTED));
        table.put(TaskStatus.DIVERTED, Set.of(TaskStatus.FINISHED));
        table.put(TaskStatus.AWAITING_RECOVERY, Set.of());
        table.put(TaskStatus.FINISHED, Set.of());
        table.put(TaskStatus.ABORTED, Set.of());
        return table;
    }

    /**
     * 判定一次流转是否合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 是否允许
     */
    public static boolean canTransit(TaskStatus from, TaskStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 列出某状态下的全部合法后继，便于 M4 侧做上报前的自检。
     */
    public static Set<TaskStatus> nextStates(TaskStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    /**
     * 是否为终态（没有合法后继）。
     *
     * <p>{@code 已完成} 与 {@code 异常终止} 是正常闭环；
     * {@code 迫降待回收} 后续转<b>人工</b>回收，已不在本状态机内流转，故也按终态处理。
     */
    public static boolean isTerminal(TaskStatus status) {
        return nextStates(status).isEmpty();
    }

    /**
     * 校验并推进：非法流转直接抛异常，避免脏状态落库。
     *
     * <p>今天只做<b>纯校验</b>。16 号接链时在此补：
     * ① 写 {@code mission.status} 并在<b>同一事务内</b>回写 {@code order_task.status}
     * （《23》§5 派生规则第 2 条）；
     * ② 每次状态变更记录时间（《34》§3.2 功能 2.2.1）；
     * ③ {@code 迫停中}／{@code 迫降待回收} 由 M4 上报、M2 决策后再写入；
     * ④ 落库归 M6。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 目标状态（便于链式使用）
     * @throws IllegalStateException 非法流转
     */
    public static TaskStatus requireTransit(TaskStatus from, TaskStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateException(
                    "非法任务状态流转：" + from.getValue() + " → " + to.getValue()
                            + "（合法后继：" + nextStates(from) + "）");
        }
        return to;
    }
}
