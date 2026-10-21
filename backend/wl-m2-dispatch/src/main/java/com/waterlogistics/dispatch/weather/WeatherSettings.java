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
 * <p>本记录<b>只承载参数</b>，不做任何判定；取值与组装在 {@link WeatherConfig}。
 *
 * @param ttlMs              快照存活时长，ms（超过即视为无新鲜风况）
 * @param warningWindMs      风速预警阈值，m/s（<b>无新鲜风况时的兜底风速</b>，不得为 0）
 * @param criticalWindMs     气象熔断的风速阈值，m/s
 * @param clearDebounceCount 解除熔断所需的连续回落样本数
 */
public record WeatherSettings(long ttlMs, double warningWindMs, double criticalWindMs,
        int clearDebounceCount) {

    public WeatherSettings {
        if (!(ttlMs > 0L)) {
            throw new IllegalArgumentException("ttlMs 必须为正：" + ttlMs);
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
    }

    /**
     * 演示缺省值：与 {@code 002_sys_param.sql} 已登记值一致
     * （预警 8.0、严重 12.0 m/s、防抖 5 条）；{@code ttlMs} 取 120 s
     * ——覆盖 M1 至少数个轮询周期，避免偶发丢一帧就把风况判成过期。
     */
    public static WeatherSettings defaults() {
        return new WeatherSettings(120_000L, 8.0, 12.0, 5);
    }
}
