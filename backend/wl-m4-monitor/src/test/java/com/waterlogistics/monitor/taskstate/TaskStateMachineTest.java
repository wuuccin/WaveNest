package com.waterlogistics.monitor.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.state.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * 任务状态流转骨架的校验：正向链放行、非法流转拒绝、异常分支可走。
 * 状态集一律复用契约 {@link TaskStatus} 八态，不自造状态词。
 */
class TaskStateMachineTest {

    /**
     * 《23》§8 正向链：已派发 → 去程中 → 末段交接中 → 返航中 → 已完成（写入方 M4）。
     */
    @Test
    void forwardChain_shouldBeAllowed() {
        assertTrue(TaskStateMachine.canTransit(TaskStatus.DISPATCHED, TaskStatus.OUTBOUND));
        assertTrue(TaskStateMachine.canTransit(TaskStatus.OUTBOUND, TaskStatus.HANDOVER));
        assertTrue(TaskStateMachine.canTransit(TaskStatus.HANDOVER, TaskStatus.RETURNING));
        assertTrue(TaskStateMachine.canTransit(TaskStatus.RETURNING, TaskStatus.FINISHED));
    }

    /**
     * 不得跳级：正向链只能逐段推进。
     */
    @Test
    void forwardChain_shouldNotAllowSkipping() {
        assertFalse(TaskStateMachine.canTransit(TaskStatus.DISPATCHED, TaskStatus.HANDOVER));
        assertFalse(TaskStateMachine.canTransit(TaskStatus.OUTBOUND, TaskStatus.RETURNING));
        assertFalse(TaskStateMachine.canTransit(TaskStatus.HANDOVER, TaskStatus.FINISHED));
    }

    /**
     * 《23》§4 异常分支：迫停中落他巢后转已完成；无可借机位转迫降待回收。
     */
    @Test
    void abnormalBranches_shouldFollow23Section4() {
        assertTrue(TaskStateMachine.canTransit(TaskStatus.RETURNING, TaskStatus.DIVERTED));
        assertTrue(TaskStateMachine.canTransit(TaskStatus.DIVERTED, TaskStatus.FINISHED),
                "迫停中落他巢后转已完成（《23》§4）");
        assertTrue(TaskStateMachine.canTransit(TaskStatus.RETURNING, TaskStatus.AWAITING_RECOVERY));
    }

    /**
     * 异常终止（无人机灭失）可从三个在飞状态转入。
     */
    @Test
    void aborted_shouldBeReachableFromInFlightStates() {
        assertTrue(TaskStateMachine.canTransit(TaskStatus.OUTBOUND, TaskStatus.ABORTED));
        assertTrue(TaskStateMachine.canTransit(TaskStatus.HANDOVER, TaskStatus.ABORTED));
        assertTrue(TaskStateMachine.canTransit(TaskStatus.RETURNING, TaskStatus.ABORTED));
        assertFalse(TaskStateMachine.canTransit(TaskStatus.DIVERTED, TaskStatus.ABORTED),
                "迫停中已落地，按保守口径不再转异常终止");
    }

    /**
     * 终态不再流转。
     */
    @Test
    void terminalStates_shouldHaveNoSuccessor() {
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.FINISHED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.ABORTED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.AWAITING_RECOVERY),
                "迫降待回收后转人工回收，不在机内流转");
        for (TaskStatus terminal : new TaskStatus[]{
                TaskStatus.FINISHED, TaskStatus.ABORTED, TaskStatus.AWAITING_RECOVERY}) {
            for (TaskStatus any : TaskStatus.values()) {
                assertFalse(TaskStateMachine.canTransit(terminal, any),
                        terminal.getValue() + " 不得再流转到 " + any.getValue());
            }
        }
    }

    /**
     * 《23》§4 约束 2：6b 意图变更不新增任务状态，状态仍停在去程中。
     * 故 6b 判据成立不会在状态机里产生任何转移——去程中不得因 6b 跳到其它状态。
     */
    @Test
    void intentChange_shouldNotCreateAnyTransition() {
        assertEquals(java.util.Set.of(TaskStatus.HANDOVER, TaskStatus.ABORTED),
                TaskStateMachine.nextStates(TaskStatus.OUTBOUND),
                "去程中的合法后继不含 6b 专属状态");
    }

    /**
     * 非法流转必须抛异常，避免脏状态落库。
     */
    @Test
    void requireTransit_shouldRejectIllegalTransition() {
        assertEquals(TaskStatus.OUTBOUND,
                TaskStateMachine.requireTransit(TaskStatus.DISPATCHED, TaskStatus.OUTBOUND));
        assertThrows(IllegalStateException.class,
                () -> TaskStateMachine.requireTransit(TaskStatus.FINISHED, TaskStatus.OUTBOUND));
    }
}
