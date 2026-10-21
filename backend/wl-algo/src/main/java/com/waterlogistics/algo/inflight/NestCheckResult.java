package com.waterlogistics.algo.inflight;

/**
 * 单个候选他巢的<b>逐项检查记录</b>（审计用），对应《84》功能10 第4章
 * 4.1 可达性筛除／4.2 容量 D2／4.3 时隙 D1／4.4 时隙重叠 D5。
 *
 * <p>4.5「借位准入完备性检查 D3」原文：「必须确认<b>所有候选他巢</b>都被检查且均不可用
 * （可达性×容量×时隙全失败），才输出"无位可借"」；「<b>答辩可展示"候选表逐项打叉"</b>」。
 * 故本类型不只给总布尔，还要记每条判据各自的结果与拒绝原因。
 *
 * @param nestId              机巢标识
 * @param reachabilityOk      C3 可达性筛除是否通过（到他巢能耗 ≤ 可用电量×系数）
 * @param capacityOk          D2 容量约束检查是否通过
 * @param slotOk              D1 时隙可行性检查是否通过（不可算时为 false）
 * @param rejectReason        拒绝原因码（三项全过时为 null）
 * @param returnEnergyPercent 该巢返程能耗，百分比（复核用）
 */
public record NestCheckResult(
        String nestId,
        boolean reachabilityOk,
        boolean capacityOk,
        boolean slotOk,
        String rejectReason,
        double returnEnergyPercent) {

    /** C3 可达性筛除不通过：到该巢返程能耗 > 可用电量 × {@code e10_nest_avail_factor} */
    public static final String REJECT_UNREACHABLE = "UNREACHABLE_BY_ENERGY";
    /** D2 容量已满（粗筛即否，不必逐时隙比较） */
    public static final String REJECT_CAPACITY_FULL = "CAPACITY_FULL";
    /** D1／D5 预计到达时刻附近（含 {@code e10_slot_buffer} 两端缓冲）无空闲机位时隙 */
    public static final String REJECT_SLOT_OCCUPIED = "SLOT_OCCUPIED_AT_ARRIVAL";
    /** 时隙判据不可算（缺预计到达时刻或占用时长）：按不通过处理，但不谎报为冲突 */
    public static final String REJECT_SLOT_UNCOMPUTABLE = "SLOT_CHECK_UNCOMPUTABLE";

    public NestCheckResult {
        if (nestId == null || nestId.isBlank()) {
            throw new IllegalArgumentException("检查记录须带 nestId，否则审计表无法定位是哪一巢被否");
        }
        if (reachabilityOk && capacityOk && slotOk && rejectReason != null) {
            throw new IllegalArgumentException("三项判据全过时不得带拒绝原因，实测 nestId=" + nestId);
        }
    }

    /** 三条判据是否全部通过（C3 × D2 × D1） */
    public boolean admitted() {
        return reachabilityOk && capacityOk && slotOk;
    }
}