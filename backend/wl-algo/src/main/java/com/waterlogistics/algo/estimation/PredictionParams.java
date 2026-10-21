package com.waterlogistics.algo.estimation;

/**
 * 船舶轨迹预测参数（A 组 · estimation 子包）。
 *
 * <p><b>本类只承载参数，不承载阈值</b>：取值一律由调用方注入（模块层从 {@code sys_param}
 * 读出后传入），算法内部不得硬编码（《包结构设计》§二 铁律 4）。
 * {@link #defaults()} 仅供本地冒烟与单测；正式取值由<b>模块层从 {@code sys_param}</b>
 * （《37》§5.14.1）读出后注入，算法层不读配置、不认键名。
 *
 * <p>单位一律 SI：长度 m、时间 s。
 *
 * @param sigma0AlongM 沿航道方向 1σ 初值，m（<b>标准差，不是方差</b>）
 * @param alphaAlongMs 沿航道方向 1σ 增长率，m/s
 * @param sigma0CrossM 横向（垂直航道）1σ 初值，m（<b>标准差，不是方差</b>）
 * @param alphaCrossMs 横向 1σ 增长率，m/s
 * @param horizonSec   预测时长，s
 * @param stepSec      相邻预测点间隔，s
 * @param validitySec  快照有效期，s；超过即视为过期，须重新刷新
 */
public record PredictionParams(
        double sigma0AlongM,
        double alphaAlongMs,
        double sigma0CrossM,
        double alphaCrossMs,
        long horizonSec,
        long stepSec,
        long validitySec) {

    public PredictionParams {
        if (!(sigma0AlongM > 0.0) || !(sigma0CrossM > 0.0)) {
            throw new IllegalArgumentException("σ 初值必须为正（标准差）：sigma0AlongM="
                    + sigma0AlongM + ", sigma0CrossM=" + sigma0CrossM);
        }
        if (alphaAlongMs < 0.0 || alphaCrossMs < 0.0) {
            throw new IllegalArgumentException("σ 增长率不得为负：alphaAlongMs="
                    + alphaAlongMs + ", alphaCrossMs=" + alphaCrossMs);
        }
        if (horizonSec <= 0 || stepSec <= 0 || validitySec <= 0) {
            throw new IllegalArgumentException("horizonSec/stepSec/validitySec 必须为正："
                    + horizonSec + "/" + stepSec + "/" + validitySec);
        }
        if (stepSec > horizonSec) {
            throw new IllegalArgumentException("stepSec 不得大于 horizonSec：" + stepSec + " > " + horizonSec);
        }
    }

    /**
     * 演示期缺省参数（量级对齐 {@code 80-A组算法方案} 附录 B 的口径）。
     *
     * <p>沿航道 σ 由 30 m 起、按 0.15 m/s 增长；横向由 10 m 起、按 0.02 m/s 增长；
     * 预测 1800 s、每 60 s 一点（共 31 点）、有效期 900 s。
     */
    public static PredictionParams defaults() {
        return new PredictionParams(30.0, 0.15, 10.0, 0.02, 1800L, 60L, 900L);
    }

    /** 预测点个数：{@code horizonSec / stepSec + 1}（含 τ=0 的当前点）。 */
    public int pointCount() {
        return (int) (horizonSec / stepSec) + 1;
    }
}
