package com.waterlogistics.dispatch.rendezvous;

import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.dispatch.weather.WeatherSnapshotPort;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/**
 * 动态会面协调器（M2 · 吴可欣，2026-10-17 新增）——<b>M2 侧唯一调用 M3 门面的地方</b>。
 *
 * <p><b>为什么需要这一层</b>：M3 的 {@link RendezvousFacade} 只提供算法能力，
 * 但<b>求解所需的输入有一半在 M2 手里</b>——船侧预测快照（M2 常驻层）、
 * 执行机台账（{@code drone}／{@code nest} 表）、求解阈值（{@code sys_param}）、
 * 代表风况（气象快照仓中最近的新鲜观测，见 {@link WeatherSnapshotPort}）。
 * 这些拼接逻辑若散落在调度主链各处，会重复构造命令、重复取快照，
 * 且<b>版本一致性锚（{@code snapshotVersion}）很容易被各调用点搞乱</b>。
 * 故集中到本类，调度链只调一个方法、拿一个 {@link MeetPlan}。
 *
 * <p><b>纪律（《42-包结构设计》§二铁律 2）</b>：M2 → M3 只调 {@link RendezvousFacade}，
 * 本类是唯一入口；<b>不得</b>引用 M3 的 solver／generator 等任何具体实现类
 * （{@code RendezvousCommand} 与 {@code B_*} 等<b>契约对象</b>属例外，
 * 它们是门面方法的入参出参，不构成反向依赖）。
 *
 * <p><b>红线</b>：本类<b>只算不落库</b>。会面点与起飞时刻由调用方
 * （{@code RescheduleService}）决定何时写入 {@code order_task}，
 * 求解层不碰 Mapper、不改占位（《33》§4.1「量判分离、两笔账不混」）。
 */
@Service
public class RendezvousCoordinator {

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

    private static final int DEFAULT_HORIZON_SEC = 1800;
    private static final int DEFAULT_WINDOW_MARGIN_SEC = 120;
    private static final double DEFAULT_EPSILON_SEC = 1.0;
    private static final int DEFAULT_MAX_ITER = 50;

    private final RendezvousFacade rendezvousFacade;
    private final DroneContextPort droneContextPort;
    private final TrajectorySnapshotPort snapshotPort;
    private final SysParamLoader sysParamLoader;
    private final WeatherSnapshotPort weatherSnapshotPort;

    public RendezvousCoordinator(RendezvousFacade rendezvousFacade,
            DroneContextPort droneContextPort, TrajectorySnapshotPort snapshotPort,
            SysParamLoader sysParamLoader, WeatherSnapshotPort weatherSnapshotPort) {
        this.rendezvousFacade = rendezvousFacade;
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
     * @param takeoffTime 起飞时刻（＝会面时刻最早上界，组合级回填用）
     * @param solutionId 算法层解标识（留痕用）
     * @param snapshotVersion 求解所用快照版本（与 M2 常驻层一致，便于事后核对）
     */
    public record MeetPlan(double meetLon, double meetLat, LocalDateTime takeoffTime,
            String solutionId, String snapshotVersion) {
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

        B_RendezvousSolution solution = rendezvousFacade.solve(command);
        if (!solution.hasSolution()) {
            throw new IllegalStateException("组合 " + comboId + " 会面求解无解，原因="
                    + solution.noSolutionReason() + "：按《22》§5 第 3 步判「追不上」，不得派发");
        }
        return new MeetPlan(
                solution.meetLon(),
                solution.meetLat(),
                toLocalDateTime(solution.meetTMin()),
                solution.solutionId(),
                solution.snapshotVersion());
    }

    /**
     * 代表风况。
     *
     * <p><b>取数口径</b>：按机巢位置与当前时刻取气象快照仓中最近的新鲜观测（风速、风向）；
     * 无观测、过期或数据不可用时回落 {@code sys_param} 的静态风况（缺省 0 m/s，
     * 即不修正有效速度）。气象链路异常不得中断调度主链。
     */
    private Wind representativeWind(double lon, double lat, long nowMs) {
        WeatherSnapshotPort.LatestWind latest = weatherSnapshotPort.latest(lon, lat, nowMs);
        if (latest != null) {
            return new Wind(latest.speedMs(), latest.dirDeg(), 0.0);
        }
        return new Wind(
                sysParamLoader.getDouble(WIND_SPEED_KEY, 0.0),
                sysParamLoader.getDouble(WIND_DIR_DEG_KEY, 0.0),
                0.0);
    }

    /** Unix 毫秒 → 本地时间（演示期统一东八区，与 {@code application.yml} 的 TimeZone 一致）。 */
    private LocalDateTime toLocalDateTime(Long epochMilli) {
        if (epochMilli == null) {
            throw new IllegalStateException("求解结果缺会面时刻，无解判据与字段表不一致");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.of("Asia/Shanghai"));
    }
}