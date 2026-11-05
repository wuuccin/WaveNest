package com.waterlogistics.dispatch.prediction;

import com.waterlogistics.contract.ws.ShipTrajPoint;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 预测看台查询服务（阶段二 1，M2 侧）。
 *
 * <p><b>只读、只做投影</b>：取数唯一来源是 {@link PredictionService#latestSnapshots()} 与
 * {@link PredictionService#snapshotOf(String)}，不新造缓存、不改预测链路。
 * 本类不做业务判断，只把 {@link PredictionSnapshot} 裁成看台需要的形状：
 * <ul>
 *   <li>{@link #ships(int, int)}：在航船摘要（MMSI／版本／premise／courseDeg／预测点数／有效期剩余），分页；</li>
 *   <li>{@link #detail(String)}：单船完整点列（含 {@code ShipTrajPoint} 四要素与 σ）。</li>
 * </ul>
 *
 * <p><b>分页口径</b>（与 {@code MonitorController} 一致，前端可直接复用）：
 * {@code page} 从 1 起（小于 1 按 1 处理）、{@code size} 默认
 * {@value #DEFAULT_PAGE_SIZE}、上限 {@value #MAX_PAGE_SIZE}（超出按上限截断）。
 * 响应里回带归一化后的 {@code page}/{@code size}，前端不必自行猜测。
 *
 * <p><b>有效期剩余</b>：{@code validitySec - (now - t0Ms)/1000}，截断到 0（不为负）。
 * 预测过期只影响展示（“剩余 0 s”），是否重算由 {@code PredictionService} 决定，本类不触发重算。
 */
@Service
public class PredictionQueryService {

    /** 分页默认条数（对齐 MonitorController 口径）。 */
    static final int DEFAULT_PAGE_SIZE = 50;

    /** 分页上限（防止一次拉全量点列拖垮前端）。 */
    static final int MAX_PAGE_SIZE = 200;

    private final PredictionService predictionService;

    public PredictionQueryService(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 在航船摘要分页。
     *
     * @param page 页码，从 1 起；小于 1 按 1 处理
     * @param size 每页条数；≤0 取默认 50，超过 200 截断为 200
     * @return 摘要列表 + 总数（总数是在航船全量条数，不是本页条数）
     */
    public ShipsResponse ships(int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        List<PredictionSnapshot> all = predictionService.latestSnapshots();
        long nowMs = System.currentTimeMillis();

        int from = Math.min(all.size(), (normalizedPage - 1) * normalizedSize);
        int to = Math.min(all.size(), from + normalizedSize);
        List<PredictionShipSummary> summaries = all.subList(from, to).stream()
                .map(snapshot -> new PredictionShipSummary(
                        snapshot.mmsi(),
                        snapshot.version(),
                        snapshot.premise(),
                        snapshot.courseDeg(),
                        snapshot.points().size(),
                        remainingValiditySec(snapshot, nowMs)))
                .toList();

        return new ShipsResponse(summaries, all.size(), normalizedPage, normalizedSize);
    }

    /**
     * 单船完整预测点列。
     *
     * @param mmsi 船舶 MMSI
     * @return 快照投影；该船尚无预测时为空（由控制层映射 404）
     */
    public Optional<PredictionDetail> detail(String mmsi) {
        return predictionService.snapshotOf(mmsi).map(snapshot -> new PredictionDetail(
                snapshot.mmsi(),
                snapshot.version(),
                snapshot.t0Ms(),
                snapshot.courseDeg(),
                snapshot.validitySec(),
                snapshot.premise(),
                snapshot.points()));
    }

    /** 有效期剩余秒数，截断到 0。 */
    static long remainingValiditySec(PredictionSnapshot snapshot, long nowMs) {
        long elapsedSec = (nowMs - snapshot.t0Ms()) / 1000L;
        return Math.max(0L, snapshot.validitySec() - elapsedSec);
    }

    /**
     * 在航船摘要（看台列表行）。
     *
     * @param mmsi               船舶 MMSI（看台按船聚合的主键）
     * @param version            轨迹版本号，判新旧只看本字段
     * @param premise            前提有效性（VALID／INVALID／UNKNOWN）
     * @param courseDeg          主轴方位，度
     * @param pointCount         预测点数（含 τ=0 当前点）
     * @param validityRemainingSec 有效期剩余秒数（≥0）
     */
    public record PredictionShipSummary(
            String mmsi,
            long version,
            String premise,
            double courseDeg,
            int pointCount,
            long validityRemainingSec) {
    }

    /**
     * 在航船摘要列表响应。
     *
     * @param ships 本页摘要（按 MMSI 升序）
     * @param total 在航船总数（全量，不是本页条数）
     * @param page  归一化后的页码
     * @param size  归一化后的每页条数
     */
    public record ShipsResponse(
            List<PredictionShipSummary> ships,
            int total,
            int page,
            int size) {

        public ShipsResponse {
            ships = List.copyOf(ships);
        }
    }

    /**
     * 单船完整预测点列。
     *
     * <p>{@code points} 与 WS 帧 {@code ship_traj} 同源同值，是同一 {@link PredictionSnapshot}
     * 的完整点列（帧只取子集，缺精算字段在本响应中同样不补默认值）。
     *
     * @param mmsi       船舶 MMSI
     * @param version    轨迹版本号
     * @param t0Ms       预测起始时刻，Unix 毫秒
     * @param courseDeg  主轴方位，度
     * @param validitySec 有效期，s
     * @param premise    前提有效性
     * @param points     预测点列（含 τ=0 当前点）
     */
    public record PredictionDetail(
            String mmsi,
            long version,
            long t0Ms,
            double courseDeg,
            long validitySec,
            String premise,
            List<ShipTrajPoint> points) {

        public PredictionDetail {
            points = List.copyOf(points);
        }
    }
}
