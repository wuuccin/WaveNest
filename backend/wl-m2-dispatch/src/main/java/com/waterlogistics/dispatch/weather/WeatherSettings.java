package com.waterlogistics.dispatch.weather;

/**
 * M2 气象消费参数（模块二 · 卢肄文 · 10-20 任务 B2）。
 *
 * <p><b>阈值一律读 {@code sys_param}</b>（《技术栈与运行环境》§六 第 3 条），键名取自
 * {@code deploy/sql/002_sys_param.sql}，<b>不得臆造、不得改名</b>：
 *
 * <table border="1">
 *   <caption>键名出处</caption>
 *   <tr><th>sys_param 键</th><th>已登记值</th><th>含义</th><th>映射到</th></tr>
 *   <tr><td>{@code weatherWindCriticalThreshold}</td><td>12.0</td>
 *       <td>风速严重阈值（m/s）</td><td>{@link #criticalWindMs()}，超过即气象熔断</td></tr>
 *   <tr><td>{@code weatherClearDebounce}</td><td>5</td>
 *       <td>清除防抖采样点数</td><td>{@link #clearDebounceCount()}，连续 N 条回落才解除</td></tr>
 * </table>
 *
 * <p><b>★ 一个键在 {@code sys_param} 中尚不存在</b>：气象快照的存活时长 {@code ttlMs}。
 * 按纪律<b>不臆造键名</b>，此值以可覆盖属性 {@code wavenest.m2.weather.ttl-ms} 给出缺省，
 * 待《37》§5.14.1 登记后改为读表——届时只改 {@link WeatherConfig} 一处。
 *
 * <p>本记录<b>只承载参数</b>，不做任何判定；取值与组装在 {@link WeatherConfig}。
 *
 * @param ttlMs              快照存活时长，ms（超过即视为无新鲜风况）
 * @param criticalWindMs     气象熔断的风速阈值，m/s
 * @param clearDebounceCount 解除熔断所需的连续回落样本数
 */
public record WeatherSettings(long ttlMs, double criticalWindMs, int clearDebounceCount) {

    public WeatherSettings {
        if (!(ttlMs > 0L)) {
            throw new IllegalArgumentException("ttlMs 必须为正：" + ttlMs);
        }
        if (!(criticalWindMs > 0.0) || !Double.isFinite(criticalWindMs)) {
            throw new IllegalArgumentException("criticalWindMs 必须为正有限值：" + criticalWindMs);
        }
        if (clearDebounceCount < 1) {
            throw new IllegalArgumentException("clearDebounceCount 至少为 1：" + clearDebounceCount);
        }
    }

    /**
     * 演示缺省值：与 {@code 002_sys_param.sql} 已登记值一致
     * （严重阈值 12.0 m/s、防抖 5 条）；{@code ttlMs} 取 120 s
     * ——覆盖 M1 至少数个轮询周期，避免偶发丢一帧就把风况判成过期。
     */
    public static WeatherSettings defaults() {
        return new WeatherSettings(120_000L, 12.0, 5);
    }
}
