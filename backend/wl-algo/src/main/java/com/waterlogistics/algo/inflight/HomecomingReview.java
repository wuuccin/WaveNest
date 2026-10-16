package com.waterlogistics.algo.inflight;

import java.util.ArrayList;
import java.util.List;

/**
 * 功能10「返航复核与归巢资源判定」三级降级判定主体，依据《84-E组算法方案》第2/4/5/7章。
 *
 * <p><b>降级顺序固化在代码里</b>：《84》第5章易混点逐字「回原巢 → 迫停他巢 → 迫降野外。
 * <b>按序降级，迫降是最后一级，不可跳级</b>」。故本类用顺序求值 ＋ 早返回实现，
 * <b>不做成可配置策略链</b>——可配置链等于留了「今天直接跳第三级」的口子。
 *
 * <p><b>本类只判落点，不做以下四件事</b>（《84》§4.3／§7.5 边界）：
 * ① 不做航迹生成与返程电量精算（归 B 组）；② 不做占位落子（归 D 组，只发请求）；
 * ③ <b>不做就近择优改投</b>（返航目标唯一化＝原巢，第二级只判准入不判择优）；④ 不做飞控执行（归模块四）。
 *
 * <p><b>两级可达性口径不同，不可合并</b>（《84》§4.3 参数表用两个不同参数键）：
 * <ul>
 *   <li><b>第一级</b>（§2.1 A1 储备能量政策）：{@code 实时电量 ≥ 返程基线 ＋ 储备裕度}，
 *       并受 §2.2 A5 低电量绝对阈值兜底（<b>与 2.1 取更保守者，必须并存</b>）；</li>
 *   <li><b>第二级</b>（§6 表「可达性筛除 C3」＋ §4.3 {@code e10_nest_avail_factor}）：
 *       {@code 到该巢返程能耗 ≤ 可用电量 × 系数}——是<b>按比例留余量的筛除</b>，不是绝对裕度。</li>
 * </ul>
 * 两者虽然都是「二值筛除」，但不等式形式与参数来源都不同，<b>抽成一个方法必会漂移</b>。
 *
 * <p><b>风况只出现一次</b>（《84》§7.7 硬约束 2「风况只经返程基线进入判定，不得单独成维或单独调阈值」）：
 * 返程基线本身是风况相关的精算结果，<b>故固定裕度系数 k 仅在无风况快照时启用</b>（§2.3 ③ 最低档兜底）；
 * 有风况快照时基线已含风况，再乘 k＝重复计数、判定过保守（§3.5 明文警告）。
 *
 * <p><b>零 Spring／零 DB</b>：阈值、候选他巢台账、风况全部由调用方注入。
 */
public final class HomecomingReview {

    private HomecomingReview() {
        // 静态判定器，禁止实例化
    }

    /** 无历史（首次判定）入口 */
    public static HomecomingDecision review(HomecomingInput input, HomecomingParams params,
            List<NestCandidate> candidates) {
        return review(input, params, candidates, null);
    }

    /**
     * 三级降级判定主入口。
     *
     * @param input 功能10 入口输入快照
     * @param params 阈值与判据参数（须由调用方从 {@code sys_param} 读出后注入）
     * @param candidates 候选他巢台账（D 组 {@code occupancy_slot} 读出）；可为 null 视作无候选
     * @param previousTier 上一轮落点结论，用于第一级能量边界的双阈值迟滞；
     *                    为 null 表示无历史，按触发裕度判
     * @return 三级落点结论（含候选他巢逐项打叉审计记录）
     */
    public static HomecomingDecision review(HomecomingInput input, HomecomingParams params,
            List<NestCandidate> candidates, HomecomingTier previousTier) {
        if (input == null || params == null) {
            throw new IllegalArgumentException("input 与 params 必填：判定不允许缺输入或缺阈值");
        }
        List<String> riskFlags = new ArrayList<>();
        if (Boolean.FALSE.equals(input.commCovered())) {
            // 《84》§7.4：通信条件作风险标记，不作否决——无覆盖区仍可迫降
            riskFlags.add(HomecomingDecision.RISK_COMM_UNCOVERED);
        }

        // ── 第一级：回原巢（归巢目标唯一化，只有这一级回原巢） ──
        double margin = previousTier == HomecomingTier.RETURN_HOME_NEST
                ? params.batteryReleaseMarginPct()
                : params.batterySafetyMarginPct();
        double homeRequired = homewardEnergyRequired(input, params);
        boolean firstTierOk = firstTierFeasibleByReserve(input.batteryPercent(), homeRequired, margin);
        // 《84》§2.2 A5 兜底：低电量绝对阈值与 2.1 取更保守者，两者必须并存
        boolean firstTierOkAll = firstTierOk && aboveAbsoluteFloor(input.batteryPercent(), params);
        if (firstTierOkAll) {
            return new HomecomingDecision(HomecomingTier.RETURN_HOME_NEST, input.homeNestId(), null, false,
                    List.of(), riskFlags,
                    "第一级命中：回原巢（电量 " + input.batteryPercent() + "% ≥ 返程基线需求 "
                            + round2(homeRequired) + "% ＋ 裕度 " + round2(margin) + "%"
                            + floorNote(params) + "）");
        }

        // ── 第二级：迫停他巢——逐个候选走 可达性 C3 → 容量 D2 → 时隙 D1/D5 ──
        List<NestCheckResult> audit = new ArrayList<>();
        NestCheckResult admitted = null;
        if (candidates != null) {
            for (NestCandidate c : candidates) {
                if (c == null || c.nestId().equals(input.homeNestId())) {
                    continue;
                }
                NestCheckResult r = checkNest(input, params, c);
                audit.add(r);
                if (admitted == null && r.admitted()) {
                    admitted = r;
                }
            }
        }

        if (admitted != null) {
            return new HomecomingDecision(HomecomingTier.FORCE_STOP_OTHER_NEST, admitted.nestId(), null, true,
                    audit, riskFlags,
                    "第二级命中：迫停他巢 " + admitted.nestId() + "（4.5 D3 已逐项检查 " + audit.size() + " 个候选）");
        }

        // ── 第三级：迫降野外（最后一级） ──
        String auditNote = audit.isEmpty() ? "无候选他巢" : audit.size() + " 个候选全部不可用";
        String why = firstTierOk
                ? "第一级电量高于 A5 低电量绝对阈值兜底线"
                : "第一级不可回（电量 " + input.batteryPercent() + "% 不足基线 " + round2(homeRequired)
                        + "% ＋ 裕度 " + round2(margin) + "%）";
        // §7.6 第 7 步：迫降点 ID 由本组 L4 迫降点层（台账候选 → 否决筛除 → 可达性）出。
        // 本轮未接 L4 台账，故 targetId/targetPosition 留空，不臆造迫降点。
        return new HomecomingDecision(HomecomingTier.EMERGENCY_LAND_FIELD, null, null, false, audit, riskFlags,
                "第三级命中：迫降野外（" + why + "，" + auditNote + "；迫降为最后一级。"
                        + "迫降点 ID 待 L4 迫降点层按 §7.6 第 7 步出）");
    }

    /**
     * 返程基线需求：<b>仅在无风况快照时才乘固定裕度系数 k</b>。
     *
     * <p>依据《84》§7.7 硬约束 2「风况只经返程基线进入判定，不得单独成维或单独调阈值」：
     * 有风况快照时基线已是风况相关精算结果（§2.1 不等式里的 {@code E_return}），
     * 再乘 k 是重复计数；无风况快照时上游既不给分布也不给范围，按 §2.3 ③ 固定系数 k 兜底放大基线。
     */
    private static double homewardEnergyRequired(HomecomingInput input, HomecomingParams params) {
        double k = input.windSnapshot() == null ? params.windMarginFactor() : 1.0;
        return input.homewardEnergyBaselinePercent() * k;
    }

    /**
     * 第一级 A1 判据（§2.1）：{@code 实时电量 ≥ 返程基线需求 ＋ 储备裕度}。
     */
    private static boolean firstTierFeasibleByReserve(double batteryPercent, double requiredPercent,
            double marginPct) {
        return batteryPercent >= requiredPercent + marginPct;
    }

    /**
     * 第一级 A5 兜底（§2.2）：实时电量低于绝对安全阈值时<b>无论基线算什么都直接判第一级不可行</b>。
     *
     * <p>阈值未注入（{@code lowBatteryFallbackEnabled()} 为 false）时本判据不生效——
     * 此时属降级运行，须在 reason 里说明，不得假装兜底已启用。
     */
    private static boolean aboveAbsoluteFloor(double batteryPercent, HomecomingParams params) {
        return !params.lowBatteryFallbackEnabled() || batteryPercent >= params.batteryAbsoluteFloorPct();
    }

    /**
     * 单一候选他巢的逐项检查：
     * C3 可达性筛除 → D2 容量约束 → D1 时隙可行性／D5 重叠判定（两端带缓冲）。
     */
    private static NestCheckResult checkNest(HomecomingInput input, HomecomingParams params, NestCandidate c) {
        boolean reachOk = reachableForOtherNest(input.batteryPercent(), c.returnEnergyPercent(),
                params.nestAvailFactor());
        boolean capacityOk = c.freeSlots() > 0;
        boolean slotOk = false;
        String reject;
        if (!reachOk) {
            reject = NestCheckResult.REJECT_UNREACHABLE;
        } else if (!capacityOk) {
            reject = NestCheckResult.REJECT_CAPACITY_FULL;
        } else if (params.arrivalEtaMs() == null) {
            reject = NestCheckResult.REJECT_SLOT_UNCOMPUTABLE;
        } else {
            long start = params.arrivalEtaMs() - params.slotBufferMs();
            long end = params.arrivalEtaMs() + params.slotHoldMs() + params.slotBufferMs();
            boolean clash = false;
            for (TimeWindow w : c.occupiedWindows()) {
                // D5 两端缓冲：到达时窗前后各扩 slotBufferMs 再判重叠
                if (start < w.endMs() && w.startMs() < end) {
                    clash = true;
                    break;
                }
            }
            slotOk = !clash;
            reject = clash ? NestCheckResult.REJECT_SLOT_OCCUPIED : null;
        }
        return new NestCheckResult(c.nestId(), reachOk, capacityOk, slotOk, reject, c.returnEnergyPercent());
    }

    /**
     * 第二级可达性筛除（《84》§6 表「可达性筛除（C3，二级+三级共用）」＋§4.3
     * {@code e10_nest_avail_factor}）：{@code 到该巢返程能耗 ≤ 可用电量 × 系数}。
     *
     * <p>与 {@link #firstTierFeasibleByReserve} <b>刻意不合并</b>：第一级是「基线＋绝对裕度」不等式，
     * 第二级是「按比例留余量」的筛除，参数来源也不同（{@code e10_reserve_ratio} vs
     * {@code e10_nest_avail_factor}），合成一个方法两处口径必漂移。
     */
    private static boolean reachableForOtherNest(double batteryPercent, double returnEnergyPercent,
            double availFactor) {
        return returnEnergyPercent <= batteryPercent * availFactor;
    }

    private static String floorNote(HomecomingParams params) {
        return params.lowBatteryFallbackEnabled()
                ? "，且高于低电量兜底线 " + params.batteryAbsoluteFloorPct() + "%"
                : "（低电量兜底未接入）";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}