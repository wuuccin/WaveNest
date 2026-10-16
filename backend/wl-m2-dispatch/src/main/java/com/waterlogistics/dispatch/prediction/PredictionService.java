package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.PredictionResult;
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
import org.springframework.stereotype.Service;

/**
 * 船舶状态估计与轨迹预测服务（M2 · 卢肄文 · 模块二功能 2）。
 *
 * <p><b>一套实现两种触发</b>（《20-系统架构》§2 硬约束 3）：本服务同时服务
 * 「常驻滚动预测」与「接单精算预测」两种触发——两者共用同一算法实现，
 * 本类只负责编排（《包结构设计》§二 铁律 5：<b>禁止两套代码</b>）。
 *
 * <p><b>今天是简化版</b>：算法委托给 {@link ConstantVelocityPredictor}（匀速外推＋固定噪声带）。
 * 精算版（卡尔曼／OU／协方差传播／前提监测）后续只替换算法实现，本类签名不变。
 *
 * <p>状态保存于进程内 {@link ConcurrentHashMap}（每船一份最新快照 ＋ 一个单调递增版本号）。
 * <b>今天不落库</b>：分工表明确「除张睿哲外其他人可以不落库」；Redis／持久化由 M6 承接。
 */
@Service
public class PredictionService {

    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);

    private final PredictionSettings settings;

    /** 每船最新快照。key = mmsi。 */
    private final Map<String, PredictionSnapshot> latestSnapshots = new ConcurrentHashMap<>();

    /**
     * 每船版本号计数器。
     *
     * <p>契约把 {@code version} 定为 {@code long} 且「判新旧用版本字段、不靠时间戳推测」，
     * 故这里用<b>单调递增计数器</b>而非哈希串：天然可比较、无碰撞。
     */
    private final Map<String, AtomicLong> versionCounters = new ConcurrentHashMap<>();

    public PredictionService(PredictionSettings settings) {
        this.settings = settings;
    }

    /**
     * 收到一条船舶观测后刷新该船预测快照——<b>常驻层与接单精算共用的唯一入口</b>。
     *
     * @param message 解析后的 AIS 船位（{@code sog} 为节、{@code cog} 为度，见契约 javadoc）
     * @return 刷新后的快照（版本号已 +1）
     * @throws IllegalArgumentException 观测越界时抛出（由调用方决定丢弃或告警）
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

        latestSnapshots.put(message.mmsi(), snapshot);

        log.debug("预测刷新：mmsi={} version={} points={} 有效期={}s 前提={}",
                snapshot.mmsi(), snapshot.version(), snapshot.points().size(),
                snapshot.validitySec(), snapshot.premise());

        return snapshot;
    }

    /** 取某船最新快照（无则空）。 */
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

    private long nextVersion(String mmsi) {
        return versionCounters.computeIfAbsent(mmsi, key -> new AtomicLong()).incrementAndGet();
    }

    /** 算法层输出点 → WS 帧点：字段语义一致（σ 均为标准差，单位 m）。 */
    private static ShipTrajPoint toFramePoint(EstimatedPoint point) {
        return new ShipTrajPoint(
                point.tMs(), point.lon(), point.lat(), point.sigmaAlongM(), point.sigmaCrossM());
    }
}
