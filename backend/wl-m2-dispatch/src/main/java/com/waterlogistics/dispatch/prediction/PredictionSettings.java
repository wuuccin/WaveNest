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

    /**
     * 装配期校验（MEDIUM.48）：<b>复用算法层的不变式，不自立一套</b>。
     *
     * <p><b>为什么必须在这里验</b>：此前本记录体不收任何约束，坏值（{@code sigmaPosM=0}、
     * {@code alpha} 为负、{@code horizon < step}）能安静地过 Spring 装配，直到第一笔预测请求
     * 调 {@link #toParams()} 才抛——错误现场离病根隔着一次启动和一次业务请求，
     * 排障时要先把「是不是配置坏了」排除掉才能看算法。放在紧凑构造器里，配置一坏启动即失败。
     *
     * <p><b>为什么不直接调 {@link #toParams()}</b>：紧凑构造器执行时字段还没赋值，
     * 那时调 {@code toParams()} 读到的是全 0。故按同样顺序把入参传给
     * {@link PredictionParams} 的构造器，让它替我们判——这是<b>唯一</b>的规则来源，
     * 算法层改了判据这里自动跟上。
     *
     * <p><b>与 {@link PredictionConfig} 的关系</b>：配置侧已对 {@code sys_param} 的坏值做
     * 「回落缺省＋warn」，这里不是重复劳动——那里的兜底只覆盖「读表读出来的值」，
     * 单测与其它构造点（如 {@code OfflinePredictionFixture}）直接 {@code new} 时没有第二道网。
     */
    public PredictionSettings {
        new PredictionParams(sigma0AlongM, alphaAlongMs, sigma0CrossM, alphaCrossMs,
                horizonSec, stepSec, validitySec);
    }

    /** 组装算法层参数对象（算法层不读配置，只收参数）。 */
    public PredictionParams toParams() {
        return new PredictionParams(
                sigma0AlongM, alphaAlongMs, sigma0CrossM, alphaCrossMs,
                horizonSec, stepSec, validitySec);
    }
}
