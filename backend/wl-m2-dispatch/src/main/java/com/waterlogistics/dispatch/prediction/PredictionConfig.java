package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.storage.param.SysParamLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 预测参数的装配（M2 · 卢肄文）——<b>阈值一律读 {@code sys_param}</b> 的落地处。
 *
 * <p>依据《技术栈与运行环境》§六 第 3 条「阈值不硬编码」与《10-16 当日任务速览》§四
 * 「阈值一律读 {@code sys_param}，不确定就报组长<b>不臆造</b>」。
 *
 * <h3>键名出处（已逐个核对 {@code deploy/sql/002_sys_param.sql}，不得改名）</h3>
 * <table border="1">
 *   <tr><th>sys_param 键</th><th>值</th><th>含义</th><th>映射到</th></tr>
 *   <tr><td>{@code sigmaPosM}</td><td>20</td><td>AIS 位置误差</td><td>σ∥ 初值</td></tr>
 *   <tr><td>{@code sigmaEM}</td><td>15</td><td>横向稳态标准差</td><td>σ⊥ 初值</td></tr>
 *   <tr><td>{@code alphaAlongMs}</td><td>0.05</td><td>纵向模型误差率</td><td>σ∥ 增长率</td></tr>
 *   <tr><td>{@code alphaCrossMs}</td><td>0.03</td><td>横向模型误差率</td><td>σ⊥ 增长率</td></tr>
 *   <tr><td>{@code horizonSec}</td><td>1800</td><td>精算时域</td><td>预测时长</td></tr>
 * </table>
 *
 * <p><b>★ 两个键在 {@code sys_param} 中尚不存在</b>：{@code stepSec}（点间隔）与
 * {@code validitySec}（有效期）。按纪律<b>不臆造键名</b>，此二者以本类常量作缺省，
 * 待《37》§5.14.1 登记后再改为读表——届时只改本类一处。
 *
 * <p>本类只负责「取值 → 组装」，不参与任何计算；注入 {@link SysParamLoader} 是
 * {@code m2 → wl-storage} 的正常依赖（《包结构设计》§二）。
 */
@Configuration
public class PredictionConfig {

    /** 沿航道 σ 初值（AIS 位置误差）。 */
    static final String KEY_SIGMA_POS_M = "sigmaPosM";

    /** 横向 σ 初值（横向稳态标准差）。 */
    static final String KEY_SIGMA_E_M = "sigmaEM";

    /** 沿航道 σ 增长率（纵向模型误差率）。 */
    static final String KEY_ALPHA_ALONG_MS = "alphaAlongMs";

    /** 横向 σ 增长率（横向模型误差率）。 */
    static final String KEY_ALPHA_CROSS_MS = "alphaCrossMs";

    /** 预测时域（精算时域）。 */
    static final String KEY_HORIZON_SEC = "horizonSec";

    /** 缺省：预测点间隔，s —— {@code sys_param} 尚无此键（不臆造）。 */
    static final long DEFAULT_STEP_SEC = 60L;

    /** 缺省：快照有效期，s —— {@code sys_param} 尚无此键（不臆造）。 */
    static final long DEFAULT_VALIDITY_SEC = 900L;

    /**
     * 组装预测参数：五个键读 {@code sys_param}，两个未登记键用缺省。
     *
     * <p>{@code SysParamLoader} 的 {@code getDouble/getInt} 在键缺失或解析失败时返回传入的
     * 默认值，故此处无需自行判空。
     */
    @Bean
    public PredictionSettings predictionSettings(SysParamLoader sysParamLoader) {
        return new PredictionSettings(
                sysParamLoader.getDouble(KEY_SIGMA_POS_M, 20.0),
                sysParamLoader.getDouble(KEY_ALPHA_ALONG_MS, 0.05),
                sysParamLoader.getDouble(KEY_SIGMA_E_M, 15.0),
                sysParamLoader.getDouble(KEY_ALPHA_CROSS_MS, 0.03),
                sysParamLoader.getInt(KEY_HORIZON_SEC, 1800),
                DEFAULT_STEP_SEC,
                DEFAULT_VALIDITY_SEC);
    }
}
