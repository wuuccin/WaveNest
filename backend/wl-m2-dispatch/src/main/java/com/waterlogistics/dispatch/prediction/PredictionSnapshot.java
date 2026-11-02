package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.PremiseStatus;
import com.waterlogistics.contract.ws.ShipTrajFrame;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.List;

/**
 * 船舶预测快照——<b>算法层完整对象</b>，模块层对外交付的完整形态（M2 · 卢肄文）。
 *
 * <p><b>与 WS 帧的关系（重要）</b>：{@code ShipTrajFrame} 是本快照的<b>子集</b>，
 * 只面向大屏渲染；契约 javadoc 已声明帧缺 {@code zSeries}／{@code deviation}／
 * {@code enterTimes}／{@code branchId}／{@code calibrationFactor} 五项，
 * 并规定「E 组功能 17 的 NIS／σ 缩放判据须回算法层取完整对象，<b>不得从帧反推</b>」。
 * 本类即那个「完整对象」，也是 M3 经端口取数的载荷。
 *
 * <p><b>不确定性口径</b>：{@link ShipTrajPoint} 内的 σ 是<b>标准差</b>，椭圆半轴为 2σ
 * （契约 {@code semiMajorM() = 2 * sigmaAlong}），需要协方差时按
 * {@code Cov = R(ψ)·diag(σ∥², σ⊥²)·R(ψ)ᵀ}、{@code ψ = courseDeg} 重建。
 *
 * <p><b>★ 今天是简化版</b>：{@code zSeries}／{@code deviationM}／{@code enterTimesMs}／
 * {@code branchId}／{@code calibrationFactor} 五项——由精算版（滤波新息序列、预测—实测偏差、
 * 会面窗口、分支标识、标定因子）填充。11-02 起 {@code calibrationFactor} 与 {@code deviationM}
 * 由 {@link PredictionCalibrationService} 回填（样本不足量时仍为 {@code null}，见该类注释），
 * 其余三项待算法侧排期。
 *
 * @param mmsi              船舶 MMSI
 * @param version           轨迹版本号，<b>每船单调递增</b>（判新旧只看本字段，不靠时间戳推测）
 * @param t0Ms              预测起始时刻，Unix 毫秒
 * @param courseDeg         主轴方位（椭圆旋转角），度
 * @param validitySec       有效期，s
 * @param premise           前提有效性，取 {@code PremiseStatus.code()}（VALID／INVALID／UNKNOWN）
 * @param points            预测点列，含 τ=0 的当前点
 * @param zSeries           新息序列（精算版）
 * @param deviationM        预测—实测偏差（校准链路回填）
 * @param enterTimesMs      会面窗口进入时刻列表（精算版）
 * @param branchId          分支标识（精算版）
 * @param calibrationFactor 标定因子（样本不足 200 或区间宽 > 0.15 时为 {@code null}＝未标定）
 */
public record PredictionSnapshot(
        String mmsi,
        long version,
        long t0Ms,
        double courseDeg,
        long validitySec,
        String premise,
        List<ShipTrajPoint> points,
        List<Double> zSeries,
        Double deviationM,
        List<Long> enterTimesMs,
        String branchId,
        Double calibrationFactor) {

    /**
     * 结构校验 ＋ 防御性拷贝（评审 MEDIUM.49 / MEDIUM.11）。
     *
     * <p><b>为什么在这里拦而不是在 {@code toFrame()}</b>：本记录的不可变承诺是给
     * M3 端口、看台与 WS 推送三方共用的。等到 {@code toFrame()} 才校验，坏值已经在
     * {@code snapshotOf()} 里被下游拿去算过一轮了；构造期失败，故障点才指向「谁造出了坏快照」。
     *
     * <p><b>为什么 {@code zSeries}／{@code enterTimesMs} 要条件拷贝</b>：它们可空，
     * 但不代表可以共享引用——{@code preciseSnapshot} 直接把引擎的 {@code outcome.zSeries()}
     * 塞进来，引擎若复用同一个列表，调用方一次 {@code add} 就能改掉已发布版本的内新息，
     * 而下游按版本号判新旧的口径对此毫无察觉。{@code null} 与空集合语义不同
     * （「无新息」vs「有新息但为空」），故不能用 {@code List.copyOf(null)} 一律抹平。
     *
     * <p>{@code premise} 只收 {@link PremiseStatus} 的三个编码：本字段会一路透传到
     * WS 帧与大屏着色，出现第四种值时前端只能静默按「未知」处理，等于把脏值藏起来。
     */
    public PredictionSnapshot {
        if (mmsi == null || mmsi.isBlank()) {
            throw new IllegalArgumentException("mmsi 不能为空：快照须可归属到船");
        }
        if (points == null) {
            throw new IllegalArgumentException("points 不得为 null（无预测应返回空快照而非 null 列表）");
        }
        if (validitySec < 0L) {
            throw new IllegalArgumentException("有效期不得为负：" + validitySec + " s（mmsi=" + mmsi + "）");
        }
        PremiseStatus.fromCode(premise);
        points = List.copyOf(points);
        zSeries = zSeries == null ? null : List.copyOf(zSeries);
        enterTimesMs = enterTimesMs == null ? null : List.copyOf(enterTimesMs);
    }

    /**
     * 投影为对外 WS 帧 {@code ship_traj}——<b>只取子集</b>，不补任何默认值。
     *
     * <p>{@code premise} 已在本快照中编码为字符串，与帧的 {@code String premise} 直接对应。
     *
     * <p><b>HIGH.24</b>：帧里必须带 {@code mmsi}。此前帧只有版本号与点列，大屏同时跟多船时
     * 无法把一帧归到哪条船，推送信封也不带船标识，故补在帧契约上（见 {@code ShipTrajFrame}）。
     *
     * <p><b>MEDIUM.11 发布前校验</b>：空点列不发帧。空帧到前端表现为「这条船突然没轨迹」，
     * 与「这条船不该显示」无法区分；不发帧则前端保留上一帧，语义明确。构造期已保证
     * 各字段合法，这里只拦「合法但无意义」的空点列。
     *
     * @return WS 帧
     * @throws IllegalStateException 点列为空（无内容可发）
     */
    public ShipTrajFrame toFrame() {
        if (points.isEmpty()) {
            throw new IllegalStateException(
                    "空点列不发帧：mmsi=" + mmsi + " version=" + version);
        }
        return new ShipTrajFrame(mmsi, version, t0Ms, courseDeg, validitySec, premise, points);
    }

    /**
     * 依据有效期判断是否已过期（下游取数前应校验，过期须先刷新）。
     *
     * <p><b>两处修正（LOW.20）</b>：
     * <ol>
     *   <li>原始写法 {@code nowMs - t0Ms > validitySec * 1000L} 在 {@code t0Ms} 超前
     *       （AIS 时基与调用方时钟有偏差）时得到负的 elapsed，快照会被判「永远新鲜」；
     *       改为正向表达 {@code nowMs > t0Ms + ttl}，负 elapsed 自然判未过期，无需额外分支。</li>
     *   <li>{@code validitySec * 1000L} 在 {@code validitySec} 接近 {@code Long.MAX_VALUE/1000}
     *       时溢出为负，同样会翻成「未过期」。用 {@link Math#multiplyExact} 兜住并饱和到
     *       {@link Long#MAX_VALUE}——溢出时「过期」才是安全侧。</li>
     * </ol>
     * 该方法已不再是无引用的死代码：{@code AdmissionInputAssembler} 用它筛掉过期快照，
     * {@link PredictionService#evictStale} 用它清理失联船。
     */
    public boolean isStale(long nowMs) {
        if (validitySec < 0L) {
            // 构造期已拒，这里只兜住反序列化等绕过构造器的路径
            return true;
        }
        long ttlMs;
        try {
            ttlMs = Math.multiplyExact(validitySec, 1000L);
        } catch (ArithmeticException overflow) {
            ttlMs = Long.MAX_VALUE;
        }
        long deadline;
        try {
            deadline = Math.addExact(t0Ms, ttlMs);
        } catch (ArithmeticException overflow) {
            deadline = Long.MAX_VALUE;
        }
        return nowMs > deadline;
    }
}
