package com.waterlogistics.algo.inflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.rest.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 功能10《84》第2/4/5/7章完成判据单测：
 * ① 三级各自命中；② 逐级降级顺序（第一级过就不出第二级）；③ 不可跳级；
 * ④ 他巢逐项打叉记录（D3 可审计）；⑤ 缺省输入降级处理；⑥ 阈值参数化（改注入值判定跟着变）；
 * ⑦ 两级可达性口径不混用；⑧ 风况只经返程基线进入判定（§7.7 硬约束 2）。
 */
class HomecomingReviewTest {

    private static final String HOME_NEST = "NEST-HOME";
    private static final double BASELINE = 40.0;
    private static final long SLOT_HOLD_MS = 2_000L;
    private static final long NO_BUFFER_MS = 0L;
    private static final GeoPoint POSITION = new GeoPoint(120.15, 30.28);

    private static HomecomingInput input(double batteryPercent) {
        return new HomecomingInput("EV-1", "ABORT", "TASK-1", "ORDER-1", "DRONE-1", HOME_NEST,
                POSITION, batteryPercent, BASELINE, true, null, new WindSnapshot(6.0, 90.0, 1L),
                "LOW_BATTERY", 1_700_000_000_000L, Boolean.TRUE);
    }

    /** 无风况快照的输入：此时固定裕度系数 k 才启用（§2.3 ③ 兜底档） */
    private static HomecomingInput inputWithoutWind(double batteryPercent) {
        return new HomecomingInput("EV-1", "ABORT", "TASK-1", "ORDER-1", "DRONE-1", HOME_NEST,
                POSITION, batteryPercent, BASELINE, true, null, null,
                "LOW_BATTERY", 1_700_000_000_000L, Boolean.TRUE);
    }

    /**
     * 基准参数组：触发裕度 20（{@code e10_reserve_ratio=0.20}×100）、他巢系数 0.8、
     * 缓冲 0（便于按半开区间直觉构造用例）、未接 A5 兜底、未接迟滞退出。
     */
    private static HomecomingParams params(Long arrivalEtaMs) {
        return new HomecomingParams(20.0, 20.0, null, 0.8, 1.2, NO_BUFFER_MS, arrivalEtaMs, SLOT_HOLD_MS);
    }

    private static NestCandidate nest(String nestId, int slots, List<TimeWindow> busy, double energy) {
        return new NestCandidate(nestId, slots, busy, energy);
    }

    private static NestCheckResult auditOf(HomecomingDecision decision, String nestId) {
        return decision.candidateAudit().stream()
                .filter(r -> r.nestId().equals(nestId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("审计表缺少候选巢 " + nestId));
    }

    /** 第一级：80% ≥ 40% ＋ 20% → 回原巢 */
    @Test
    void firstTier_shouldReturnHomeNest() {
        HomecomingDecision d = HomecomingReview.review(input(80.0), params(1_000L), List.of());

        assertEquals(HomecomingTier.RETURN_HOME_NEST, d.tier());
        assertEquals(HOME_NEST, d.targetId(), "第一级目标只能是原巢（返航目标唯一化）");
        assertFalse(d.needSlotRequest(), "占位请求只在第二级发出");
        assertTrue(d.candidateAudit().isEmpty(), "第一级不必检查他巢");
    }

    /** 降级顺序：第一级命中就早返回，不再出第二级候选审计 */
    @Test
    void firstTierHit_shouldNotReachSecondTier() {
        List<NestCandidate> usable = List.of(nest("NEST-B", 2, List.of(new TimeWindow(20_000L, 22_000L)), 20.0));

        HomecomingDecision d = HomecomingReview.review(input(80.0), params(1_000L), usable);

        assertEquals(HomecomingTier.RETURN_HOME_NEST, d.tier());
        assertTrue(d.candidateAudit().isEmpty(), "第一级已命中，他巢检查不应发生");
    }

    /** 第一级不过且无候选他巢 → 直落第三级（迫降是最后一级，不可跳级） */
    @Test
    void noCandidate_shouldFallToThirdTier() {
        HomecomingDecision d = HomecomingReview.review(input(30.0), params(1_000L), List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier());
        assertNull(d.targetId(), "迫降点 ID 归本组 L4 出，未接台账前不得臆造");
        assertNull(d.targetPosition());
        assertFalse(d.needSlotRequest());
        assertTrue(d.reason().contains("无候选他巢"));
    }

    /** 第二级：跳过不可达候选，取首个准入他巢，落巢即回收（不给卸货点） */
    @Test
    void secondTier_shouldStopAtOtherNestAndRequestSlot() {
        List<NestCandidate> candidates = List.of(
                nest("NEST-A", 2, List.of(), 45.0),
                nest("NEST-B", 2, List.of(new TimeWindow(20_000L, 22_000L)), 20.0));

        HomecomingDecision d = HomecomingReview.review(input(50.0), params(1_000L), candidates);

        assertEquals(HomecomingTier.FORCE_STOP_OTHER_NEST, d.tier());
        assertEquals("NEST-B", d.targetId(), "50%×0.8=40% ≥ 20%，NEST-B 准入");
        assertTrue(d.needSlotRequest(), "迫停他巢才发占位请求");
        assertFalse(d.reason().contains("卸货"), "货机同行、落巢即回收，不存在卸货点改投");
        assertTrue(auditOf(d, "NEST-B").admitted(), "NEST-B 三项判据全过");
        assertEquals(NestCheckResult.REJECT_UNREACHABLE, auditOf(d, "NEST-A").rejectReason(),
                "准入者之前的候选仍须逐项记录");
    }

    /** C3 可达性筛除：到他巢能耗 ≤ 可用电量×系数，超出即否 */
    @Test
    void unreachableNest_shouldBeRejectedByEnergy() {
        // 50%×0.8 = 40%，能耗 45% 超限
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(1_000L),
                List.of(nest("NEST-A", 2, List.of(), 45.0)));

        NestCheckResult r = auditOf(d, "NEST-A");
        assertFalse(r.reachabilityOk(), "能耗 45% > 可用电量 50%×0.8，可达性应否");
        assertEquals(NestCheckResult.REJECT_UNREACHABLE, r.rejectReason());
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier());
    }

    /** D2 容量约束：总机位已占满即否 */
    @Test
    void capacityFull_shouldRejectNest() {
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(1_000L),
                List.of(nest("NEST-A", 1, List.of(new TimeWindow(20_000L, 22_000L)), 20.0)));

        NestCheckResult r = auditOf(d, "NEST-A");
        assertTrue(r.reachabilityOk(), "能耗够，可达性不应误否");
        assertFalse(r.capacityOk(), "1 机位已被 1 个时窗占满，容量 D2 应否");
        assertEquals(NestCheckResult.REJECT_CAPACITY_FULL, r.rejectReason());
    }

    /** D5 时隙重叠：预计到达时刻落在占用窗内即否 */
    @Test
    void occupiedSlotAtArrival_shouldRejectNest() {
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(10_000L),
                List.of(nest("NEST-A", 2, List.of(new TimeWindow(9_000L, 11_000L)), 20.0)));

        NestCheckResult r = auditOf(d, "NEST-A");
        assertTrue(r.reachabilityOk());
        assertTrue(r.capacityOk());
        assertFalse(r.slotOk(), "[10000,12000) 与 [9000,11000) 重叠");
        assertEquals(NestCheckResult.REJECT_SLOT_OCCUPIED, r.rejectReason());
    }

    /** 半开区间口径（《29》§3.4.1）：端点相接不算冲突（缓冲为 0 时） */
    @Test
    void touchingSlotEndpoint_shouldNotClash() {
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(10_000L),
                List.of(nest("NEST-A", 2, List.of(new TimeWindow(8_000L, 10_000L)), 20.0)));

        assertTrue(auditOf(d, "NEST-A").slotOk(), "[10000,12000) 与 [8000,10000) 端点相接，不算重叠");
        assertEquals(HomecomingTier.FORCE_STOP_OTHER_NEST, d.tier());
    }

    /** e10_slot_buffer 两端缓冲：端点相接但在缓冲内仍算冲突 */
    @Test
    void slotBuffer_shouldAbsorbEdgeContact() {
        HomecomingParams buffered = new HomecomingParams(20.0, 20.0, null, 0.8, 1.2,
                HomecomingParams.SUGGESTED_SLOT_BUFFER_MS, 10_000L, SLOT_HOLD_MS);
        List<TimeWindow> touching = List.of(new TimeWindow(8_000L, 10_000L));

        assertTrue(auditOf(HomecomingReview.review(input(50.0), params(10_000L),
                List.of(nest("NEST-A", 2, touching, 20.0))), "NEST-A").slotOk(),
                "缓冲为 0 时端点相接不冲突");

        assertFalse(auditOf(HomecomingReview.review(input(50.0), buffered,
                List.of(nest("NEST-A", 2, touching, 20.0))), "NEST-A").slotOk(),
                "缓冲 58s 后到达窗扩为 [-48000,70000)，与 [8000,10000) 重叠，应判否");
    }

    /** 缺预计到达时刻 → 时隙判据不可算，不得谎报为冲突 */
    @Test
    void slotUncomputable_shouldNotBeReportedAsConflict() {
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(null),
                List.of(nest("NEST-A", 2, List.of(), 20.0)));

        NestCheckResult r = auditOf(d, "NEST-A");
        assertFalse(r.slotOk());
        assertEquals(NestCheckResult.REJECT_SLOT_UNCOMPUTABLE, r.rejectReason());
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier());
    }

    /** D3 完备性：遍历完所有候选才输出「无位可借」，三类打叉齐备 */
    @Test
    void allCandidatesAudited_beforeDeclaringNoneAvailable() {
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(10_000L), List.of(
                nest("NEST-UNREACH", 2, List.of(), 45.0),
                nest("NEST-FULL", 1, List.of(new TimeWindow(1L, 2L)), 20.0),
                nest("NEST-BUSY", 2, List.of(new TimeWindow(9_000L, 11_000L)), 20.0)));

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier());
        assertEquals(3, d.candidateAudit().size(), "D3 要求所有候选都被检查，缺一即不可输出无位可借");
        assertTrue(d.candidateAudit().stream().noneMatch(NestCheckResult::admitted));
        assertEquals(NestCheckResult.REJECT_UNREACHABLE, auditOf(d, "NEST-UNREACH").rejectReason());
        assertEquals(NestCheckResult.REJECT_CAPACITY_FULL, auditOf(d, "NEST-FULL").rejectReason());
        assertEquals(NestCheckResult.REJECT_SLOT_OCCUPIED, auditOf(d, "NEST-BUSY").rejectReason());
        assertTrue(d.reason().contains("3 个候选全部不可用"));
    }

    /** 阈值参数化：同一输入改注入裕度，判定结果跟着变 */
    @Test
    void verdict_shouldFollowInjectedMargin() {
        HomecomingInput in = input(50.0);
        HomecomingParams loose = new HomecomingParams(5.0, 5.0, null, 0.8, 1.2, NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);
        HomecomingParams tight = new HomecomingParams(20.0, 20.0, null, 0.8, 1.2, NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);

        assertEquals(HomecomingTier.RETURN_HOME_NEST,
                HomecomingReview.review(in, loose, List.of()).tier(), "裕度 5%：50% ≥ 45%，应回原巢");
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD,
                HomecomingReview.review(in, tight, List.of()).tier(), "裕度 20%：50% < 60%，不得回原巢");
    }

    /** 第二级系数参数化：改 e10_nest_avail_factor 判定跟着变 */
    @Test
    void secondTier_shouldFollowInjectedAvailFactor() {
        HomecomingInput in = input(50.0);
        HomecomingParams loose = new HomecomingParams(20.0, 20.0, null, 0.98, 1.2, NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);
        HomecomingParams tight = new HomecomingParams(20.0, 20.0, null, 0.5, 1.2, NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);

        assertEquals(HomecomingTier.FORCE_STOP_OTHER_NEST,
                HomecomingReview.review(in, loose, List.of(nest("NEST-A", 2, List.of(), 45.0))).tier(),
                "系数 0.98 → 上限 49% ≥ 45%，应准入");
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD,
                HomecomingReview.review(in, tight, List.of(nest("NEST-A", 2, List.of(), 45.0))).tier(),
                "系数 0.5 → 上限 25% < 45%，不得准入");
    }

    /**
     * 两级可达性口径不混用：第二级按比例系数（能耗 ≤ 电量×0.8），
     * 不沿用第一级的「基线＋绝对裕度」——否则能耗 35%、电量 50% 时会被误判。
     */
    @Test
    void reachability_calipers_shouldNotBeSharedAcrossTiers() {
        // 35% ≤ 50%×0.8=40% 准入；但 35%+20%=55% > 50% 按第一级口径会否
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(1_000L),
                List.of(nest("NEST-A", 2, List.of(), 35.0)));

        assertTrue(auditOf(d, "NEST-A").reachabilityOk(), "第二级按系数口径，35% 应准入");
        assertEquals(HomecomingTier.FORCE_STOP_OTHER_NEST, d.tier());
    }

    /** §2.2 A5 低电量绝对阈值兜底：与 2.1 取更保守者，必须并存 */
    @Test
    void lowBatteryFallback_shouldOverrideBaselineVerdict() {
        HomecomingParams withFloor = new HomecomingParams(20.0, 20.0, 45.0, 0.8, 1.2,
                NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);

        // 70% ≥ 40%+20%，2.1 判可行；且 70% > 45% 兜底线，2.2 同判可行
        assertEquals(HomecomingTier.RETURN_HOME_NEST,
                HomecomingReview.review(input(70.0), withFloor, List.of()).tier(),
                "电量高于兜底线，2.1 与 2.2 同判可行");
        assertTrue(HomecomingReview.review(input(70.0), withFloor, List.of()).reason()
                .contains("低电量兜底线"));

        // 抬兜底线到 80%：2.1 仍判可行，但 2.2 判否 → 第一级不可行，降第三级
        HomecomingParams highFloor = new HomecomingParams(20.0, 20.0, 80.0, 0.8, 1.2,
                NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);
        HomecomingDecision d = HomecomingReview.review(input(70.0), highFloor, List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier(),
                "2.2 兜底必须与 2.1 并存，取更保守者");
        assertTrue(d.reason().contains("A5 低电量绝对阈值"), "reason 须点明是兜底判否：" + d.reason());

        assertEquals(HomecomingTier.RETURN_HOME_NEST,
                HomecomingReview.review(input(70.0), params(1_000L), List.of()).tier(),
                "未注入兜底阈值时不启用 2.2，属降级运行");
    }

    /** §7.7 硬约束 2：风况只经返程基线进入判定，有快照时不得再乘 k */
    @Test
    void windFactor_shouldOnlyApplyWhenSnapshotMissing() {
        HomecomingParams k12 = new HomecomingParams(20.0, 20.0, null, 0.8, 1.2, NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);

        // 有风况快照：基线已含风况，k 不得再放大 —— 65% ≥ 40%+20%=60%，应回原巢
        assertEquals(HomecomingTier.RETURN_HOME_NEST,
                HomecomingReview.review(input(65.0), k12, List.of()).tier(),
                "有快照时不得乘 k，否则重复计数、判定过保守");

        // 无风况快照：走 §2.3 ③ 固定系数 k 兜底档，40%×1.2=48%，+20%=68% > 65%
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD,
                HomecomingReview.review(inputWithoutWind(65.0), k12, List.of()).tier(),
                "无快照时按固定系数 k 放大基线");
    }

    /** 迟滞只作用第一级能量边界：注入退出裕度后按退出裕度判 */
    @Test
    void hysteresis_shouldLatchFirstTier() {
        HomecomingParams latched = new HomecomingParams(20.0, 10.0, null, 0.8, 1.2,
                NO_BUFFER_MS, 1_000L, SLOT_HOLD_MS);
        HomecomingInput in = input(55.0);

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD,
                HomecomingReview.review(in, latched, List.of()).tier(),
                "触发裕度 20%：55% < 60%");
        assertEquals(HomecomingTier.RETURN_HOME_NEST,
                HomecomingReview.review(in, latched, List.of(), HomecomingTier.RETURN_HOME_NEST).tier(),
                "退出裕度 10%：55% ≥ 50%，迟滞防乒乓");

        HomecomingDecision secondTier = HomecomingReview.review(input(48.0), latched,
                List.of(nest("NEST-A", 2, List.of(), 36.0)), HomecomingTier.RETURN_HOME_NEST);
        assertTrue(auditOf(secondTier, "NEST-A").reachabilityOk(),
                "迟滞不得外溢到第二级：48%×0.8=38% ≥ 36%，第二级恒按系数判，不按 release 裕度判");
    }

    /** 原巢不是「他巢」，第二级须从候选里剔除 */
    @Test
    void homeNest_shouldBeSkippedInCandidates() {
        HomecomingDecision d = HomecomingReview.review(input(50.0), params(1_000L),
                List.of(nest(HOME_NEST, 4, List.of(), 10.0)));

        assertTrue(d.candidateAudit().isEmpty(), "原巢不得出现在他巢审计表里");
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier());
    }

    /** 通信无覆盖只作风险标记，不作否决（《84》§7.4） */
    @Test
    void commUncovered_shouldBeRiskFlagNotVeto() {
        HomecomingInput uncovered = new HomecomingInput("EV-1", "ABORT", "TASK-1", "ORDER-1", "DRONE-1",
                HOME_NEST, POSITION, 30.0, BASELINE, true, null, new WindSnapshot(8.0, 45.0, 1L),
                "LOW_BATTERY", 1_700_000_000_000L, Boolean.FALSE);

        HomecomingDecision d = HomecomingReview.review(uncovered, params(1_000L), List.of());

        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, d.tier(), "无通信覆盖仍可迫降野外");
        assertTrue(d.riskFlags().contains(HomecomingDecision.RISK_COMM_UNCOVERED));

        HomecomingInput plenty = new HomecomingInput("EV-2", "ABORT", "TASK-1", "ORDER-1", "DRONE-1",
                HOME_NEST, POSITION, 80.0, BASELINE, true, null, new WindSnapshot(8.0, 45.0, 1L),
                "LOW_BATTERY", 1_700_000_000_000L, Boolean.FALSE);
        HomecomingDecision home = HomecomingReview.review(plenty, params(1_000L), List.of());
        assertEquals(HomecomingTier.RETURN_HOME_NEST, home.tier(), "通信无覆盖不得否决回原巢");
        assertTrue(home.riskFlags().contains(HomecomingDecision.RISK_COMM_UNCOVERED));
    }

    /** 缺省输入降级：无目标点用当前位置、commCovered 未知不加标记 */
    @Test
    void missingOptionalInputs_shouldUseDegradedCaliper() {
        HomecomingInput in = new HomecomingInput("EV-3", "ABORT", "TASK-1", "ORDER-1", "DRONE-1",
                HOME_NEST, POSITION, 80.0, BASELINE, true, null, null, "LOW_BATTERY", 1_700_000_000_000L,
                null);

        assertNull(in.currentTargetPoint(), "无目标点");
        assertNull(in.commCovered(), "通信覆盖未知");
        assertEquals(POSITION, in.returnStartPoint(), "§7.3：无目标点则用当前位置作返程起点");

        HomecomingDecision d = HomecomingReview.review(in, params(1_000L), List.of());
        assertEquals(HomecomingTier.RETURN_HOME_NEST, d.tier());
        assertTrue(d.riskFlags().isEmpty(), "通信覆盖未知时不得凭空打风险标记");
    }

    /** 构造器自守：占位请求只在第二级 */
    @Test
    void decision_shouldRejectSlotRequestOutsideSecondTier() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new HomecomingDecision(HomecomingTier.RETURN_HOME_NEST, HOME_NEST, null, true,
                        List.of(), List.of(), "越界占位请求"));
        assertTrue(thrown.getMessage().contains("占位请求只在第二级"),
                "异常信息须点名占位请求口径：" + thrown.getMessage());

        IllegalArgumentException noTarget = assertThrows(IllegalArgumentException.class,
                () -> new HomecomingDecision(HomecomingTier.FORCE_STOP_OTHER_NEST, null, null, true,
                        List.of(), List.of(), "缺机位标识"));
        assertTrue(noTarget.getMessage().contains("targetId"),
                "异常信息须点名缺失字段 targetId：" + noTarget.getMessage());
    }

    /** 阈值参数自守：解除裕度不得高于触发裕度；系数不得越界 */
    @Test
    void params_shouldRejectIllegalCombinations() {
        IllegalArgumentException margin = assertThrows(IllegalArgumentException.class,
                () -> new HomecomingParams(10.0, 20.0, null, 0.8, 1.2, 0L, 0L, SLOT_HOLD_MS));
        assertTrue(margin.getMessage().contains("解除裕度"), "异常信息须点名解除裕度：" + margin.getMessage());

        IllegalArgumentException factor = assertThrows(IllegalArgumentException.class,
                () -> new HomecomingParams(10.0, 10.0, null, 1.5, 1.2, 0L, 0L, SLOT_HOLD_MS));
        assertTrue(factor.getMessage().contains("他巢可达性系数"),
                "异常信息须点名他巢可达性系数：" + factor.getMessage());
    }

    /** 缺必填字段时报错点名字段，不静默放行 */
    @Test
    void input_shouldRejectMissingRequiredField() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new HomecomingInput("EV-1", "ABORT", "TASK-1", "ORDER-1", "DRONE-1", "  ",
                        POSITION, 80.0, BASELINE, true, null, null, "LOW_BATTERY", 1L, Boolean.TRUE));
        assertTrue(thrown.getMessage().contains("homeNestId"),
                "异常信息须点名缺失字段 homeNestId：" + thrown.getMessage());
    }

    /** 枚举体例：中文状态词往返，未知值拒绝 */
    @Test
    void tierValue_shouldRoundTrip() {
        assertEquals(HomecomingTier.EMERGENCY_LAND_FIELD, HomecomingTier.fromValue("迫降野外"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> HomecomingTier.fromValue("随便写的"));
        assertTrue(thrown.getMessage().contains("未知"), "异常信息须点明是未知取值：" + thrown.getMessage());
    }

    /** 时窗自守：必须是非空半开区间 */
    @Test
    void timeWindow_shouldRejectEmptyInterval() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new TimeWindow(100L, 100L));
        assertTrue(thrown.getMessage().contains("半开区间"), "异常信息须点名区间口径：" + thrown.getMessage());
    }
}