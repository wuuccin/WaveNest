package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.PredictionResult;
import com.waterlogistics.algo.inflight.HeadingSample;
import com.waterlogistics.algo.inflight.IntentChangeJudge;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 船舶状态估计与轨迹预测服务（M2 · 卢肄文 · 模块二功能 2）——预测刷新驱动链的<b>中段</b>。
 *
 * <p><b>一套实现两种触发</b>（《20-系统架构》§2 硬约束 3）：本服务同时服务「常驻滚动预测」
 * 与「接单精算预测」，两者共用同一算法实现，本类只做编排
 * （《包结构设计》§二 铁律 5：<b>禁止两套代码</b>）。
 *
 * <p><b>驱动链（《10-16 当日任务速览》§3.3）</b>：
 * <pre>
 *   新状态(data.ais) → 本类 refresh → ① 快照更新（供 M3 端口取用）
 *                                   → ② 航向跳变超阈 ⇒ 发重算请求（解锁 6a）
 *                                   → ③ 配成 HeadingSample（供 E 组 6b 判据）
 * </pre>
 *
 * <p><b>今天仍是简化版</b>：算法委托 {@link ConstantVelocityPredictor}（匀速外推＋固定噪声带）。
 * 精算版（卡尔曼／OU／协方差传播／前提监测）只替换算法实现，本类签名不变。
 *
 * <p>状态存于进程内 {@link ConcurrentHashMap}（每船一份最新快照 ＋ 单调递增版本号）。
 * 分水岭：<b>快照本身不落库</b>（Redis／持久化由 M6 承接）；但调度结果要落
 * {@code task_log}（见 {@code dispatch.record.TaskLogRecorder}）。
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    /**
     * 触发重算请求的<b>航向跳变阈值</b>（度）。
     *
     * <p>物理含义与 E 组 6b 判据的 Θ_th 同源（都判「船是否改成向」），故直接引用其公开常量，
     * 避免代码里出现两个各自为政的「20°」。{@code sys_param} 目前无此键
     * （E 组 26 项亦无 camelCase 键名），按纪律<b>不臆造键名</b>；待《37》§5.14.1 登记后改读表。
     */
    public static final double RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG =
            IntentChangeJudge.SUGGESTED_THETA_TH_DEG;

    /** 触发原因文案（追溯用，进 {@link RecomputeRequest}）。 */
    static final String REASON_COURSE_JUMP = "预测主轴方位跳变超阈（简化版变向判据）";

    private final PredictionSettings settings;

    private final RecomputeRequester recomputeRequester;

    private final ApplicationEventPublisher eventPublisher;

    /** 每船最新快照。key = mmsi。 */
    private final Map<String, PredictionSnapshot> latestSnapshots = new ConcurrentHashMap<>();

    /**
     * 每船版本号计数器。
     *
     * <p>契约把 {@code version} 定为 {@code long} 且「判新旧用版本字段、不靠时间戳推测」，
     * 故这里用<b>单调递增计数器</b>而非哈希串：天然可比较、无碰撞。
     */
    private final Map<String, AtomicLong> versionCounters = new ConcurrentHashMap<>();

    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester) {
        this(settings, recomputeRequester, event -> { });
    }

    @Autowired
    public PredictionService(PredictionSettings settings, RecomputeRequester recomputeRequester,
            ApplicationEventPublisher eventPublisher) {
        this.settings = settings;
        this.recomputeRequester = recomputeRequester;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 收到一条船舶观测后刷新该船预测快照——<b>常驻层与接单精算共用的唯一入口</b>。
     *
     * <p>刷新成功后顺带判定「预测前提是否已实质改变」：与上一版主轴方位比较，
     * 变化达到 {@link #RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG} 即发一次重算请求
     * （首帧不算跳变，没有可比的上一版）。
     *
     * @param message 解析后的 AIS 船位（{@code sog} 为<b>节</b>、{@code cog} 为度，见契约 javadoc）
     * @return 刷新后的快照（版本号已 +1）
     * @throws IllegalArgumentException 观测越界时抛出（由调用方决定丢弃或告警；
     *         抛出时<b>不写快照、不递增版本</b>，本条观测视为未发生）
     */
    public PredictionSnapshot refresh(AisMessage message) {
        // 单位归一：AisMessage 保持源单位（节），算法层统一 SI（m/s）
        double sogMs = ConstantVelocityPredictor.knotsToMs(message.sog());

        PredictionResult result = ConstantVelocityPredictor.predict(
                message.mmsi(),
                message.lon(),
                message.lat(),
                sogMs,
                message.cog(),
                message.ts(),
                settings.toParams());

        long version = nextVersion(message.mmsi());

        List<ShipTrajPoint> points = result.points().stream()
                .map(PredictionService::toFramePoint)
                .toList();

        PredictionSnapshot snapshot = new PredictionSnapshot(
                result.mmsi(),
                version,
                result.t0Ms(),
                result.courseDeg(),
                result.validitySec(),
                result.premise().code(),
                points,
                // 以下五项属精算版，今天一律占位为空（字段先立，避免将来改签名）
                null, null, null, null, null);

        PredictionSnapshot previous = latestSnapshots.put(message.mmsi(), snapshot);

        log.debug("预测刷新：mmsi={} version={} points={} 有效期={}s 前提={}",
                snapshot.mmsi(), snapshot.version(), snapshot.points().size(),
                snapshot.validitySec(), snapshot.premise());

        maybeRequestRecompute(previous, snapshot);
        eventPublisher.publishEvent(new PredictionUpdatedEvent(snapshot.toFrame()));

        return snapshot;
    }

    /** 取某船最新快照（无则空）。M3 端口与本模块消费方都走这里。 */
    public Optional<PredictionSnapshot> snapshotOf(String mmsi) {
        return Optional.ofNullable(latestSnapshots.get(mmsi));
    }

    /** 取全部在跟踪船舶的最新快照，按 MMSI 排序，便于稳定展示与联调比对。 */
    public List<PredictionSnapshot> latestSnapshots() {
        return latestSnapshots.values().stream()
                .sorted(Comparator.comparing(PredictionSnapshot::mmsi))
                .toList();
    }

    /** 当前在跟踪的船数。 */
    public int trackedShipCount() {
        return latestSnapshots.size();
    }

    /**
     * 把一条<b>实测</b> AIS 观测配成 E 组 6b 判据所需的样本（解锁吴可欣的闭环枢纽）。
     *
     * <p>{@code HeadingSample} 要的四个量中，{@code predictedTangentDeg}（预测航迹切向）
     * 只能由本模块给出——{@code algo.inflight.HeadingSample} 的 javadoc 明写
     * 「预测切向最终来自 A 组 {@code PredictionSnapshot}，且必须经 M2 常驻层取用完整对象，
     * <b>不得从 WS 帧 {@code ship_traj} 反推</b>」。本方法即那个「常驻层取用」的唯一出口。
     *
     * <p>切向取「最接近实测时刻的那个预测点 → 其下一点」的大地方位；
     * 末点沿用前一段方位；只有一个点时回落主轴方位。
     *
     * @param measured 实测船位（同时被 {@link #refresh} 刷新过快照时配对才有意义）
     * @return 配对样本；该船尚无快照时为空（调用方跳过本拍即可）
     */
    public Optional<HeadingSample> toHeadingSample(AisMessage measured) {
        return snapshotOf(measured.mmsi())
                .map(snapshot -> new HeadingSample(
                        measured.cog(),
                        tangentDegAt(snapshot, measured.ts()),
                        ConstantVelocityPredictor.knotsToMs(measured.sog()),
                        measured.ts()));
    }

    /** 快照在指定时刻的航迹切向（度，正北 0、顺时针）。 */
    static double tangentDegAt(PredictionSnapshot snapshot, long tsMs) {
        List<ShipTrajPoint> points = snapshot.points();
        if (points.isEmpty()) {
            return snapshot.courseDeg();
        }

        int nearest = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long distance = Math.abs(points.get(i).t() - tsMs);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = i;
            }
        }

        if (nearest + 1 < points.size()) {
            ShipTrajPoint from = points.get(nearest);
            ShipTrajPoint to = points.get(nearest + 1);
            return ConstantVelocityPredictor.bearingDeg(from.lon(), from.lat(), to.lon(), to.lat());
        }
        if (nearest > 0) {
            ShipTrajPoint from = points.get(nearest - 1);
            ShipTrajPoint to = points.get(nearest);
            return ConstantVelocityPredictor.bearingDeg(from.lon(), from.lat(), to.lon(), to.lat());
        }
        return snapshot.courseDeg();
    }

    /**
     * 两角之差取最短弧，返回 {@code [0, 180]} 度。
     *
     * <p>必须取最短弧：航向 350° → 10° 实际只转了 20°，直接相减会得到 340° 的假跳变。
     */
    static double shortestArcDeg(double fromDeg, double toDeg) {
        double raw = Math.abs(
                ConstantVelocityPredictor.normalizeDeg(toDeg)
                        - ConstantVelocityPredictor.normalizeDeg(fromDeg));
        raw = raw % 360.0;
        return raw > 180.0 ? 360.0 - raw : raw;
    }

    private void maybeRequestRecompute(PredictionSnapshot previous, PredictionSnapshot current) {
        if (previous == null) {
            // 首帧：没有可比的上一版，不算跳变，避免刚上线就刷一串重算请求
            return;
        }

        double deltaDeg = shortestArcDeg(previous.courseDeg(), current.courseDeg());
        if (deltaDeg < RECOMPUTE_COURSE_DELTA_THRESHOLD_DEG) {
            return;
        }

        recomputeRequester.requestRecompute(new RecomputeRequest(
                current.mmsi(),
                current.version(),
                previous.courseDeg(),
                current.courseDeg(),
                deltaDeg,
                REASON_COURSE_JUMP,
                current.t0Ms()));
    }

    private long nextVersion(String mmsi) {
        return versionCounters.computeIfAbsent(mmsi, key -> new AtomicLong()).incrementAndGet();
    }

    /** 算法层输出点 → WS 帧点：字段语义一致（σ 均为标准差，单位 m）。 */
    private static ShipTrajPoint toFramePoint(EstimatedPoint point) {
        return new ShipTrajPoint(
                point.tMs(), point.lon(), point.lat(), point.sigmaAlongM(), point.sigmaCrossM());
    }
}
