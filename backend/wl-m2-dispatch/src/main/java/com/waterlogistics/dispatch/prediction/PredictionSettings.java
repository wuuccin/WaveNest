package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.PredictionParams;

/**
 * 预测参数载体（M2 · 卢肄文）——<b>纯数据，不含 Spring 注解</b>。
 *
 * <p>为什么刻意不标注 {@code @Component}：算法链路要能<b>脱离 Spring 与数据库独立单测</b>
 * （《技术栈与运行环境》§2.3 第 3 条）。本类只做不可变载体，「从哪来」由
 * {@link PredictionConfig} 决定；单测直接 {@code new PredictionSettings(...)} 即可。
 *
 * <p><b>纪律落点</b>：阈值不得硬编码、不得臆造键名。正式取值由 {@link PredictionConfig}
 * 从 {@code sys_param}（《37》§5.14.1）读出后注入；本类内部<b>不做任何默认值兜底</b>
 * ——缺省值只在 {@link PredictionConfig} 一处出现，便于将来统一改为读表。
 *
 * @param sigma0AlongM 沿航道方向 1σ 初值，m（<b>标准差，不是方差</b>）
 * @param alphaAlongMs 沿航道方向 1σ 增长率，m/s
 * @param sigma0CrossM 横向 1σ 初值，m
 * @param alphaCrossMs 横向 1σ 增长率，m/s
 * @param horizonSec   预测时长，s
 * @param stepSec      相邻预测点间隔，s
 * @param validitySec  快照有效期，s
 */
public record PredictionSettings(
        double sigma0AlongM,
        double alphaAlongMs,
        double sigma0CrossM,
        double alphaCrossMs,
        long horizonSec,
        long stepSec,
        long validitySec) {

    /** 组装算法层参数对象（算法层不读配置，只收参数）。 */
    public PredictionParams toParams() {
        return new PredictionParams(
                sigma0AlongM, alphaAlongMs, sigma0CrossM, alphaCrossMs,
                horizonSec, stepSec, validitySec);
    }
}
