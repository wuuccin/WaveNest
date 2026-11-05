package com.waterlogistics.dispatch.weather;

import com.waterlogistics.storage.param.SysParamLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 气象消费参数的装配（M2 · 卢肄文）——「阈值一律读 {@code sys_param}」的落地处。
 *
 * <p>与 {@code PredictionConfig} 同一套写法：{@link SysParamLoader} 的
 * {@code getDouble/getInt} 在键缺失或解析失败时返回传入默认值，故此处无需自行判空。
 *
 * <p><b>键名出处</b>（已逐个核对 {@code deploy/sql/002_sys_param.sql}）：
 * <ul>
 *   <li>{@code weatherWindCriticalThreshold ＝ 12.0} —— 风速严重阈值（m/s），气象熔断判据；</li>
 *   <li>{@code weatherClearDebounce ＝ 5} —— 清除防抖采样点数，连续 N 条回落才解除熔断。</li>
 * </ul>
 *
 * <p><b>★ {@code ttlMs} 在 {@code sys_param} 中尚无对应键</b>，按纪律<b>不臆造键名</b>，
 * 改用可覆盖属性 {@code wavenest.m2.weather.ttl-ms}；待《37》§5.14.1 登记后，
 * 把本方法第三行改成 {@code sysParamLoader.getInt(KEY_..., ...)} 即可，只此一处。
 *
 * <p>本类只负责「取值 → 组装」，不参与任何计算；{@code m2 → wl-storage} 的正常依赖
 * （《包结构设计》§二）。
 */
@Configuration
public class WeatherConfig {

    /** 风速预警阈值（m/s）—— <b>无新鲜风况时的兜底风速</b>（C1 选机判定用，不得为 0）。 */
    static final String KEY_WIND_WARNING_MS = "weatherWindWarningThreshold";

    /** 风速严重阈值（m/s）—— 超过即气象熔断。 */
    static final String KEY_WIND_CRITICAL_MS = "weatherWindCriticalThreshold";

    /** 清除防抖采样点数 —— 连续 N 条回落才解除熔断。 */
    static final String KEY_CLEAR_DEBOUNCE = "weatherClearDebounce";

    /** 缺省：快照存活时长，ms —— {@code sys_param} 尚无此键（不臆造），可由属性覆盖。 */
    static final long DEFAULT_TTL_MS = 120_000L;

    /**
     * 组装气象消费参数。
     *
     * @param sysParamLoader 参数表加载器
     * @param ttlMs          快照存活时长（属性 {@code wavenest.m2.weather.ttl-ms}，缺省 120 s）
     */
    @Bean
    public WeatherSettings weatherSettings(SysParamLoader sysParamLoader,
            @Value("${wavenest.m2.weather.ttl-ms:120000}") long ttlMs) {
        return new WeatherSettings(
                ttlMs > 0L ? ttlMs : DEFAULT_TTL_MS,
                sysParamLoader.getDouble(KEY_WIND_WARNING_MS, 8.0),
                sysParamLoader.getDouble(KEY_WIND_CRITICAL_MS, 12.0),
                sysParamLoader.getInt(KEY_CLEAR_DEBOUNCE, 5));
    }
}
