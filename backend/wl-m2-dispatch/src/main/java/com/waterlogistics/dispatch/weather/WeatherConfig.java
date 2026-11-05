package com.waterlogistics.dispatch.weather;

import com.waterlogistics.storage.param.SysParamLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>{@code weatherWindWarningThreshold ＝ 8.0} —— 风速预警阈值（m/s），
 *       兼作无新鲜风况时的兜底风速；</li>
 *   <li>{@code weatherWindCriticalThreshold ＝ 12.0} —— 风速严重阈值（m/s），气象熔断判据；</li>
 *   <li>{@code weatherClearDebounce ＝ 5} —— 清除防抖采样点数，连续 N 条回落才解除熔断。</li>
 * </ul>
 *
 * <p><b>★ {@code ttlMs} 在 {@code sys_param} 中尚无对应键</b>，按纪律<b>不臆造键名</b>，
 * 改用可覆盖属性 {@code wavenest.m2.weather.ttl-ms}；待《37》§5.14.1 登记后，
 * 把本方法第三行改成 {@code sysParamLoader.getInt(KEY_..., ...)} 即可，只此一处。
 *
 * <h3>11-05 评审关闭要点（本文件承载 3 条）</h3>
 * <ul>
 *   <li><b>{@code MED-BUG.66}</b>：原 {@code ttlMs > 0L ? ttlMs : DEFAULT_TTL_MS} 把
 *       「配错」（0 或负数）静默替换成缺省值——配置写错的人看不到任何提示，
 *       上线后用的不是他配的数。改为<b>交给 {@link WeatherSettings} 的紧凑构造器抛异常</b>，
 *       把配置错误暴露在启动期（Spring 起不来 + 异常信息带实际值），而不是带病运行。</li>
 *   <li><b>{@code MED-MAINT.18}</b>：TTL 缺省值原来在 {@code @Value} 的 {@code :120000}
 *       与 {@link #DEFAULT_TTL_MS} 两处各写一遍，本类又转存一份常量。
 *       现在<b>唯一出处是 {@link WeatherSettings#DEFAULT_TTL_MS}</b>，
 *       {@code @Value} 的表达式用字符串拼接引它——注解值必须是编译期常量，
 *       拼接后仍是常量，故可行；三个阈值同样改引 {@link WeatherSettings} 的常量。</li>
 *   <li><b>{@code MED-MAINT.19}</b>：三个阈值在<b>启动期</b>从 {@code sys_param} 读出后
 *       固化进 {@link WeatherSettings}，运行期改表<b>不生效</b>——原 javadoc 未注明，
 *       现场调参后以为立刻生效、实则要重启。现在在类注释与 {@link #weatherSettings} 上
 *       都写明「启动期读取一次，改表需重启」，并在装配时打一条 INFO 把实际生效值打出来。</li>
 * </ul>
 *
 * <p>本类只负责「取值 → 组装」，不参与任何计算；{@code m2 → wl-storage} 的正常依赖
 * （《包结构设计》§二）。
 */
@Configuration
public class WeatherConfig {

    private static final Logger log = LoggerFactory.getLogger(WeatherConfig.class);

    /** 风速预警阈值（m/s）—— <b>无新鲜风况时的兜底风速</b>（C1 选机判定用，不得为 0）。 */
    static final String KEY_WIND_WARNING_MS = "weatherWindWarningThreshold";

    /** 风速严重阈值（m/s）—— 超过即气象熔断。 */
    static final String KEY_WIND_CRITICAL_MS = "weatherWindCriticalThreshold";

    /** 清除防抖采样点数 —— 连续 N 条回落才解除熔断。 */
    static final String KEY_CLEAR_DEBOUNCE = "weatherClearDebounce";

    /** 缺省：快照存活时长，ms —— {@code sys_param} 尚无此键（不臆造），可由属性覆盖。 */
    static final long DEFAULT_TTL_MS = WeatherSettings.DEFAULT_TTL_MS;

    /**
     * 组装气象消费参数。
     *
     * <p><b>只在启动期读一次</b>（{@code @Bean} 单例）：运行期修改
     * {@code sys_param} 或 {@code application.yml} 的 {@code ttl-ms} <b>都不会生效</b>，
     * 要改必须重启 M2 进程。装配时打一条 INFO 把四个实际生效值打出来，
     * 现场核对「我改的到底生效没有」不必再去翻配置。
     *
     * @param sysParamLoader 参数表加载器
     * @param ttlMs          快照存活时长（属性 {@code wavenest.m2.weather.ttl-ms}，
     *                       缺省 {@link WeatherSettings#DEFAULT_TTL_MS}）
     * @return 生效的气象参数
     * @throws IllegalArgumentException TTL 非正或超出 {@link WeatherSettings#MAX_TTL_MS}，
     *                                  或阈值组合非法（严重 ≤ 预警、防抖越界）时抛出，
     *                                  由 Spring 启动期直接暴露配置错误，不做静默替换
     */
    @Bean
    public WeatherSettings weatherSettings(SysParamLoader sysParamLoader,
            @Value("${wavenest.m2.weather.ttl-ms:" + WeatherSettings.DEFAULT_TTL_MS + "}") long ttlMs) {
        WeatherSettings settings = new WeatherSettings(
                ttlMs,
                sysParamLoader.getDouble(KEY_WIND_WARNING_MS, WeatherSettings.DEFAULT_WARNING_WIND_MS),
                sysParamLoader.getDouble(KEY_WIND_CRITICAL_MS, WeatherSettings.DEFAULT_CRITICAL_WIND_MS),
                sysParamLoader.getInt(KEY_CLEAR_DEBOUNCE, WeatherSettings.DEFAULT_CLEAR_DEBOUNCE));
        log.info("气象参数（启动期读取，运行期改表需重启）：ttlMs={} warningWindMs={} criticalWindMs={} "
                        + "clearDebounceCount={}",
                settings.ttlMs(), settings.warningWindMs(), settings.criticalWindMs(),
                settings.clearDebounceCount());
        return settings;
    }
}
