package com.waterlogistics.algo.inflight;

/**
 * 功能10 的阈值与判据参数，<b>全部由调用方注入</b>。
 *
 * <p>《10-16 清单》§四硬口径：「阈值一律读 {@code sys_param}，不确定就报组长不臆造」——故本类不读库、
 * 不设生产默认值；字段与《84》§4.3「sys_param 默认值建议表（E-12 · 功能10 三级降级）」一一对应。
 *
 * <p><b>参数与文档键的换算关系</b>（量纲差异必须由模块层负责换算，不得在算法层臆造）：
 * <table border="1">
 *   <caption>本 record 字段 ← 《84》§4.3 参数键</caption>
 *   <tr><th>本字段</th><th>《84》§4.3 参数键</th><th>换算</th></tr>
 *   <tr><td>{@code batterySafetyMarginPct}</td><td>{@code e10_reserve_ratio}（0.20 无量纲比率）</td>
 *       <td><b>× 100</b> 成百分点：本项目实时电量按百分比（满电＝100），故 0.20 → 20.0</td></tr>
 *   <tr><td>{@code batteryAbsoluteFloorPct}</td><td>{@code e10_low_battery_abs}</td>
 *       <td>百分点。<b>不自行给默认值</b>——文档标「不自定，引 G 组 G-11 统一阈值」，
 *           无注入时本类退化为「不启用兜底」，不得臆造数值</td></tr>
 *   <tr><td>{@code batteryReleaseMarginPct}</td><td>{@code e10_hyst_exit_ratio}（1.2 无量纲比率）</td>
 *       <td><b>换算口径待确认</b>：文档给的是 ratio、与本字段的百分点不同量纲，
 *           且 1.2 &gt; 1 无法直接读成「退出裕度＝进入裕度×1.2」（退出应比进入宽松）。
 *           已按硬口径报组长，<b>在此不臆造换算</b>，由模块层确认后注入</td></tr>
 *   <tr><td>{@code nestAvailFactor}</td><td>{@code e10_nest_avail_factor}（0.8 无量纲比率）</td>
 *       <td>原样使用；第二级判据为「到他巢能耗 ≤ 可用电量×factor」</td></tr>
 *   <tr><td>{@code slotBufferMs}</td><td>{@code e10_slot_buffer}（58 s）</td>
 *       <td><b>× 1000</b> 成毫秒：58 s → 58000 ms；用于 4.4 时隙重叠判定两端缓冲</td></tr>
 *   <tr><td>{@code windMarginFactor}</td><td>{@code e10_margin_k}（1.2 无量纲比率）</td>
 *       <td>原样使用；<b>仅在无风况快照时生效</b>（§7.7 硬约束 2，见
 *           {@link HomecomingInput#windSnapshot()}）</td></tr>
 *   <tr><td>{@code slotHoldMs}</td><td><b>无对应键</b></td>
 *       <td>D5「占用时长估计」的数据源未在 §4.3 落键，已登记待确认，故本类不给建议值</td></tr>
 * </table>
 *
 * <p><b>迟滞只作用于第一级</b>：《84》§7.4 已决事项逐字「迟滞适用范围｜只在功能10 第一级能量边界生效；
 * 第二、三级靠『占位即锁定』防横跳」——故只给第一级双阈值，二三级不设迟滞字段。
 *
 * @param batterySafetyMarginPct 第一级 A1 储备裕度，百分点（对应 {@code e10_reserve_ratio}×100）
 * @param batteryReleaseMarginPct 第一级 A1 迟滞退出裕度，百分点；<b>须 ≤ 触发裕度</b>；
 *                               传与触发裕度<b>同值</b>即等价于「不迟滞」（退出＝进入）
 * @param batteryAbsoluteFloorPct 第一级 A5 低电量绝对阈值兜底，百分点；<b>可空＝未接入兜底</b>
 * @param nestAvailFactor 第二级他巢可达性系数，原样使用（对应 {@code e10_nest_avail_factor}）
 * @param windMarginFactor 固定裕度系数 k；<b>仅无风况快照时生效</b>（对应 {@code e10_margin_k}）
 * @param slotBufferMs 4.4 时隙重叠判定两端缓冲，毫秒（对应 {@code e10_slot_buffer}×1000）；须 ≥ 0
 * @param arrivalEtaMs 预计到达时刻，Unix 毫秒（第二级时隙判定用）；可空则时隙判据不可算
 * @param slotHoldMs 归巢时隙占用时长，毫秒（D5 占用时长估计）；须 &gt; 0
 */
public record HomecomingParams(
        double batterySafetyMarginPct,
        double batteryReleaseMarginPct,
        Double batteryAbsoluteFloorPct,
        double nestAvailFactor,
        double windMarginFactor,
        long slotBufferMs,
        Long arrivalEtaMs,
        long slotHoldMs) {

    /**
     * 《84》§4.3 {@code e10_reserve_ratio} = 0.20（无量纲比率），换算成百分点得 20.0。
     *
     * <p><b>仅供单测与仿真起步</b>，生产取值须由模块层从 {@code sys_param} 读出后换算注入。
     */
    public static final double SUGGESTED_BATTERY_SAFETY_MARGIN_PCT = 20.0;

    /**
     * 《84》§4.3 {@code e10_nest_avail_factor} = 0.8（无量纲比率，原样使用）。
     *
     * <p><b>仅供单测与仿真起步</b>，生产取值须由模块层从 {@code sys_param} 读出后注入。
     */
    public static final double SUGGESTED_NEST_AVAIL_FACTOR = 0.8;

    /**
     * 《84》§4.3 {@code e10_slot_buffer} = 58 s，换算成毫秒得 58000。
     *
     * <p><b>仅供单测与仿真起步</b>，生产取值须由模块层从 {@code sys_param} 读出后换算注入。
     */
    public static final long SUGGESTED_SLOT_BUFFER_MS = 58_000L;

    /**
     * 《84》§4.3 {@code e10_margin_k} = 1.2 是「固定裕度系数 k」的<b>建议初值</b>。
     *
     * <p><b>本类刻意不给 k 的默认值</b>：§7.7 硬约束 2「风况只经返程基线进入判定，不得单独成维或单独调阈值」
     * ——k 只在<b>无风况快照</b>时启用（上游既不给分布也不给范围的最低档，§2.3 ③）。
     * 给一个恒 > 1 的默认值等于默认全场景放大基线，等于把「重复计数」写死。生产值须注入。
     */
    public static HomecomingParams suggested(double nestAvailFactor, double windMarginFactor,
            long arrivalEtaMs, long slotHoldMs) {
        return new HomecomingParams(SUGGESTED_BATTERY_SAFETY_MARGIN_PCT,
                SUGGESTED_BATTERY_SAFETY_MARGIN_PCT, null,
                nestAvailFactor, windMarginFactor, SUGGESTED_SLOT_BUFFER_MS, arrivalEtaMs, slotHoldMs);
    }

    /** A5 低电量兜底是否已接入（未接入时第一级只有 A1 一条判据，属降级运行） */
    public boolean lowBatteryFallbackEnabled() {
        return batteryAbsoluteFloorPct != null;
    }

    public HomecomingParams {
        requireNonNegative(batterySafetyMarginPct, "触发裕度");
        requireNonNegative(batteryReleaseMarginPct, "解除裕度");
        if (batteryReleaseMarginPct > batterySafetyMarginPct) {
            throw new IllegalArgumentException("解除裕度(" + batteryReleaseMarginPct
                    + ")不得高于触发裕度(" + batterySafetyMarginPct
                    + ")：迟滞要求退出门限低于进入门限");
        }
        if (batteryAbsoluteFloorPct != null) {
            requireNonNegative(batteryAbsoluteFloorPct, "低电量绝对阈值");
            if (batteryAbsoluteFloorPct > 100) {
                throw new IllegalArgumentException("低电量绝对阈值不得超 100%，实测=" + batteryAbsoluteFloorPct);
            }
        }
        if (!Double.isFinite(nestAvailFactor) || nestAvailFactor <= 0 || nestAvailFactor > 1) {
            throw new IllegalArgumentException("他巢可达性系数须为 (0,1] 区间有限值（能耗 ≤ 可用电量×系数），实测="
                    + nestAvailFactor);
        }
        if (!Double.isFinite(windMarginFactor) || windMarginFactor <= 0) {
            throw new IllegalArgumentException("固定裕度系数 k 须为正有限值，实测=" + windMarginFactor);
        }
        if (slotBufferMs < 0) {
            throw new IllegalArgumentException("时隙重叠两端缓冲不得为负（毫秒），实测=" + slotBufferMs);
        }
        if (slotHoldMs <= 0) {
            throw new IllegalArgumentException("归巢时隙占用时长须为正（毫秒），实测=" + slotHoldMs);
        }
        if (arrivalEtaMs != null && arrivalEtaMs < 0) {
            throw new IllegalArgumentException("预计到达时刻不可为负，实测=" + arrivalEtaMs);
        }
    }

    private static void requireNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + "须为非负有限值（百分点），实测=" + value);
        }
    }
}