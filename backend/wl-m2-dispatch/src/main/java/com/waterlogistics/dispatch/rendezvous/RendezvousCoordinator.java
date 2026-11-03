package com.waterlogistics.dispatch.rendezvous;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.dispatch.weather.WeatherSnapshotPort;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 动态会面协调器（M2 · 吴可欣，2026-10-17 新增）——<b>M2 侧唯一调用 M3 门面的地方</b>。
 *
 * <p><b>为什么需要这一层</b>：M3 的门面只提供算法能力，
 * 但<b>求解所需的输入有一半在 M2 手里</b>——船侧预测快照（M2 常驻层）、
 * 执行机台账（{@code drone}／{@code nest} 表）、求解阈值（{@code sys_param}）、
 * 代表风况（气象快照仓中最近的新鲜观测，见 {@link WeatherSnapshotPort}）。
 * 这些拼接逻辑若散落在调度主链各处，会重复构造命令、重复取快照，
 * 且<b>版本一致性锚（{@code snapshotVersion}）很容易被各调用点搞乱</b>。
 * 故集中到本类，调度链只调一个方法、拿一个 {@link MeetPlan}。
 *
 * <p><b>纪律（《42-包结构设计》§二铁律 2）</b>：M2 → M3 只经 {@link RendezvousPort}，
 * 由 {@code wl-app} 的实现转调 M3 门面；<b>不得</b>引用 M3 的 solver／generator 等任何具体实现类
 * （{@code RendezvousCommand} 与 {@code B_*} 等<b>契约对象</b>属例外，
 * 它们是门面方法的入参出参，不构成反向依赖）。
 *
 * <p><b>红线</b>：本类<b>只算不落库</b>。会面点与起飞时刻由调用方
 * （{@code RescheduleService}）决定何时写入 {@code order_task}，
 * 求解层不碰 Mapper、不改占位（《33》§4.1「量判分离、两笔账不混」）。
 */
@Service
public class RendezvousCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RendezvousCoordinator.class);

    /** 求解时限 T_max：最晚会面时刻相对当前的多远（秒） */
    private static final String HORIZON_SEC_KEY = "rendezvousHorizonSec";

    /** 会面时间窗余量（秒），窗口＝[t*, t*＋余量] */
    private static final String WINDOW_MARGIN_SEC_KEY = "rendezvousWindowMarginSec";

    /** 不动点迭代收敛阈值 ε_t（秒） */
    private static final String EPSILON_SEC_KEY = "rendezvousEpsilonSec";

    /** 不动点迭代最大次数 */
    private static final String MAX_ITER_KEY = "rendezvousMaxIter";

    /** 代表风况：风速 m/s（无气象快照时取缺省 0，即不修正有效速度） */
    private static final String WIND_SPEED_KEY = "windSpeedMs";

    /** 代表风况：风向（度） */
    private static final String WIND_DIR_DEG_KEY = "windDirDeg";

    /** 交接时长下界（秒），用于推算交接完成位置与末段伴飞速度 */
    private static final String HANDOVER_SEC_KEY = "tHandoverMinSec";

    private static final int DEFAULT_HORIZON_SEC = 1800;
    private static final int DEFAULT_WINDOW_MARGIN_SEC = 120;
    private static final double DEFAULT_EPSILON_SEC = 1.0;
    private static final int DEFAULT_MAX_ITER = 50;
    private static final int DEFAULT_HANDOVER_SEC = 120;

    /** 库内时间统一按东八区解释，不随运行环境的默认时区漂移。 */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final RendezvousPort rendezvousPort;
    private final DroneContextPort droneContextPort;
    private final TrajectorySnapshotPort snapshotPort;
    private final SysParamLoader sysParamLoader;
    private final WeatherSnapshotPort weatherSnapshotPort;

    public RendezvousCoordinator(RendezvousPort rendezvousPort,
            DroneContextPort droneContextPort, TrajectorySnapshotPort snapshotPort,
            SysParamLoader sysParamLoader, WeatherSnapshotPort weatherSnapshotPort) {
        this.rendezvousPort = rendezvousPort;
        this.droneContextPort = droneContextPort;
        this.snapshotPort = snapshotPort;
        this.sysParamLoader = sysParamLoader;
        this.weatherSnapshotPort = weatherSnapshotPort;
    }

    /**
     * 会面方案——求解结果投影，<b>单位与时区已在本层统一</b>，
     * 调用方不必再碰 Unix 毫秒。
     *
     * @param meetLon 会面点经度
     * @param meetLat 会面点纬度
     * @param meetTime 会面时刻（组合级回填与航迹起点推算的依据）
     * @param solutionId 算法层解标识（留痕用）
     * @param snapshotVersion 求解所用快照版本（与 M2 常驻层一致，便于事后核对）
     * @param nestLon 执行机归属机巢经度
     * @param nestLat 执行机归属机巢纬度
     * @param droneSpeedMs 执行机巡航速度
     * @param handoverEndLon 交接完成位置经度
     * @param handoverEndLat 交接完成位置纬度
     * @param terminalSpeedMs 末段伴飞速度
     */
    public record MeetPlan(double meetLon, double meetLat, LocalDateTime meetTime,
            String solutionId, String snapshotVersion,
            double nestLon, double nestLat, double droneSpeedMs,
            double handoverEndLon, double handoverEndLat, double terminalSpeedMs) {
    }

    /**
     * 为某订单＋某候选组合求会面点（M2 第 3 步 · 判「追得上」，《22-接口契约》§5）。
     *
     * @param order   目标订单（取 {@code shipMmsi}）
     * @param comboId 候选组合标识（会面点为组合级产物）
     * @param droneId 执行机（用于反查机巢起点与机型速度）
     * @return 会面方案
     * @throws IllegalStateException 台账查不到／快照缺失／求解无解——<b>三种情况都不得静默放行</b>：
     *         无解若按「now＋固定缓冲」派发，等于用一个假的会面时刻把货送出去，
     *         与 {@code TaskQueueService#dispatch} 的真实回填语义冲突。
     */
    public MeetPlan solveFor(OrderTaskEntity order, String comboId, String droneId) {
        DroneContext drone = droneContextPort.resolve(droneId);
        if (drone == null) {
            throw new IllegalStateException("执行机 " + droneId + " 台账或归属机巢查无，会面求解无从起步");
        }

        String shipMmsi = order.getShipMmsi();
        ShipTrackSnapshot snapshot = snapshotPort.latest(shipMmsi);
        if (snapshot == null) {
            throw new IllegalStateException("船舶 " + shipMmsi + " 无预测快照，会面求解拒绝："
                    + "快照经 TrajectorySnapshotPort 自取，查无即视为数据未就绪，不得用船位反推");
        }

        long nowMs = System.currentTimeMillis();
        RendezvousCommand command = new RendezvousCommand(
                comboId,
                shipMmsi,
                snapshot.version(),
                drone.nestLon(),
                drone.nestLat(),
                drone.cruiseSpeedMs(),
                representativeWind(drone.nestLon(), drone.nestLat(), nowMs),
                nowMs,
                nowMs + sysParamLoader.getInt(HORIZON_SEC_KEY, DEFAULT_HORIZON_SEC) * 1000L,
                sysParamLoader.getInt(WINDOW_MARGIN_SEC_KEY, DEFAULT_WINDOW_MARGIN_SEC),
                sysParamLoader.getDouble(EPSILON_SEC_KEY, DEFAULT_EPSILON_SEC),
                sysParamLoader.getInt(MAX_ITER_KEY, DEFAULT_MAX_ITER));

        B_RendezvousSolution solution = rendezvousPort.solve(command);
        if (!solution.hasSolution()) {
            throw new IllegalStateException("组合 " + comboId + " 会面求解无解，原因="
                    + solution.noSolutionReason() + "：按《22》§5 第 3 步判「追不上」，不得派发");
        }
        long meetMs = solution.meetTMin();
        int handoverSec = Math.max(1, sysParamLoader.getInt(HANDOVER_SEC_KEY, DEFAULT_HANDOVER_SEC));
        ShipPoint handoverPoint = pointAt(snapshot, meetMs + handoverSec * 1000L);
        double handoverEndLon = handoverPoint.lon();
        double handoverEndLat = handoverPoint.lat();
        double handoverDistance = GeoApprox.distanceM(
                solution.meetLon(), solution.meetLat(), handoverEndLon, handoverEndLat);
        if (handoverDistance < 1.0) {
            double[] offset = offsetMeters(solution.meetLon(), solution.meetLat(),
                    handoverPoint.cog(), 1.0);
            handoverEndLon = offset[0];
            handoverEndLat = offset[1];
            handoverDistance = 1.0;
        }
        double terminalSpeedMs = Math.max(0.1, Math.min(100.0, handoverDistance / handoverSec));
        return new MeetPlan(
                solution.meetLon(),
                solution.meetLat(),
                toLocalDateTime(meetMs),
                solution.solutionId(),
                solution.snapshotVersion(),
                drone.nestLon(),
                drone.nestLat(),
                drone.cruiseSpeedMs(),
                handoverEndLon,
                handoverEndLat,
                terminalSpeedMs);    }

    /**
     * 代表风况。
     *
     * <p><b>取数口径</b>：按机巢位置与当前时刻取气象快照仓中最近的新鲜观测（风速、风向）；
     * 无观测、过期或数据不可用时回落 {@code sys_param} 的静态风况（缺省 0 m/s，
     * 即不修正有效速度）。气象链路异常不得中断调度主链。
     */
    private Wind representativeWind(double lon, double lat, long nowMs) {
        WeatherSnapshotPort.LatestWind latest;
        try {
            latest = weatherSnapshotPort.latest(lon, lat, nowMs);
        } catch (RuntimeException ex) {
            log.warn("气象快照取数失败，回落系统参数静态风况", ex);
            latest = null;
        }
        if (latest != null) {
            double speed = sanitizeSpeed(latest.speedMs(),
                    sysParamLoader.getDouble(WIND_SPEED_KEY, 0.0));
            double direction = sanitizeDirection(latest.dirDeg(),
                    sysParamLoader.getDouble(WIND_DIR_DEG_KEY, 0.0));
            return new Wind(speed, direction, 0.0);
        }
        double fallbackSpeed = sanitizeSpeed(sysParamLoader.getDouble(WIND_SPEED_KEY, 0.0), 0.0);
        double fallbackDirection = sanitizeDirection(sysParamLoader.getDouble(WIND_DIR_DEG_KEY, 0.0), 0.0);
        return new Wind(fallbackSpeed, fallbackDirection, 0.0);
    }

    private static double sanitizeSpeed(double speed, double fallback) {
        if (!Double.isFinite(speed) || speed < 0.0) {
            return Double.isFinite(fallback) && fallback >= 0.0 ? fallback : 0.0;
        }
        return speed;
    }

    private static double sanitizeDirection(double direction, double fallback) {
        if (!Double.isFinite(direction)) {
            return Double.isFinite(fallback) ? fallback % 360.0 : 0.0;
        }
        return direction % 360.0;
    }

    /**
     * 取预测轨迹上指定时刻的位置：落在两点之间时按时间线性插值，超出首末点时取端点。
     */
    private static ShipPoint pointAt(ShipTrackSnapshot snapshot, long targetMs) {
        List<ShipPoint> points = snapshot.points();
        ShipPoint first = points.get(0);
        if (targetMs <= first.t()) {
            return first;
        }
        ShipPoint last = points.get(points.size() - 1);
        if (targetMs >= last.t()) {
            return last;
        }
        for (int i = 1; i < points.size(); i++) {
            ShipPoint right = points.get(i);
            if (targetMs <= right.t()) {
                ShipPoint left = points.get(i - 1);
                double span = (double) (right.t() - left.t());
                double ratio = span <= 0.0 ? 0.0 : (double) (targetMs - left.t()) / span;
                return new ShipPoint(targetMs,
                        left.lon() + ratio * (right.lon() - left.lon()),
                        left.lat() + ratio * (right.lat() - left.lat()),
                        right.cog(), right.sigmaAlong(), right.sigmaCross());
            }
        }
        return last;
    }

    /** 沿指定方位推进若干米，返回新的经纬度。 */
    private static double[] offsetMeters(double lon, double lat, double bearingDeg, double meters) {
        double rad = Math.toRadians(bearingDeg);
        double dLat = meters * Math.cos(rad) / 111_320.0;
        double dLon = meters * Math.sin(rad)
                / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{lon + dLon, lat + dLat};
    }

    /** Unix 毫秒 → 本地时间（库内统一东八区，与 {@code application.yml} 的 TimeZone 一致）。 */
    private LocalDateTime toLocalDateTime(Long epochMilli) {
        if (epochMilli == null) {
            throw new IllegalStateException("求解结果缺会面时刻，无解判据与字段表不一致");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DB_ZONE);
    }
}