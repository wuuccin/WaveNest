package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AdmissionPipeline} 单测（A 组 · {@code 80} §3.1.2 判定链八步）。
 *
 * <p>重点覆盖：每一步的命中出口与原因码、<b>熔断期间不拒单</b>（只延后）、
 * 「船在段内／段外／永远进不来」三种空间判定、{@code etaMin} 卡截止时刻、
 * 以及<b>「数据源没接通时判据被记入 skipped 而不是被静默放过」</b>这条最关键的性质。
 */
class AdmissionPipelineTest {

    private static final long NOW_MS = 1_700_000_000_000L;
    private static final double BASE_LAT = 28.60;
    private static final double LON = 115.85;
    private static final double MPD_LAT = ConstantVelocityPredictor.METERS_PER_DEG_LAT;

    private static final WaterwayGeometry NORTH = new WaterwayGeometry(List.of(
            new WaterwayGeometry.Vertex(LON, BASE_LAT),
            new WaterwayGeometry.Vertex(LON, BASE_LAT + 0.20)));

    private static final long DEADLINE_MS = NOW_MS + 3_600_000L;   // 1 h 后

    private static AdmissionPipeline.Policy policy() {
        return new AdmissionPipeline.Policy(
                Set.of("MEDICINE", "FOOD", "BATTERY"),
                25.0,                 // 载重上界 kg
                0.05,                 // 裕度 → 上限 23.75 kg
                120_000L,             // ttlAisMs
                20.0,                 // vMaxCruiseMs
                120.0,                // tHandoverMinSec
                120L,                 // wakeMarginSec
                60L,                  // suspendRetrySec
                1800L,                // horizonSec
                5.0,                  // weightTolKg
                600L);                // dupWindowSec
    }

    private static AdmissionPipeline.OrderRequest request() {
        return new AdmissionPipeline.OrderRequest(
                "ORD-1", "413800001", "MEDICINE", 10.0, DEADLINE_MS, "NORMAL", "G-1");
    }

    private static AdmissionPipeline.ShipObservation shipAt(double currentS, boolean inSegment) {
        return new AdmissionPipeline.ShipObservation(currentS, NOW_MS - 5_000L, "OK", inSegment);
    }

    /** 从 {@code s0} 起以 2 m/s 前进的 31 点预测（每 60 s 一点，覆盖 1800 s）。 */
    private static AdmissionPipeline.SnapshotView snapshotFrom(double s0) {
        List<EstimatedPoint> points = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            double s = s0 + 2.0 * i * 60.0;
            points.add(new EstimatedPoint(NOW_MS + i * 60_000L, LON,
                    BASE_LAT + s / MPD_LAT, 20.0, 15.0));
        }
        return new AdmissionPipeline.SnapshotView(NOW_MS, points);
    }

    private static List<AdmissionPipeline.NestPoint> nests() {
        return List.of(new AdmissionPipeline.NestPoint("N-1", LON, BASE_LAT + 0.02));
    }

    private static AdmissionPipeline.ServiceSegment segment() {
        return new AdmissionPipeline.ServiceSegment(NORTH, 2000.0, 8000.0);
    }

    /** 「全面接通」的输入：所有数据源都在，船在段内。 */
    private static AdmissionPipeline.Input completeInput() {
        return new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0),
                nests(), segment(), Boolean.TRUE, Boolean.FALSE, List.of(), Boolean.TRUE, policy());
    }

    @Test
    @DisplayName("八步全过 → 受理并触发调度")
    void allGatesPass_shouldAccept() {
        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(completeInput());

        assertEquals(AdmissionPipeline.Outcome.ACCEPT_TRIGGER_SCHEDULE, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.NONE, decision.reason());
        assertTrue(decision.allowsSchedule());
        assertFalse(decision.hasSkippedCriteria());
        assertTrue(decision.etaLowerBoundSec() > 0.0);
        assertEquals(NOW_MS, decision.enterTsMs(), "已在段内时驶入时刻即快照起点");
    }

    @Test
    @DisplayName("① 信息不完整 → 转人工 INCOMPLETE（不流转）")
    void incompleteRequest_shouldGoManual() {
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "413800001", "MEDICINE", 10.0, null,
                        "NORMAL", "G-1"),
                NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Outcome.MANUAL, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.INCOMPLETE, decision.reason());
        assertFalse(decision.allowsSchedule());
    }

    @Test
    @DisplayName("MMSI 非 9 位数字 → REJECT(MMSI_INVALID)")
    void invalidMmsi_shouldReject() {
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "4138A0001", "MEDICINE", 10.0,
                        DEADLINE_MS, "NORMAL", "G-1"),
                NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());

        assertEquals(AdmissionPipeline.Reason.MMSI_INVALID, AdmissionPipeline.admit(input).reason());
        assertTrue(AdmissionPipeline.isValidMmsi(" 413800001 "), "首尾空白应被容忍");
        assertFalse(AdmissionPipeline.isValidMmsi("41380001"));
        assertFalse(AdmissionPipeline.isValidMmsi(null));
    }

    @Test
    @DisplayName("② 重复单：同船同货类同重量且在窗内 → REJECT(DUPLICATE)；终止态不算")
    void duplicateOrder_shouldReject() {
        AdmissionPipeline.PriorOrder duplicate = new AdmissionPipeline.PriorOrder(
                "413800001", "MEDICINE", 12.0, NOW_MS - 60_000L, false);
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true),
                snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE, Boolean.FALSE,
                List.of(duplicate), Boolean.TRUE, policy());

        assertEquals(AdmissionPipeline.Reason.DUPLICATE, AdmissionPipeline.admit(input).reason());

        // 终止态不参与重复判定
        AdmissionPipeline.PriorOrder terminal = new AdmissionPipeline.PriorOrder(
                "413800001", "MEDICINE", 12.0, NOW_MS - 60_000L, true);
        AdmissionPipeline.Input accepted = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true),
                snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE, Boolean.FALSE,
                List.of(terminal), Boolean.TRUE, policy());
        assertTrue(AdmissionPipeline.admit(accepted).allowsSchedule());

        // 重量差超容差也不算重复
        AdmissionPipeline.PriorOrder heavy = new AdmissionPipeline.PriorOrder(
                "413800001", "MEDICINE", 30.0, NOW_MS - 60_000L, false);
        AdmissionPipeline.Input notDuplicate = new AdmissionPipeline.Input(request(), NOW_MS,
                shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE, Boolean.FALSE,
                List.of(heavy), Boolean.TRUE, policy());
        assertTrue(AdmissionPipeline.admit(notDuplicate).allowsSchedule());
    }

    @Test
    @DisplayName("③ 已过截止时刻 → REJECT(EXPIRED)")
    void expired_shouldReject() {
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "413800001", "MEDICINE", 10.0,
                        NOW_MS - 1L, "NORMAL", "G-1"),
                NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());

        assertEquals(AdmissionPipeline.Reason.EXPIRED, AdmissionPipeline.admit(input).reason());
    }

    @Test
    @DisplayName("③ 剩余时间小于 etaMin → REJECT(TIME_INFEASIBLE)，etaMin 一并回传")
    void timeInfeasible_shouldReject() {
        // 截止只剩 60 s，而 t_handover 就是 120 s，时间上必然不可行
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "413800001", "MEDICINE", 10.0,
                        NOW_MS + 60_000L, "NORMAL", "G-1"),
                NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Reason.TIME_INFEASIBLE, decision.reason());
        assertTrue(decision.etaLowerBoundSec() >= 120.0, "etaMin 至少含交接时长下界");
    }

    @Test
    @DisplayName("④ 货物类型不在白名单 → REJECT(CARGO_TYPE)；超载 → REJECT(OVERLOAD)")
    void cargoChecks_shouldReject() {
        AdmissionPipeline.Input wrongType = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "413800001", "BULK", 10.0, DEADLINE_MS,
                        "NORMAL", "G-1"),
                NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());
        assertEquals(AdmissionPipeline.Reason.CARGO_TYPE, AdmissionPipeline.admit(wrongType).reason());

        // 上界 = 25 × (1 − 0.05) = 23.75 kg
        AdmissionPipeline.Input overload = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "413800001", "MEDICINE", 24.0,
                        DEADLINE_MS, "NORMAL", "G-1"),
                NOW_MS, shipAt(5000.0, true), snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());
        assertEquals(AdmissionPipeline.Reason.OVERLOAD, AdmissionPipeline.admit(overload).reason());
    }

    @Test
    @DisplayName("⑤ 状态缺失或超 TTL → DEFER(DATA_STALE)，唤醒事件 DATA_RESTORED")
    void staleData_shouldDefer() {
        AdmissionPipeline.ShipObservation stale = new AdmissionPipeline.ShipObservation(
                5000.0, NOW_MS - 200_000L, "INSUFFICIENT", true);
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(request(), NOW_MS, stale,
                snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE, Boolean.FALSE, List.of(),
                Boolean.TRUE, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Outcome.DEFER, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.DATA_STALE, decision.reason());
        assertEquals(AdmissionPipeline.WakeEvent.DATA_RESTORED, decision.wakeEvent());
        assertEquals(NOW_MS + 60_000L, decision.wakeAtMs(), "按 suspendRetrySec 重判");

        // 链路断流同样按数据不足延后
        AdmissionPipeline.Input linkDown = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true),
                snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE, Boolean.FALSE, List.of(),
                Boolean.FALSE, policy());
        assertEquals(AdmissionPipeline.Reason.DATA_STALE, AdmissionPipeline.admit(linkDown).reason());
    }

    @Test
    @DisplayName("⑥ 船在段外但会驶入 → DEFER(SHIP_OUT_OF_SEGMENT)，唤醒时刻 = tEnter − wakeMargin")
    void shipWillEnter_shouldDeferUntilEnter() {
        // 起点 s=0，2 m/s → 2000 m 处需 1000 s；唤醒应在 1000 − 120 = 880 s 后
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(0.0, false),
                snapshotFrom(0.0), nests(), segment(), Boolean.TRUE, Boolean.FALSE, List.of(),
                Boolean.TRUE, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Outcome.DEFER, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.SHIP_OUT_OF_SEGMENT, decision.reason());
        assertEquals(AdmissionPipeline.WakeEvent.SHIP_ENTERED, decision.wakeEvent());
        assertEquals(NOW_MS + 1_000_000L, decision.enterTsMs(), "tEnter 应为 1000 s 处");
        assertEquals(NOW_MS + 880_000L, decision.wakeAtMs(), "唤醒提前 wakeMarginSec");
    }

    @Test
    @DisplayName("⑥ 预测时域内永不进段 → DEFER(SHIP_OUT_OF_SEGMENT) 周期重评（MEDIUM.27：不永久拒单）")
    void neverEntersSegment_shouldDeferWithRetry() {
        // 反向行驶：s 递减，永远进不了 [2000, 8000]
        List<EstimatedPoint> points = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            double s = 1000.0 - 2.0 * i * 60.0;                 // 会夹到 0
            points.add(new EstimatedPoint(NOW_MS + i * 60_000L, LON,
                    BASE_LAT + Math.max(0.0, s) / MPD_LAT, 20.0, 15.0));
        }
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(1000.0, false),
                new AdmissionPipeline.SnapshotView(NOW_MS, points), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());

        // MEDIUM.27：「时域内进不了段」≠「永远进不了」——延后重评而非 OUT_OF_SERVICE 永久拒单
        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Outcome.DEFER, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.SHIP_OUT_OF_SEGMENT, decision.reason());
        assertEquals(NOW_MS + 60_000L, decision.wakeAtMs(), "按 suspendRetrySec 周期重评");
    }

    @Test
    @DisplayName("⑥ 驶入时刻晚于「截止 − etaMin」→ REJECT(OUT_OF_SERVICE)")
    void enterTooLate_shouldReject() {
        // 截止只剩 10 min，而进段需 1000 s（16.7 min）
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(
                new AdmissionPipeline.OrderRequest("ORD-1", "413800001", "MEDICINE", 10.0,
                        NOW_MS + 600_000L, "NORMAL", "G-1"),
                NOW_MS, shipAt(0.0, false), snapshotFrom(0.0), nests(), segment(), Boolean.TRUE,
                Boolean.FALSE, List.of(), Boolean.TRUE, policy());

        assertEquals(AdmissionPipeline.Reason.OUT_OF_SERVICE, AdmissionPipeline.admit(input).reason());
    }

    @Test
    @DisplayName("⑦ 货未就绪 → DEFER(GOODS_NOT_READY)，等 GOODS_READY 事件")
    void goodsNotReady_shouldDefer() {
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true),
                snapshotFrom(5000.0), nests(), segment(), Boolean.FALSE, Boolean.FALSE, List.of(),
                Boolean.TRUE, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Outcome.DEFER, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.GOODS_NOT_READY, decision.reason());
        assertEquals(AdmissionPipeline.WakeEvent.GOODS_READY, decision.wakeEvent());
    }

    @Test
    @DisplayName("⑧ 闸门熔断 → DEFER(GATE_CUTOFF)，绝不拒单")
    void gateCutoff_shouldDeferNotReject() {
        AdmissionPipeline.Input input = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true),
                snapshotFrom(5000.0), nests(), segment(), Boolean.TRUE, Boolean.TRUE, List.of(),
                Boolean.TRUE, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(input);
        assertEquals(AdmissionPipeline.Outcome.DEFER, decision.outcome());
        assertEquals(AdmissionPipeline.Reason.GATE_CUTOFF, decision.reason());
        assertEquals(AdmissionPipeline.WakeEvent.GATE_RESUME, decision.wakeEvent());
        assertFalse(decision.allowsSchedule(), "熔断期间不得进入调度，但也不得拒单");
    }

    @Test
    @DisplayName("★ 数据源未接通：判据记入 skipped，不静默放过")
    void missingDataSources_shouldBeReportedAsSkipped() {
        // 只有订单与船状态，航道台账／机巢／货况／闸门全没有
        AdmissionPipeline.Input bare = new AdmissionPipeline.Input(request(), NOW_MS, shipAt(5000.0, true),
                null, List.of(), null, null, null, null, null, policy());

        AdmissionPipeline.Decision decision = AdmissionPipeline.admit(bare);
        assertTrue(decision.hasSkippedCriteria());
        assertTrue(decision.skippedCriteria()
                .contains(AdmissionPipeline.CRITERION_SPACE_GATE), "空间闸门应被记为未判定");
        assertTrue(decision.skippedCriteria()
                .contains(AdmissionPipeline.CRITERION_GOODS_READY), "货况应被记为未判定");
        assertTrue(decision.skippedCriteria()
                .contains(AdmissionPipeline.CRITERION_GATE_CUTOFF), "闸门应被记为未判定");
        assertTrue(decision.skippedCriteria()
                .contains(AdmissionPipeline.CRITERION_ETA_MIN), "etaMin 应被记为未判定");
        assertTrue(decision.skippedCriteria()
                .contains(AdmissionPipeline.CRITERION_DUPLICATE), "重复单应被记为未判定");
        // 放行但仍如实标注「没判定完整」
        assertTrue(decision.allowsSchedule());
        assertTrue(Double.isNaN(decision.etaLowerBoundSec()));
        assertNull(decision.enterTsMs());
    }

    @Test
    @DisplayName("参数与输入校验（含 HIGH.15：NaN 过所有比较运算，必须被有限性检查拦住）")
    void validation_shouldRejectBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionPipeline.ServiceSegment(NORTH, 8000.0, 2000.0));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionPipeline.Policy(Set.of("MEDICINE"), 0.0, 0.05, 120_000L, 20.0,
                        120.0, 120L, 60L, 1800L, 5.0, 600L));
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionPipeline.Policy(Set.of("MEDICINE"), 25.0, 1.0, 120_000L, 20.0,
                        120.0, 120L, 60L, 1800L, 5.0, 600L));
        // HIGH.15：NaN 会通过所有「< / > / >=」比较，载荷上界算出 NaN 后
        // 「载重 > NaN」恒 false → 超载单静默放行。有限性检查必须在这里兜住
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionPipeline.Policy(Set.of("MEDICINE"), 25.0, Double.NaN, 120_000L,
                        20.0, 120.0, 120L, 60L, 1800L, 5.0, 600L), "裕度 NaN");
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionPipeline.Policy(Set.of("MEDICINE"), Double.NaN, 0.05, 120_000L,
                        20.0, 120.0, 120L, 60L, 1800L, 5.0, 600L), "载重上界 NaN");
        assertThrows(IllegalArgumentException.class,
                () -> new AdmissionPipeline.Policy(Set.of("MEDICINE"), 25.0, 0.05, 120_000L, 20.0,
                        120.0, 120L, 60L, 1800L, Double.NaN, 600L), "重复单容差 NaN");
        // MEDIUM.29：MMSI 只认 ASCII 0-9，全角数字与其他文字系统数字不放行
        assertFalse(AdmissionPipeline.isValidMmsi("４１３８００００１"), "全角数字");
        assertFalse(AdmissionPipeline.isValidMmsi("4138๐๐๐๐๑"), "非 ASCII 数字字符");
    }
}
