package com.waterlogistics.dispatch.weather;

/**
 * M2 气象消费参数（模块二 · 卢肄文 · 10-20 任务 B2 ＝ 10-21 C1 复用）。
 *
 * <p><b>阈值一律读 {@code sys_param}</b>（《技术栈与运行环境》§六 第 3 条），键名取自
 * {@code deploy/sql/002_sys_param.sql}，<b>不得臆造、不得改名</b>：
 *
 * <table border="1">
 *   <caption>键名出处</caption>
 *   <tr><th>sys_param 键</th><th>已登记值</th><th>含义</th><th>映射到</th></tr>
 *   <tr><td>{@code weatherWindWarningThreshold}</td><td>8.0</td>
 *       <td>风速预警阈值（m/s）</td>
 *       <td>{@link #warningWindMs()}，<b>无新鲜风况时选机判定的兜底风速</b>（C1）</td></tr>
 *   <tr><td>{@code weatherWindCriticalThreshold}</td><td>12.0</td>
 *       <td>风速严重阈值（m/s）</td><td>{@link #criticalWindMs()}，超过即气象熔断</td></tr>
 *   <tr><td>{@code weatherClearDebounce}</td><td>5</td>
 *       <td>清除防抖采样点数</td><td>{@link #clearDebounceCount()}，连续 N 条回落才解除</td></tr>
 * </table>
 *
 * <h3>★ {@link #warningWindMs()} 为什么必须是它，而不是 0</h3>
 * <p>10-21 C1 要求「无新鲜风况时的兜底值<b>不得给 0</b>」——{@code 0 m/s} 会被下游判定
 * 当成「无风、可飞」，是危险方向的默认值。这里取<b>已登记的「风速预警阈值 8.0 m/s」</b>
 * 作为保守假设，理由三条：
 * <ol>
 *   <li><b>有出处</b>：它就在 {@code sys_param} 里、语义正是「需要开始注意的风」，
 *       不是我们临时编的数；</li>
 *   <li><b>保守但不误杀</b>：8.0 低于多数机型的抗风上限，不会把所有机型判死，
 *       主链在气象短暂缺失时仍能推进（否则一次丢帧就把整单卡住）；</li>
 *   <li><b>可解释</b>：调用方在用了兜底时会打 {@code WARN} 日志，现场能看出
 *       「这次判定用的不是实测风」。</li>
 * </ol>
 *
 * <p><b>★ 另一个键在 {@code sys_param} 中尚不存在</b>：气象快照的存活时长 {@code ttlMs}。
 * 按纪律<b>不臆造键名</b>，此值以可覆盖属性 {@code wavenest.m2.weather.ttl-ms} 给出缺省，
 * 待《37》§5.14.1 登记后改为读表——届时只改 {@link WeatherConfig} 一处。
 *
 * <h3>11-05 评审关闭要点（本文件承载 3 条）</h3>
 * <ul>
 *   <li>{@code MED-BUG.69}：TTL 与防抖<b>只有下界无上界</b> → 补 {@link #MAX_TTL_MS}
 *       与 {@link #MAX_CLEAR_DEBOUNCE}，越界即抛（不再静默接受一个能把风况永久留存的 TTL）；</li>
 *   <li>{@code LOW-MAINT.30}：缺省值三处重复（此处 {@link #defaults()}、{@link WeatherConfig}
 *       的 {@code DEFAULT_TTL_MS} 与 {@code @Value} 字面量）→ 全部改引本类的
 *       {@link #DEFAULT_TTL_MS} 等常量，缺省只在一个文件里写；</li>
 *   <li>{@code MED-BUG.66}（{@link WeatherConfig} 侧）：非正 TTL 静默替换缺省值 → 改为抛，
 *       由 Spring 启动期暴露配置错误，而不是带着一个不是自己配的值上线。</li>
 * </ul>
 *
 * <p>本记录<b>只承载参数</b>，不做任何判定；取值与组装在 {@link WeatherConfig}。
 *
 * @param ttlMs              快照存活时长，ms（超过即视为无新鲜风况），取值 (0, {@link #MAX_TTL_MS}]
 * @param warningWindMs      风速预警阈值，m/s（<b>无新鲜风况时的兜底风速</b>，不得为 0）
 * @param criticalWindMs     气象熔断的风速阈值，m/s
 * @param clearDebounceCount 解除熔断所需的连续回落样本数，取值 [1, {@link #MAX_CLEAR_DEBOUNCE}]
 */
public record WeatherSettings(long ttlMs, double warningWindMs, double criticalWindMs,
        int clearDebounceCount) {

    /** 缺省快照存活时长，ms——120 s，覆盖 M1 至少数个轮询周期（唯一出处）。 */
    public static final long DEFAULT_TTL_MS = 120_000L;

    /** 缺省风速预警阈值，m/s——与 {@code 002_sys_param.sql} 已登记值一致（唯一出处）。 */
    public static final double DEFAULT_WARNING_WIND_MS = 8.0;

    /** 缺省风速严重阈值，m/s——与 {@code 002_sys_param.sql} 已登记值一致（唯一出处）。 */
    public static final double DEFAULT_CRITICAL_WIND_MS = 12.0;

    /** 缺省清除防抖样本数——与 {@code 002_sys_param.sql} 已登记值一致（唯一出处）。 */
    public static final int DEFAULT_CLEAR_DEBOUNCE = 5;

    /**
     * TTL 上界，ms——1 小时。
     *
     * <p>MED-BUG.69 的关闭点：原实现只拦「非正」，一个手误多打两个零的
     * {@code ttl-ms: 12000000}（约 3.3 小时）会被静默接受，后果是**过期风况继续参与判定**——
     * 气象熔断该跳不跳、抗风淘汰该刷不刷，而且日志里看不到任何异常。
     * 1 小时的上界与演示时长、与 M1 的发布节奏（秒级）都留了三个数量级余量，
     * 真要更长只能改本常量并说明理由。
     */
    public static final long MAX_TTL_MS = 3_600_000L;

    /**
     * 防抖样本数上界——60 条。
     *
     * <p>MED-BUG.69 的另一半：防抖是「连续 N 条回落才解除熔断」，N 配得过大
     * 等于**熔断实质上永不解除**（演示里每秒 1 条，60 条即 1 分钟；再多就该怀疑配错了）。
     * 上界同时兜住「把毫秒当条数填」这类单位错（如误填 120000）。
     */
    public static final int MAX_CLEAR_DEBOUNCE = 60;

    public WeatherSettings {
        if (!(ttlMs > 0L)) {
            throw new IllegalArgumentException("ttlMs 必须为正：" + ttlMs);
        }
        if (ttlMs > MAX_TTL_MS) {
            throw new IllegalArgumentException("ttlMs 超出上界 " + MAX_TTL_MS + " ms（1 小时）：" + ttlMs
                    + "——过大的 TTL 会让过期风况继续参与判定，气象熔断该跳不跳");
        }
        if (!(warningWindMs > 0.0) || !Double.isFinite(warningWindMs)) {
            throw new IllegalArgumentException("warningWindMs 必须为正有限值（兜底值不得为 0）："
                    + warningWindMs);
        }
        if (!(criticalWindMs > 0.0) || !Double.isFinite(criticalWindMs)) {
            throw new IllegalArgumentException("criticalWindMs 必须为正有限值：" + criticalWindMs);
        }
        if (criticalWindMs <= warningWindMs) {
            throw new IllegalArgumentException("严重阈值必须高于预警阈值："
                    + criticalWindMs + " <= " + warningWindMs);
        }
        if (clearDebounceCount < 1) {
            throw new IllegalArgumentException("clearDebounceCount 至少为 1：" + clearDebounceCount);
        }
        if (clearDebounceCount > MAX_CLEAR_DEBOUNCE) {
            throw new IllegalArgumentException("clearDebounceCount 超出上界 " + MAX_CLEAR_DEBOUNCE
                    + "：" + clearDebounceCount + "——过大的防抖等于熔断永不解除");
        }
    }

    /**
     * 演示缺省值：与 {@code 002_sys_param.sql} 已登记值一致
     * （预警 8.0、严重 12.0 m/s、防抖 5 条）；{@code ttlMs} 取 120 s
     * ——覆盖 M1 至少数个轮询周期，避免偶发丢一帧就把风况判成过期。
     *
     * <p>11-05（LOW-MAINT.30）：四个数都改成引本类常量，不再在这里写字面量——
     * 缺省值在本类、{@link WeatherConfig}、{@code @Value} 三处各写一遍，
     * 改一处漏两处的风险是实打实的（{@code ttlMs} 已经发生过一次：本类 120 s、
     * {@code @Value} 也是 120 s，靠人眼核对保持一致）。
     */
    public static WeatherSettings defaults() {
        return new WeatherSettings(DEFAULT_TTL_MS, DEFAULT_WARNING_WIND_MS,
                DEFAULT_CRITICAL_WIND_MS, DEFAULT_CLEAR_DEBOUNCE);
    }
}
