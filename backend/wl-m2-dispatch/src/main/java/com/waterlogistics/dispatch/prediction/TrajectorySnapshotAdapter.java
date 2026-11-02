package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预测快照端口适配器（M2 · 卢肄文）——<b>薄适配层</b>，现已正式实现 M3 端口。
 *
 * <p><b>端口反转（L-7 裁决方案 A）</b>：接口由 <b>M3 声明</b>
 * （{@code rendezvous/port/TrajectorySnapshotPort}）、<b>M2 提供实现</b>、
 * <b>{@code wl-app} 装配</b>（{@code new RendezvousFacade(adapter, droneContextPort)}）
 * ——M3 全程不知道 M2 存在。本类因此是 M2 侧唯一向 M3 暴露的出口。
 *
 * <p><b>职责边界（只做转换，不做计算）</b>：
 * <ol>
 *   <li>向 {@link PredictionService} 取本模块的内部快照 {@link PredictionSnapshot}；</li>
 *   <li>投影为 M3 侧视图 {@link ShipTrackSnapshot}（<b>只裁字段、不改语义</b>，
 *       《22-接口契约》§2.1 投影纪律）。</li>
 * </ol>
 * 算法、版本号、有效期全在 {@link PredictionService} 里，本类不做任何判定。
 *
 * <p><b>投影口径（与 {@code ShipTrackSnapshot} javadoc 逐条对齐）</b>：
 * <ul>
 *   <li>{@code version}：本模块内部为 {@code long}（与契约 {@code ShipTrajFrame.version}
 *       同型、每船单调递增），M3 侧定为 {@code String} —— 此处按十进制字符串投影，
 *       <b>不引入第二套版本语义</b>；跨模块比对以字符串相等为准。</li>
 *   <li>{@code validitySec}：内部 {@code long} → M3 侧 {@code int}，<b>收窄前校验范围</b>
 *       （HIGH.25，见 {@link #toIntValiditySec}）。</li>
 *   <li>{@code points[].cog}：内部不逐点存方位，<b>按相邻预测点求大地方位</b>补齐
 *       （末点沿用前一段方位；只有一个点时回落到快照主轴 {@code courseDeg}；
 *       相邻两点重合时同样回落——HIGH.26 配套的 MEDIUM.50）。</li>
 *   <li>σ 两列原样搬运，单位米、语义为<b>标准差</b>（非方差）。</li>
 * </ul>
 *
 * <p><b>为什么不做缓存</b>：端口声明为「纯读、无状态副作用」（{@code 《81》§2.4}），
 * 且快照版本必须与调用当轮一致，缓存会引入版本漂移，故一律直取。
 *
 * <h3>异常口径（HIGH.25／HIGH.26 的核心）</h3>
 * <p>端口契约把 {@link IllegalArgumentException} 留给「{@code shipMmsi} 为空」这类<b>调用方缺陷</b>。
 * 适配器内部若把「上游数据缺陷」也抛成 {@code IllegalArgumentException}，M3 会把「预测有效期配错了」
 * 误读成「我传错了参数」——排障方向直接跑偏。故：
 * <ul>
 *   <li><b>数据缺陷</b>（有效期非正／超出 int 范围）→ {@link IllegalStateException}，消息带船号与版本；</li>
 *   <li><b>无可用预测</b>（点列为空）→ 返回 {@code null}，与「查无此船」同一条路径，
 *       M3 按既有约定抛 {@code SnapshotUnavailableException} 拒绝求解。</li>
 * </ul>
 */
@Component
public class TrajectorySnapshotAdapter implements TrajectorySnapshotPort {

    private static final Logger log = LoggerFactory.getLogger(TrajectorySnapshotAdapter.class);

    private final PredictionService predictionService;

    public TrajectorySnapshotAdapter(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 取指定船的最新预测快照（M3 端口实现）。
     *
     * @param shipMmsi 目标船标识
     * @return M3 侧快照；<b>查无此船、尚无预测、或点列为空时返回 {@code null}</b>
     *         ——这是接口约定的返回值：M3 侧判 null 后抛
     *         {@code SnapshotUnavailableException} 拒绝求解
     * @throws IllegalArgumentException 目标船标识为空或空白（调用方缺陷）
     * @throws IllegalStateException    快照存在但字段不可投影（上游数据缺陷）
     */
    @Override
    public ShipTrackSnapshot latest(String shipMmsi) {
        if (shipMmsi == null || shipMmsi.isBlank()) {
            throw new IllegalArgumentException("shipMmsi 不能为空：调用方前置校验缺失");
        }
        return predictionService.snapshotOf(shipMmsi)
                .map(TrajectorySnapshotAdapter::toShipTrackSnapshotOrNull)
                .orElse(null);
    }

    /**
     * 内部快照 → M3 侧视图；<b>点列为空时返回 {@code null}</b>（HIGH.26）。
     *
     * <p>{@code ShipTrackSnapshot} 的构造器把空点列判为非法参数。空点列在本模块是
     * <b>合法状态</b>（{@code PredictionAssurance} 只记 error 不拦匀速版，见
     * {@code PredictionService#assertStructure}），它表示「这版没有可用轨迹」。
     * 把合法状态抛成异常，等于让上游数据问题在 M3 里表现为崩溃；返回 null 则走
     * 「无预测可用」的既有分支，M3 的处置（拒绝求解）本来就是对的。
     *
     * <p>之所以还记 warn：空点列虽然"合法"，但它意味着这一拍算了个空，值得被看见。
     */
    static ShipTrackSnapshot toShipTrackSnapshotOrNull(PredictionSnapshot snapshot) {
        if (snapshot.points().isEmpty()) {
            log.warn("预测快照点列为空，按『无可用预测』返回 null：mmsi={} 版本={} t0={}",
                    snapshot.mmsi(), snapshot.version(), snapshot.t0Ms());
            return null;
        }
        return new ShipTrackSnapshot(
                Long.toString(snapshot.version()),
                snapshot.mmsi(),
                snapshot.t0Ms(),
                toIntValiditySec(snapshot),
                toShipPoints(snapshot));
    }

    /** 严格版：调用方已确认点列非空（单测与内部投影用）。 */
    static ShipTrackSnapshot toShipTrackSnapshot(PredictionSnapshot snapshot) {
        ShipTrackSnapshot projected = toShipTrackSnapshotOrNull(snapshot);
        if (projected == null) {
            throw new IllegalStateException(
                    "预测快照点列为空：mmsi=" + snapshot.mmsi() + " version=" + snapshot.version());
        }
        return projected;
    }

    /**
     * {@code long validitySec} → {@code int}，<b>收窄前显式校验</b>（HIGH.25）。
     *
     * <p>裸强转 {@code (int)} 在 2147483648 上回绕成负数，{@code ShipTrackSnapshot} 随即抛
     * {@code IllegalArgumentException("预测有效期必须为正…")}——而端口契约里这个异常的含义是
     * 「调用方传错了 shipMmsi」。运维看到的是参数错误，真实原因是上游配了个荒唐的有效期。
     *
     * <p>为什么不静默钳到 {@code Integer.MAX_VALUE}：钳位会让一个明显配错的值看起来"能用"，
     * 下游按 68 年的有效期做窗口裁剪，比直接失败更糟。
     *
     * @param snapshot 内部快照
     * @return 等价 int 值
     * @throws IllegalStateException 有效期非正或超出 int 范围（上游数据缺陷）
     */
    static int toIntValiditySec(PredictionSnapshot snapshot) {
        long validitySec = snapshot.validitySec();
        if (validitySec <= 0L || validitySec > Integer.MAX_VALUE) {
            throw new IllegalStateException("预测有效期无法投影到 M3 端口：mmsi=" + snapshot.mmsi()
                    + " version=" + snapshot.version() + " validitySec=" + validitySec
                    + "（须在 (0, " + Integer.MAX_VALUE + "] 内）");
        }
        return (int) validitySec;
    }

    private static List<ShipPoint> toShipPoints(PredictionSnapshot snapshot) {
        List<ShipTrajPoint> source = snapshot.points();
        List<ShipPoint> projected = new ArrayList<>(source.size());

        for (int i = 0; i < source.size(); i++) {
            ShipTrajPoint point = source.get(i);
            projected.add(new ShipPoint(
                    point.t(),
                    point.lon(),
                    point.lat(),
                    segmentCourseDeg(source, snapshot, i),
                    point.sigmaAlong(),
                    point.sigmaCross()));
        }
        return projected;
    }

    /**
     * 该点的航迹方位：优先取「本点 → 下一点」，末点取「上一点 → 本点」，
     * 只有一个点、或相邻两点重合时回落到快照主轴方位。
     *
     * <p><b>重合点为什么必须单列（MEDIUM.50）</b>：{@code bearingDeg} 在两点相同时是
     * {@code atan2(0, 0) = 0}，即<b>正北 0°</b>——它不是"无值"，而是一个看起来完全合法的航向。
     * 船静止或极慢时，几十个预测点在 7 位小数上完全相同，整条轨迹的 {@code cog} 会齐刷刷变成
     * 正北，M3 的会面解算会据此把一个停着的船当成向北行驶的船。回落主轴方位至少与
     * 「只有一个点」的口径一致，且不会凭空造出一个方向。
     */
    private static double segmentCourseDeg(
            List<ShipTrajPoint> points, PredictionSnapshot snapshot, int index) {
        ShipTrajPoint current = points.get(index);
        if (index + 1 < points.size()) {
            ShipTrajPoint next = points.get(index + 1);
            if (isSamePoint(current, next)) {
                return snapshot.courseDeg();
            }
            return ConstantVelocityPredictor.bearingDeg(
                    current.lon(), current.lat(), next.lon(), next.lat());
        }
        if (index > 0) {
            ShipTrajPoint previous = points.get(index - 1);
            if (isSamePoint(previous, current)) {
                return snapshot.courseDeg();
            }
            return ConstantVelocityPredictor.bearingDeg(
                    previous.lon(), previous.lat(), current.lon(), current.lat());
        }
        return snapshot.courseDeg();
    }

    /**
     * 两点是否在<b>方位无意义</b>的意义上重合。
     *
     * <p>不用严格 {@code ==}：预测点来自浮点外推，静止船的点位常带 1e-12 级的抖动，
     * 严格相等会漏判，而 {@code atan2(tiny, tiny)} 得到的方向完全由抖动决定——
     * 比齐刷刷指正北更难察觉。1e-9 度约 0.1 mm，远小于任何有意义的位移。
     */
    private static boolean isSamePoint(ShipTrajPoint a, ShipTrajPoint b) {
        return Math.abs(a.lon() - b.lon()) < COINCIDENT_EPS_DEG
                && Math.abs(a.lat() - b.lat()) < COINCIDENT_EPS_DEG;
    }

    /** 判定「同一位置」的经纬度阈值，度（≈0.1 mm）。 */
    static final double COINCIDENT_EPS_DEG = 1e-9;
}
