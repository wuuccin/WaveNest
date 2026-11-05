package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.MultiStepPredictor.SpeedLimitLookup;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import java.util.function.DoubleSupplier;

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
        return precise(settings, assurance, params, centerline, speedLimit, null);
    }

    /**
     * 精算版画像（带中心线、分段限速与速度偏置取值口）。
     *
     * <p><b>为什么偏置单独一个重载而不是塞进 {@code Params}</b>：{@code Params} 是启动时定值，
     * 而偏置会被校准器在运行期回写（77 §8 G1）。塞进 {@code Params} 就等于「启动那一刻读一次」，
     * 回写永远不生效——闭环会断在同一个地方，只是这次不报错、也没人看得出来。
     *
     * @param settings         预测参数（σ 初值、α、时域、步长）
     * @param assurance        断言网（含赣江地理框）
     * @param params           引擎参数（附录 B 初值）
     * @param centerline       航道中心线；为空时沿用实测窗口建参考线
     * @param speedLimit       航段分段限速查询；为 {@code null} 时恒不限速
     * @param alongSpeedBiasMs 沿航道速度偏置取值口；为 {@code null} 时恒 0
     * @return 画像
     */
    public static PredictionProfile precise(PredictionSettings settings, PredictionAssurance assurance,
            PrecisePredictionEngine.Params params, WaterwayGeometry centerline,
            SpeedLimitLookup speedLimit, DoubleSupplier alongSpeedBiasMs) {
        return new PredictionProfile(PredictionMode.PRECISE, assurance,
                new PrecisePredictionEngine(settings, params, centerline, speedLimit, alongSpeedBiasMs));
    }
}
