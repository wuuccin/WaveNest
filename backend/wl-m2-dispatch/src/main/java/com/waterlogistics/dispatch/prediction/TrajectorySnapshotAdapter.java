package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.algo.estimation.ConstantVelocityPredictor;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>{@code validitySec}：内部 {@code long} → M3 侧 {@code int}（演示期取值远小于 int 上限）。</li>
 *   <li>{@code points[].cog}：内部不逐点存方位，<b>按相邻预测点求大地方位</b>补齐
 *       （末点沿用前一段方位；只有一个点时回落到快照主轴 {@code courseDeg}）。</li>
 *   <li>σ 两列原样搬运，单位米、语义为<b>标准差</b>（非方差）。</li>
 * </ul>
 *
 * <p><b>为什么不做缓存</b>：端口声明为「纯读、无状态副作用」（{@code 《81》§2.4}），
 * 且快照版本必须与调用当轮一致，缓存会引入版本漂移，故一律直取。
 */
@Component
public class TrajectorySnapshotAdapter implements TrajectorySnapshotPort {

    private final PredictionService predictionService;

    public TrajectorySnapshotAdapter(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 取指定船的最新预测快照（M3 端口实现）。
     *
     * @param shipMmsi 目标船标识
     * @return M3 侧快照；<b>查无此船或尚无预测时返回 {@code null}</b>
     *         ——这是接口约定的返回值：M3 侧判 null 后抛
     *         {@code SnapshotUnavailableException} 拒绝求解
     * @throws IllegalArgumentException 目标船标识为空或空白（调用方缺陷）
     */
    @Override
    public ShipTrackSnapshot latest(String shipMmsi) {
        if (shipMmsi == null || shipMmsi.isBlank()) {
            throw new IllegalArgumentException("shipMmsi 不能为空：调用方前置校验缺失");
        }
        return predictionService.snapshotOf(shipMmsi)
                .map(TrajectorySnapshotAdapter::toShipTrackSnapshot)
                .orElse(null);
    }

    /** 内部快照 → M3 侧视图（包级可见，便于单测直接验证投影）。 */
    static ShipTrackSnapshot toShipTrackSnapshot(PredictionSnapshot snapshot) {
        return new ShipTrackSnapshot(
                Long.toString(snapshot.version()),
                snapshot.mmsi(),
                snapshot.t0Ms(),
                (int) snapshot.validitySec(),
                toShipPoints(snapshot));
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
     * 只有一个点时回落到快照主轴方位。
     */
    private static double segmentCourseDeg(
            List<ShipTrajPoint> points, PredictionSnapshot snapshot, int index) {
        ShipTrajPoint current = points.get(index);
        if (index + 1 < points.size()) {
            ShipTrajPoint next = points.get(index + 1);
            return ConstantVelocityPredictor.bearingDeg(
                    current.lon(), current.lat(), next.lon(), next.lat());
        }
        if (index > 0) {
            ShipTrajPoint previous = points.get(index - 1);
            return ConstantVelocityPredictor.bearingDeg(
                    previous.lon(), previous.lat(), current.lon(), current.lat());
        }
        return snapshot.courseDeg();
    }
}
