package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.algo.estimation.WaterwayGeometry;

/**
 * 预测实现的装配结果（M2 · 卢肄文 · 10-23 F4）：<b>模式 ＋ 断言网 ＋ 精算引擎</b>三件套。
 *
 * <p>为什么打成一个对象而不是给 {@link PredictionService} 加三个构造参数：三者必须<b>同时</b>
 * 成立才有意义（切到精算却没有断言网，等于清单说的「闭着眼睛改数值」），打包后装配处只能
 * 整体给，不会漏配一个。
 *
 * @param mode      实现选择
 * @param assurance 输出区间断言网
 * @param engine    精算引擎（{@link PredictionMode#CONSTANT} 时可为 {@code null}）
 */
public record PredictionProfile(
        PredictionMode mode,
        PredictionAssurance assurance,
        PrecisePredictionEngine engine) {

    public PredictionProfile {
        if (mode == null || assurance == null) {
            throw new IllegalArgumentException("模式与断言网不得为 null");
        }
        if (mode == PredictionMode.PRECISE && engine == null) {
            throw new IllegalArgumentException("精算模式必须给引擎，否则切了也是空转");
        }
    }

    /**
     * 匀速版画像：既是缺省，也是 F4 的回退姿态（断言网只留结构判定）。
     */
    public static PredictionProfile constant() {
        return new PredictionProfile(PredictionMode.CONSTANT,
                new PredictionAssurance(PredictionAssurance.Bounds.unbounded()), null);
    }

    /**
     * 精算版画像。
     *
     * @param settings  预测参数（σ 初值、α、时域、步长）
     * @param assurance 断言网（含赣江地理框）
     * @param params    引擎参数（附录 B 初值）
     * @return 画像
     */
    public static PredictionProfile precise(PredictionSettings settings, PredictionAssurance assurance,
            PrecisePredictionEngine.Params params) {
        return precise(settings, assurance, params, null);
    }

    /**
     * 精算版画像（带航道中心线）。
     *
     * @param settings   预测参数（σ 初值、α、时域、步长）
     * @param assurance  断言网（含赣江地理框）
     * @param params     引擎参数（附录 B 初值）
     * @param centerline 航道中心线；为空时沿用实测窗口建参考线的退化口径
     * @return 画像
     */
    public static PredictionProfile precise(PredictionSettings settings, PredictionAssurance assurance,
            PrecisePredictionEngine.Params params, WaterwayGeometry centerline) {
        return precise(settings, assurance, params, centerline, SpeedLimitLookup.unbounded());
    }

    /**
     * 精算版画像（带航道中心线与分段限速）。
     *
     * @param settings   预测参数（σ 初值、α、时域、步长）
     * @param assurance  断言网（含赣江地理框）
     * @param params     引擎参数（附录 B 初值）
     * @param centerline 航道中心线；为空时沿用实测窗口建参考线的退化口径
     * @param speedLimit 航段分段限速查询；为 {@code null} 时恒不限速
     * @return 画像
     */
    public static PredictionProfile precise(PredictionSettings settings, PredictionAssurance assurance,
            PrecisePredictionEngine.Params params, WaterwayGeometry centerline,
            SpeedLimitLookup speedLimit) {
        return new PredictionProfile(PredictionMode.PRECISE, assurance,
                new PrecisePredictionEngine(settings, params, centerline, speedLimit));
    }
}
