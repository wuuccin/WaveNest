package com.waterlogistics.rendezvous.handover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waterlogistics.algo.meeting.model.B_HandoverConfirmation;
import com.waterlogistics.algo.meeting.model.HandoverResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 末段会面与交接判定单测——四子阶段判据推进（接近→匹配→交接）、稳定持续时间、
 * 交接风阈值与失败上报（《81-B组算法方案》§3.6、《43-编码分工说明》§8 ③）。
 *
 * <p>几何夹具：船静止于 (LON0, LAT0)，机沿同纬线接近——相对距离＝东西偏移（米），
 * 相对速度＝相邻样本偏移差（m/s），全部可手算。
 */
class HandoverJudgeTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final double LAT0 = 30.0;
    private static final double LON0 = 121.0;
    private static final double MPL = Math.PI * 6_371_000.0 / 180.0 * Math.cos(Math.toRadians(LAT0));
    private static final double PLANNED_MEET_LON = LON0 + 100 / MPL;
    private static final double PLANNED_MEET_LAT = LAT0;

    /** 收敛 300 m、位置阈 50 m、速度阈 2 m/s、稳定 10 s、风阈 10 m/s、能耗率 0.01%/s */
    private static final HandoverParams PARAMS = new HandoverParams(300, 50, 2, 10, 10, 0.01);

    /** 标准接近序列：500 m 处进入；稳定满 10 s 后交接；投放反馈＋风况达标 → 成功。 */
    @Test
    void judge_shouldCompleteHandoverAfterStableHover() {
        List<HandoverSample> samples = new ArrayList<>();
        samples.add(s(0, 500, 5, false));
        samples.add(s(1, 400, 5, false));
        samples.add(s(2, 280, 5, false));    // 进邻域 → 匹配，实际会面点＝此位
        samples.add(s(3, 45, 5, false));     // 位置达标但速度 235 超限 → 重新计时
        samples.add(s(4, 30, 5, false));     // 速度 15 仍超限 → 重新计时
        samples.add(s(5, 29, 5, false));     // 速度 1 → 稳定计时起
        samples.add(s(6, 28, 5, false));
        samples.add(s(7, 29, 5, false));
        samples.add(s(8, 28, 5, false));
        samples.add(s(9, 29, 5, false));
        samples.add(s(10, 28, 5, false));
        samples.add(s(11, 29, 5, false));
        samples.add(s(12, 28, 5, false));
        samples.add(s(13, 29, 5, false));
        samples.add(s(14, 28, 5, false));
        samples.add(s(15, 29, 5, false));    // 稳定满 10 s → 交接
        samples.add(s(16, 29, 5, true));     // 投放反馈出现且风况达标 → 成功
        samples.add(s(17, 29, 5, false));    // 脱离段样本（判定已结束）

        B_HandoverConfirmation r = HandoverJudge.judge(cmd(samples));

        assertEquals(HandoverResult.SUCCESS, r.handoverResult(), "稳定达标后投放完成应成功");
        assertEquals(T0 + 16_000L, r.handoverDoneT(), "交接完成时刻＝投放反馈样本时刻");
        assertEquals(15, r.escortTimeCost(), "伴飞＝首样本→交接开始（15 s）");
        assertEquals(1, r.handoverTimeCost(), "交接＝交接开始→投放完成（1 s）");
        assertEquals(0.16, r.energyCost(), 1e-9, "能耗＝(15＋1)×0.01%");
        assertEquals(LON0 + 280 / MPL, r.actualMeetLon(), 1e-9, "实际会面点＝进邻域时的机位");
        assertEquals(LAT0, r.actualMeetLat(), 1e-9);
        assertEquals("task-1", r.taskId());
        assertEquals("UAV-01", r.droneId());
        assertEquals(PLANNED_MEET_LON, r.plannedMeetLon(), 1e-12, "计划基准原样保留（G 组 d_space 用）");
        assertEquals(T0, r.plannedMeetT());
    }

    /** 交接风阈值：投放反馈出现时风况超限 → 失败上报（完成时刻与开销为 null）。 */
    @Test
    void judge_shouldFail_whenWindExceedsLimitAtDrop() {
        List<HandoverSample> samples = successSequenceUpTo(15);
        samples.add(s(16, 29, 15, true));   // 风速 15 ＞ 风阈 10

        B_HandoverConfirmation r = HandoverJudge.judge(cmd(samples));

        assertEquals(HandoverResult.FAILED, r.handoverResult(), "风况超限不得判成功");
        assertNull(r.handoverDoneT(), "失败无完成时刻");
        assertNull(r.escortTimeCost(), "失败时开销为 null");
        assertNull(r.energyCost(), "失败时开销为 null");
        assertEquals(LON0 + 280 / MPL, r.actualMeetLon(), 1e-9, "已完成接近：实际会面点仍给（追溯）");
    }

    /** 悬停等待超限（简化＝样本耗尽仍无投放反馈）→ 失败上报。 */
    @Test
    void judge_shouldFail_whenDropNeverConfirmed() {
        List<HandoverSample> samples = successSequenceUpTo(15);
        for (int sec = 16; sec <= 25; sec++) {
            samples.add(s(sec, 29, 5, false));   // 一直悬停，投放反馈始终未来
        }

        B_HandoverConfirmation r = HandoverJudge.judge(cmd(samples));

        assertEquals(HandoverResult.FAILED, r.handoverResult(), "样本耗尽即放弃（重试/悬停上限简化）");
        assertNull(r.handoverDoneT());
        assertNull(r.handoverTimeCost());
    }

    /** 稳定持续时间清零：悬停抖动始终超速 → 停留匹配段 → 失败。 */
    @Test
    void judge_shouldFail_whenHoverNeverStabilizes() {
        List<HandoverSample> samples = new ArrayList<>();
        samples.add(s(0, 500, 5, false));
        samples.add(s(1, 400, 5, false));
        samples.add(s(2, 280, 5, false));   // 进匹配
        samples.add(s(3, 230, 5, false));   // 速度 50 ✗
        samples.add(s(4, 180, 5, false));
        samples.add(s(5, 130, 5, false));
        samples.add(s(6, 80, 5, false));
        samples.add(s(7, 30, 5, false));
        samples.add(s(8, 30, 5, true));     // 尚未稳定满时长，投放标记不生效（仍在匹配）

        B_HandoverConfirmation r = HandoverJudge.judge(cmd(samples));

        assertEquals(HandoverResult.FAILED, r.handoverResult(), "稳定时长未达标不得交接");
        assertNull(r.handoverDoneT());
        assertEquals(LON0 + 280 / MPL, r.actualMeetLon(), 1e-9);
    }

    /** 始终未进邻域 → 失败，且实际会面点为空。 */
    @Test
    void judge_shouldFail_whenNeverEnteredNeighborhood() {
        List<HandoverSample> samples = new ArrayList<>();
        samples.add(s(0, 500, 5, false));
        samples.add(s(1, 480, 5, false));
        samples.add(s(2, 460, 5, false));

        B_HandoverConfirmation r = HandoverJudge.judge(cmd(samples));

        assertEquals(HandoverResult.FAILED, r.handoverResult());
        assertNull(r.actualMeetLon(), "未完成接近：无实际会面点");
        assertNull(r.actualMeetLat());
    }

    /** 入参校验：空样本、时间非升序一律拒绝。 */
    @Test
    void judge_shouldRejectInvalidSamples() {
        assertThrows(IllegalArgumentException.class,
                () -> HandoverJudge.judge(cmd(List.of())), "空样本应拒绝");

        List<HandoverSample> reversed = new ArrayList<>();
        reversed.add(s(1, 500, 5, false));
        reversed.add(s(0, 400, 5, false));
        assertThrows(IllegalArgumentException.class,
                () -> HandoverJudge.judge(cmd(reversed)), "时间非升序应拒绝");
    }

    /** 枚举中文口径（《81》§2.3(6)、§3.6）。 */
    @Test
    void enums_shouldFollowDocValues() {
        assertEquals("成功", HandoverResult.SUCCESS.getValue());
        assertEquals("失败", HandoverResult.FAILED.getValue());
        assertEquals(HandoverResult.FAILED, HandoverResult.fromValue("失败"));
        assertEquals("接近", HandoverPhase.APPROACH.getValue());
        assertEquals("匹配", HandoverPhase.MATCH.getValue());
        assertEquals("交接", HandoverPhase.TRANSFER.getValue());
        assertEquals("脱离", HandoverPhase.DEPART.getValue());
    }

    // ===== 夹具 =====

    /** 成功序列的前段：0..lastSec 秒接近＋稳定（不含投放样本） */
    private static List<HandoverSample> successSequenceUpTo(int lastSec) {
        List<HandoverSample> samples = new ArrayList<>();
        samples.add(s(0, 500, 5, false));
        samples.add(s(1, 400, 5, false));
        samples.add(s(2, 280, 5, false));
        samples.add(s(3, 45, 5, false));
        samples.add(s(4, 30, 5, false));
        for (int sec = 5; sec <= lastSec; sec++) {
            samples.add(s(sec, sec % 2 == 0 ? 28 : 29, 5, false));
        }
        return samples;
    }

    private static HandoverCommand cmd(List<HandoverSample> samples) {
        return new HandoverCommand("task-1", "UAV-01",
                PLANNED_MEET_LON, PLANNED_MEET_LAT, T0, samples, PARAMS);
    }

    /** 第 second 秒样本：机东西偏移 eastM、风况与投放标记给定；船静止 */
    private static HandoverSample s(int second, double eastM, double windMs, boolean dropDone) {
        return new HandoverSample(T0 + second * 1000L,
                LON0 + eastM / MPL, LAT0, LON0, LAT0, windMs, dropDone);
    }
}
