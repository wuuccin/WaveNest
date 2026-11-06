package com.waterlogistics.algo.estimation;

/**
 * 航道中心线的<b>弯道几何</b>——局部曲率与折线近似的横向弦高（A 组 · estimation）。
 *
 * <h3>为什么要有这个类</h3>
 * <p>横向 OU 的稳态标准差 {@code σ_e} 在 11-06 之前是一个<b>全航段固定</b>的常数
 * （{@code sys_param.sigmaEM = 15 m}），语义是「单侧航道内正常横向摆动」。这个口径在
 * 直线航段成立，在弯道航段<b>系统性偏小</b>：
 *
 * <ol>
 *   <li><b>参考线是折线，真实河道是曲线</b>。折线在一段长 {@code Λ} 的弦上代替一段半径
 *       {@code R = 1/κ} 的圆弧，两者的最大横向偏差就是该弦的弦高
 *       {@code h = Λ²·κ/8}。预测沿折线推进，因此在弯道内预测点相对真实中心线必然带着
 *       这个量级的横向偏差——它是<b>确定性几何量</b>，不是噪声，也不是拍出来的系数。</li>
 *   <li>带宽度被这个偏差低估的后果不是"数值不好看"，而是 {@code sigmaCrossM} 与
 *       {@code wTolM} 的比较失效：{@link MultiStepPredictor#validitySec} 会把一条实际
 *       已经压岸的预测判成"仍然可信"，下游（会面点求解、ETA）照单全收。</li>
 * </ol>
 *
 * <h3>曲率怎么算（折线的固有难点）</h3>
 * <p>折线在段内曲率恒为 0、在顶点处曲率是脉冲（转角/零弧长），两者都不能直接进公式。
 * 本类取<b>窗口平均</b>：在 {@code [s − Λ/2, s + Λ/2]} 两端各取一次航道走向，转角除以窗宽。
 * 于是顶点处的脉冲被摊到长度为 {@code Λ} 的一段上，得到该处等效曲率
 * {@code κ = Δψ/Λ}，与圆弧的 {@code κ = 1/R} 同量纲、同含义。
 *
 * <p><b>不做二次光滑</b>（高斯核、样条等）：折线本身没有顶点之外的曲率信息，平滑只会造出
 * 「看起来连续」的假精度，并让参数调试失去可解释性。窗口平均的代价是 {@code κ(s)} 在
 * {@code s = s_vertex ± Λ/2} 处跳变，这是<b>已知且可接受</b>的：σ 用途上差一次跳变，
 * 远小于把弯道当直线处理带来的系统偏差。
 *
 * <h3>窗宽 Λ 为什么取 {@value #BEND_WINDOW_M} m</h3>
 * <p>Λ 是「一段折线能表现出的最短弯」的尺度：窗宽小于点距时窗口里可能一个顶点都没有，
 * 曲率恒为 0（漏检）；远大于点距时整条航段的弯被平摊成一个很小的 {@code κ}（漏判）。
 * 赣江定稿航段的折线点距约 500 m（朝阳—八一 5.78 km／12 段），取同量级即可覆盖单个弯
 * 而不吞掉相邻弯。台账换成更密的折线后，该窗宽仍然可用——{@code Δψ} 与点距无关，
 * 只与这一窗内的总转角有关。
 *
 * <p>本类<b>纯计算</b>：无 Spring、无 DB、无 MQ。
 */
public final class CenterlineBend {

    /**
     * 弯道识别窗宽 {@code Λ}，m（取值理由见类注释「窗宽 Λ 为什么取 500 m」）。
     */
    public static final double BEND_WINDOW_M = 500.0;

    private CenterlineBend() {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 弧长 {@code s} 处的<b>等效曲率</b>，1/m（恒非负）。
     *
     * <p>{@code κ = |Δψ| / Λ}，其中 {@code Δψ} 是 {@code [s − Λ/2, s + Λ/2]} 两端的航道走向差
     * （取最短有向角，处理 {@code 359°→1°} 的环绕）。区间端点越界时夹到
     * {@code [0, 中心线总长]}——端点外没有航道，重采样无意义。
     *
     * <p><b>双向航道不受影响</b>：{@link WaterwayGeometry#courseDegAt} 给的是中心线固定正向的
     * 走向，与船的实际航行方向无关；{@code Δψ} 取的是两端之差，上行船与下行船得到同一个曲率。
     * 曲率是几何属性，不该跟着船的方向翻符号。
     *
     * @param geometry 航道中心线
     * @param s        沿航道弧长，m
     * @return 等效曲率，1/m；{@code geometry} 为 {@code null} 时返回 0
     */
    public static double curvatureRadPerM(WaterwayGeometry geometry, double s) {
        return curvatureRadPerM(geometry, s, BEND_WINDOW_M);
    }

    /**
     * 指定窗宽的等效曲率（供单测与不同点距的台账用）。
     *
     * @param geometry 航道中心线
     * @param s        沿航道弧长，m
     * @param windowM  窗宽 {@code Λ}，m（须为正）
     * @return 等效曲率，1/m
     * @throws IllegalArgumentException 窗宽非正
     */
    public static double curvatureRadPerM(WaterwayGeometry geometry, double s, double windowM) {
        if (!(windowM > 0.0) || !Double.isFinite(windowM)) {
            throw new IllegalArgumentException("弯道窗宽必须为正：" + windowM);
        }
        if (geometry == null) {
            return 0.0;
        }
        double total = geometry.totalLengthM();
        double from = Math.max(0.0, Math.min(total, s - windowM / 2.0));
        double to = Math.max(0.0, Math.min(total, s + windowM / 2.0));
        double spanM = to - from;
        if (!(spanM > 0.0)) {
            // 中心线总长为 0 或窗口完全落在端点外：没有可比的走向
            return 0.0;
        }
        double fromDeg = geometry.courseDegAt(from);
        double toDeg = geometry.courseDegAt(to);
        double deltaRad = Math.toRadians(AlongTrackKalmanFilter.shortestAngleDiffDeg(toDeg, fromDeg));
        return Math.abs(deltaRad) / spanM;
    }

    /**
     * 折线代替圆弧在该处的<b>横向弦高</b> {@code h = Λ²·κ/8}，m。
     *
     * <p>推导：半径 {@code R} 的圆弧上张角 {@code Δψ = Λ/R}、弦长 {@code Λ}，弦到弧的最大距离
     * （弦高）为 {@code R·(1 − cos(Δψ/2))}，小角展开即 {@code Λ²/(8R) = Λ²·κ/8}。赣江航段
     * 单个弯约 11°，该项相对误差约 0.1%，无须保留高阶项。
     *
     * @param geometry 航道中心线
     * @param s        沿航道弧长，m
     * @return 弦高，m（直线段为 0）
     */
    public static double sagittaM(WaterwayGeometry geometry, double s) {
        double curvature = curvatureRadPerM(geometry, s);
        if (!(curvature > 0.0)) {
            return 0.0;
        }
        return curvature * BEND_WINDOW_M * BEND_WINDOW_M / 8.0;
    }

    /**
     * 沿预测用的横向稳态标准差 {@code σ_e}：在观测口径 {@code σ_e0} 上并入弯道弦高。
     *
     * <pre>
     *   σ_e(s) = min( sqrt(σ_e0² + h(s)²), W/2 )
     * </pre>
     *
     * <p><b>为什么是平方和而不是直接相加</b>：{@code σ_e0} 是「正常横向摆动」（观测统计量），
     * {@code h} 是「参考线几何失真」（确定性几何量），二者来源独立、无固定相位关系，
     * 按独立量合成（平方和开方）是保守且可解释的；直接相加在 {@code h ≪ σ_e0} 时会虚增带宽，
     * 在 {@code h ≫ σ_e0} 时也不改结论。
     *
     * <p><b>为什么夹到 {@code W/2}</b>：与 {@code MultiStepPredictor} 里 {@code σ_cross} 的截断
     * 同口径——半宽是横向不确定度的物理上界，弯道不许突破它（{@code 80} §3.2.6
     * 「带宽度有天然的物理上界」）。
     *
     * @param sigmaE0     观测口径的横向稳态标准差，m（{@code sys_param.sigmaEM}）
     * @param halfWidthM  航道半宽 {@code W/2}，m
     * @param geometry    航道中心线
     * @param s           沿航道弧长，m
     * @return 预测用的横向稳态标准差，m
     */
    public static double sigmaEPredictionM(double sigmaE0, double halfWidthM,
            WaterwayGeometry geometry, double s) {
        double h = sagittaM(geometry, s);
        double combined = h > 0.0 ? Math.hypot(sigmaE0, h) : sigmaE0;
        if (halfWidthM > 0.0) {
            combined = Math.min(combined, halfWidthM);
        }
        return combined;
    }

    /**
     * 把有效曲率换算成「转弯半径」，m（仅用于日志与报告，不进任何计算）。
     *
     * @param curvatureRadPerM 等效曲率，1/m
     * @return 转弯半径，m；曲率为 0（直线段）时为 {@link Double#POSITIVE_INFINITY}
     */
    public static double radiusM(double curvatureRadPerM) {
        if (!(curvatureRadPerM > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        return 1.0 / curvatureRadPerM;
    }
}
