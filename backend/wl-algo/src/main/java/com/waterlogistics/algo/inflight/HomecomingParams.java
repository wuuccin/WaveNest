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
 *   <tr><td>{@code batteryAbsoluteFloorPct}</td><td>{@code lowBatteryTrigger}</td>
 *       <td>百分点，2026-10-18 已统一为 20，与 G 组告警共用同一 sys_param 键。
 *           由模块层读出后注入；无注入时本类退化为「不启用兜底」</td></tr>
 *   <tr><td>{@code batteryReleaseMarginPct}</td><td>{@code e10_hyst_exit_ratio}（0.6 无量纲比率）</td>
 *       <td><b>进入裕度 × 0.6</b>：0.20×100×0.6 → 12.0 百分点（退出线宽于进入线，防乒乓）</td></tr>
 *   <tr><td>{@code nestAvailFactor}</td><td>{@code e10_nest_avail_factor}（0.8 无量纲比率）</td>
 *       <td>原样使用；第二级判据为「到他巢能耗 ≤ 可用电量×factor」</td></tr>
 *   <tr><td>{@code slotBufferMs}</td><td>{@code e10_slot_buffer}（58 s）</td>
 *       <td><b>× 1000</b> 成毫秒：58 s → 58000 ms；用于 4.4 时隙重叠判定两端缓冲</td></tr>
 *   <tr><td>{@code windMarginFactor}</td><td>{@code e10_margin_k}（1.2 无量纲比率）</td>
 *       <td>原样使用；<b>仅在无风况快照时生效</b>（§7.7 硬约束 2，见
 *           {@link HomecomingInput#windSnapshot()}）</td></tr>
 *   <tr><td>{@code windMarginFactorExit}</td><td>{@code e10_margin_k} × {@code e10_hyst_exit_ratio}</td>
 *       <td>退出侧的 k：1.2 × 0.6 → 0.72；与电量裕度同口径，仅第一级迟滞退出时启用</td></tr>
 *   <tr><td>{@code slotHoldMs}</td><td>{@code e10_slot_hold}（300 s）</td>
 *       <td><b>× 1000</b> 成毫秒：300 s → 300000 ms；落位后作业占用，与 {@code slotBufferMs} 分离</td></tr>
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
 * @param windMarginFactor 固定裕度系数 k（进入侧）；<b>仅无风况快照时生效</b>（对应 {@code e10_margin_k}）
 * @param windMarginFactorExit 固定裕度系数 k（退出侧，对应 {@code e10_margin_k×e10_hyst_exit_ratio}）；
 *                             <b>须 ≤ 进入侧</b>，同值即等价于风况侧「不迟滞」
 * @param slotBufferMs 4.4 时隙重叠判定近进缓冲（窗口下界），毫秒（对应 {@code e10_slot_buffer}×1000）；须 ≥ 0
 * @param arrivalEtaMs 预计到达时刻，Unix 毫秒（第二级时隙判定用）；可空则时隙判据不可算
 * @param slotHoldMs 归巢时隙占用时长，毫秒（D5 占用时长估计）；须 &gt; 0
 * @param robustWindOffsetMs A4 鲁棒档风速轴偏移 Δv，<b>米每秒（m/s）</b>，对应
 *                       {@code e10RobustWindOffsetMs}（建议 3，2–5 中值）；可空＝未注入 A4 档。
 *                       {@code Ms} 此处是 m/s，不是毫秒（本库 {@code slotBufferMs} 才是毫秒）。
 *                       算法层只据此识别 A4 档，不乘系数——{@code v_robust = 上界 + 该偏移} 由装配层算
 * @param alphaFailProb A3 机会约束允许失败概率 α，无量纲，对应 {@code e10AlphaFailProb}；
 *                      可空，<b>本批不灌、无默认值</b>（等风况分布形态）——故 A3 档只留结构、暂不可达
 */
public record HomecomingParams(
        double batterySafetyMarginPct,
        double batteryReleaseMarginPct,
        Double batteryAbsoluteFloorPct,
        double nestAvailFactor,
        double windMarginFactor,
        double windMarginFactorExit,
        long slotBufferMs,
        Long arrivalEtaMs,
        long slotHoldMs,
        Double robustWindOffsetMs,
        Double alphaFailProb) {

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
     * 《84》§4.3 {@code e10RobustWindOffsetMs} = 3 m/s（参考区间 2–5 m/s 的中值）。
     *
     * <p><b>仅供单测与仿真起步</b>；生产取值须由模块层从 {@code sys_param} 读出后注入，本类不设生产默认值。
     * 单位是<b>米每秒</b>，不是毫秒。
     */
    public static final double SUGGESTED_ROBUST_WIND_OFFSET_MS = 3.0;

    /**
     * 第三级迫降点距航道半宽阈值建议值 = 500 米（11-16 清单 1-6，键 {@code e10LandingCorridorHalfWidthM}）。
     *
     * <p>赣江该段江面＋近岸横向尺度为数百米量级：比会面走廊半宽 50 m 高一个量级、比机巢服务半径 5 km
     * 低一个量级，故取 500 m。<b>仅供单测与仿真起步</b>；生产取值须由模块层从 {@code sys_param} 读出后注入，
     * 阈值未注入时选择器判「不可算」而非取该建议值（不臆造）。
     */
    public static final double SUGGESTED_LANDING_CORRIDOR_HALF_WIDTH_M = 500.0;

    /**
     * 《84》§4.3 {@code e10_margin_k} = 1.2 是「固定裕度系数 k」的<b>建议初值</b>。
     *
     * <p><b>本类刻意不给 k 的默认值</b>：§7.7 硬约束 2「风况只经返程基线进入判定，不得单独成维或单独调阈值」
     * ——k 只在<b>无风况快照</b>时启用（上游既不给分布也不给范围的最低档，§2.3 ③）。
     * 给一个恒 > 1 的默认值等于默认全场景放大基线，等于把「重复计数」写死。生产值须注入。
     */
    public static HomecomingParams suggested(double nestAvailFactor, double windMarginFactor,
            long arrivalEtaMs, long slotHoldMs) {
        return suggested(nestAvailFactor, windMarginFactor, arrivalEtaMs, slotHoldMs, null);
    }

    /**
     * 带 A4 风速偏移的建议构造（单测钉死三档判档链用）。{@code robustWindOffsetMs} 为空时即不提供 A4 档。
     */
    public static HomecomingParams suggested(double nestAvailFactor, double windMarginFactor,
            long arrivalEtaMs, long slotHoldMs, Double robustWindOffsetMs) {
        return new HomecomingParams(SUGGESTED_BATTERY_SAFETY_MARGIN_PCT,
                SUGGESTED_BATTERY_SAFETY_MARGIN_PCT, null,
                nestAvailFactor, windMarginFactor, windMarginFactor, SUGGESTED_SLOT_BUFFER_MS,
                arrivalEtaMs, slotHoldMs, robustWindOffsetMs, null);
    }

    /**
     * 九参兼容构造器：develop 既有调用方（2-1 迟滞两值读键已落）不传 A4／A3 两字段时视为未注入。
     * A4 档由 {@link #suggested(double, double, long, long, Double)} 或 11 参规范构造器显式注入。
     */
    public HomecomingParams(double batterySafetyMarginPct, double batteryReleaseMarginPct,
            Double batteryAbsoluteFloorPct, double nestAvailFactor, double windMarginFactor,
            double windMarginFactorExit, long slotBufferMs, Long arrivalEtaMs, long slotHoldMs) {
        this(batterySafetyMarginPct, batteryReleaseMarginPct, batteryAbsoluteFloorPct,
                nestAvailFactor, windMarginFactor, windMarginFactorExit, slotBufferMs,
                arrivalEtaMs, slotHoldMs, null, null);
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
        if (!Double.isFinite(windMarginFactorExit) || windMarginFactorExit <= 0) {
            throw new IllegalArgumentException("退出侧固定裕度系数 k 须为正有限值，实测=" + windMarginFactorExit);
        }
        if (windMarginFactorExit > windMarginFactor) {
            throw new IllegalArgumentException("退出侧 k(" + windMarginFactorExit
                    + ")不得高于进入侧 k(" + windMarginFactor + ")：迟滞要求退出门限低于进入门限");
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
        if (robustWindOffsetMs != null
                && (!Double.isFinite(robustWindOffsetMs) || robustWindOffsetMs < 0)) {
            throw new IllegalArgumentException("A4 风速轴偏移 Δv 须为非负有限值（m/s），实测="
                    + robustWindOffsetMs);
        }
        if (alphaFailProb != null && (!Double.isFinite(alphaFailProb)
                || alphaFailProb <= 0 || alphaFailProb >= 1)) {
            throw new IllegalArgumentException("A3 允许失败概率 α 须为 (0,1) 区间有限值，实测="
                    + alphaFailProb);
        }
    }

    private static void requireNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + "须为非负有限值（百分点），实测=" + value);
        }
    }
}
