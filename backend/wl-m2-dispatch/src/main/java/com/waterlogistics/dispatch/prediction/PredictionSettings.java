package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.PredictionParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 预测参数入口（M2 · 卢肄文）。
 *
 * <p><b>纪律落点</b>：阈值不得硬编码（《技术栈与运行环境》§六 第 3 条），正式取值应读
 * {@code sys_param}（M6 的 {@code SysParamLoader}）。今天 {@code storage/param} 尚未落地，
 * 故此处用 {@code @Value} ＋ 缺省值作为<b>唯一</b>的可覆盖入口：
 * 业务代码只 {@link #toParams()} 取参数，绝不内联数字。
 *
 * <p>待 {@code SysParamLoader} 可用后，只改本类一处：把 {@code @Value} 换成读参数表，
 * 调用方零改动。
 *
 * <p>注意：本类<b>不写进 {@code application.yml}</b>——该文件由收口方维护且明确
 * 「只放连接与占位，不含业务参数」。
 */
@Component
public class PredictionSettings {

    private final double sigma0AlongM;
    private final double alphaAlongMs;
    private final double sigma0CrossM;
    private final double alphaCrossMs;
    private final long horizonSec;
    private final long stepSec;
    private final long validitySec;

    public PredictionSettings(
            @Value("${wavenest.prediction.sigma0-along-m:30.0}") double sigma0AlongM,
            @Value("${wavenest.prediction.alpha-along-ms:0.15}") double alphaAlongMs,
            @Value("${wavenest.prediction.sigma0-cross-m:10.0}") double sigma0CrossM,
            @Value("${wavenest.prediction.alpha-cross-ms:0.02}") double alphaCrossMs,
            @Value("${wavenest.prediction.horizon-sec:1800}") long horizonSec,
            @Value("${wavenest.prediction.step-sec:60}") long stepSec,
            @Value("${wavenest.prediction.validity-sec:900}") long validitySec) {
        this.sigma0AlongM = sigma0AlongM;
        this.alphaAlongMs = alphaAlongMs;
        this.sigma0CrossM = sigma0CrossM;
        this.alphaCrossMs = alphaCrossMs;
        this.horizonSec = horizonSec;
        this.stepSec = stepSec;
        this.validitySec = validitySec;
    }

    /** 组装算法层参数对象（算法层不读配置，只收参数）。 */
    public PredictionParams toParams() {
        return new PredictionParams(
                sigma0AlongM, alphaAlongMs, sigma0CrossM, alphaCrossMs,
                horizonSec, stepSec, validitySec);
    }
}
