package com.waterlogistics.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.contract.mq.AisMessage;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.order.CargoReadyService;
import com.waterlogistics.dispatch.order.OrderAdmissionService;
import com.waterlogistics.dispatch.order.OrderSubmitRequest;
import com.waterlogistics.dispatch.order.OrderSubmitResponse;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.queue.CargoGateWaker;
import com.waterlogistics.dispatch.rendezvous.RendezvousPort;
import com.waterlogistics.dispatch.resource.OccupancySlotService;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.port.TrajectorySnapshotPort;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.repository.OrderTaskMapper;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only dynamic/static policy comparison through admission, candidate selection,
 * rendezvous, dispatch and persisted order state. Metrics use the same synthetic
 * post-dispatch truth model in both arms; energy is a distance proxy, not telemetry.
 */
@SpringBootTest(properties = {"wavenest.m2.flight-advance-enabled=false", "wavenest.sim.enabled=false",
        // 货到巢关的后台轮询会与断言抢同一批订单，本类自己按需驱动放行
        "wavenest.m2.cargo-gate-poll-enabled=false"})
@Transactional
class DispatchPerturbationComparisonTest {
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final double SHIP_LON = 115.857937;
    private static final double SHIP_LAT = 28.663776;
    private static final double METERS_PER_LON = GeoApprox.METERS_PER_DEG
            * Math.cos(Math.toRadians(SHIP_LAT));
    private static final double BATTERY_RANGE_M = 30_000.0;
    private static final String[] TYPES = {"speed-step", "heading-jump", "ais-gap", "wind-change"};
    private static final String[] LEVELS = {"light", "medium", "heavy"};

    @Autowired private OrderAdmissionService admissionService;
    @Autowired private PredictionService predictionService;
    @Autowired private OrderTaskMapper orderTaskMapper;
    @Autowired private DroneMapper droneMapper;
    @Autowired private NestMapper nestMapper;
    @Autowired private SwitchingPort switchingPort;
    @Autowired private CargoReadyService cargoReadyService;
    @Autowired private CargoGateWaker cargoGateWaker;
    @Autowired private OccupancySlotService occupancySlotService;

    @Test
    void compareTwelveDispatchedPairs() {
        System.out.println("scenario,level,mode,orderId,droneId,meetingErrorM,shipWaitS,energyProxyPct");
        int compared = 0;
        for (int type = 0; type < TYPES.length; type++) {
            for (int level = 0; level < LEVELS.length; level++) {
                Metrics dynamic = dispatch(type, level, true, compared * 2);
                Metrics baseline = dispatch(type, level, false, compared * 2 + 1);
                System.out.printf(Locale.ROOT, "%s,%s,delta,-,-,%.2f,%.2f,%.2f%n",
                        TYPES[type], LEVELS[level], dynamic.errorM() - baseline.errorM(),
                        dynamic.waitS() - baseline.waitS(), dynamic.energyPct() - baseline.energyPct());
                compared++;
            }
        }
        assertThat(compared).isEqualTo(12);
    }

    private Metrics dispatch(int type, int level, boolean dynamic, int ordinal) {
        String mmsi = String.format(Locale.ROOT, "8%08d", 70_000_000 + ordinal);
        predictionService.refresh(new AisMessage(mmsi, "对照船", SHIP_LON, SHIP_LAT,
                5.0, 90.0, System.currentTimeMillis()));
        switchingPort.dynamic.set(dynamic);
        long startMs = System.currentTimeMillis();
        OrderSubmitResponse response = admissionService.submit(new OrderSubmitRequest(
                mmsi, 2.7, "食品", null, "URGENT"));
        assertThat(response.accepted()).isTrue();
        OrderTaskEntity order = orderTaskMapper.selectById(response.orderId());
        assertThat(order).isNotNull();
        // 系统下单时刻选定货巢 → 货物建行为「未就绪」→ 登记到巢并由轮询放行后才派发。
        // 本类在同一事务内连下 24 单，而巢时隙锁只在订单终态释放，须逐单释放，
        // 否则第 5 单起锁不到巢时隙转「等资源」，而等资源不会被门关轮询自动重试。
        if (order.getCargoNestId() != null && !order.getCargoNestId().isBlank()) {
            cargoReadyService.markReady(response.orderId(), order.getCargoNestId());
            cargoGateWaker.tick();
            occupancySlotService.releaseByOrder(response.orderId());
            order = orderTaskMapper.selectById(response.orderId());
        }
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DISPATCHED.getValue());
        assertThat(order.getDroneId()).isNotBlank();
        assertThat(order.getMeetLon()).isNotNull();
        assertThat(order.getMeetLat()).isNotNull();
        assertThat(order.getMeetTime()).isNotNull();

        DroneEntity drone = droneMapper.selectById(order.getDroneId());
        NestEntity nest = nestMapper.selectById(drone.getCurrentNestId());
        double nestLon = nest.getLon().doubleValue();
        double nestLat = nest.getLat().doubleValue();
        double meetLon = order.getMeetLon().doubleValue();
        double meetLat = order.getMeetLat().doubleValue();
        double distance = GeoApprox.distanceM(nestLon, nestLat, meetLon, meetLat);
        double speed = drone.getCruiseSpeedMs() == null ? 12.0 : drone.getCruiseSpeedMs().doubleValue();
        double wind = type == 3 ? 1.5 * (level + 1) : 0.0;
        double arrivalSec = distance / Math.max(1.0, speed - wind);
        double meetSec = (order.getMeetTime().atZone(DB_ZONE).toInstant().toEpochMilli() - startMs) / 1000.0;
        double actualSec = Math.max(0, Math.max(arrivalSec, meetSec));
        double actualEast = truthEast(actualSec, type, level);
        double actualNorth = truthNorth(actualSec, type, level);
        double actualLon = SHIP_LON + actualEast / METERS_PER_LON;
        double actualLat = SHIP_LAT + actualNorth / GeoApprox.METERS_PER_DEG;
        double error = GeoApprox.distanceM(meetLon, meetLat, actualLon, actualLat);
        double shipArrivalSec = nearestShipTime(meetLon, meetLat, type, level);
        double wait = Math.max(0, actualSec - shipArrivalSec);
        double energy = 100.0 * 2.0 * distance / BATTERY_RANGE_M;
        assertThat(Double.isFinite(error) && Double.isFinite(wait) && Double.isFinite(energy)).isTrue();
        System.out.printf(Locale.ROOT, "%s,%s,%s,%s,%s,%.2f,%.2f,%.2f%n",
                TYPES[type], LEVELS[level], dynamic ? "dynamic" : "static", response.orderId(),
                order.getDroneId(), error, wait, energy);
        // The next arm needs an idle drone; the transaction rolls this fixture reset back.
        drone.setStatus("空闲");
        droneMapper.updateById(drone);
        return new Metrics(error, wait, energy);
    }

    private static double truthEast(double sec, int type, int level) {
        double east = 2.5722 * sec; // 5 knots converted to metres per second
        if (sec >= 60) {
            double affected = sec - 60;
            if (type == 0) east += 0.8 * (level + 1) * affected;
            if (type == 1) east = 2.5722 * 60 + 2.5722 * affected
                    * Math.cos(Math.toRadians(20.0 * (level + 1)));
            if (type == 2) east += 0.35 * (level + 1) * affected;
        }
        return east;
    }

    private static double truthNorth(double sec, int type, int level) {
        return type == 1 && sec >= 60 ? 2.5722 * (sec - 60)
                * Math.sin(Math.toRadians(20.0 * (level + 1))) : 0.0;
    }

    private static double nearestShipTime(double lon, double lat, int type, int level) {
        double best = Double.POSITIVE_INFINITY;
        int bestIndex = 0;
        for (int i = 0; i <= 180; i++) {
            double sec = 10.0 * i;
            double candidate = GeoApprox.distanceM(lon, lat,
                    SHIP_LON + truthEast(sec, type, level) / METERS_PER_LON,
                    SHIP_LAT + truthNorth(sec, type, level) / GeoApprox.METERS_PER_DEG);
            if (candidate < best) { best = candidate; bestIndex = i; }
        }
        return bestIndex * 10.0;
    }

    private record Metrics(double errorM, double waitS, double energyPct) {}

    @TestConfiguration
    static class PolicyConfig {
        @Bean @Primary
        SwitchingPort comparisonPort(RendezvousFacade facade, TrajectorySnapshotPort snapshots) {
            return new SwitchingPort(facade, snapshots);
        }
    }

    static class SwitchingPort implements RendezvousPort {
        private final RendezvousFacade facade;
        private final TrajectorySnapshotPort snapshots;
        private final AtomicBoolean dynamic = new AtomicBoolean(true);

        SwitchingPort(RendezvousFacade facade, TrajectorySnapshotPort snapshots) {
            this.facade = facade;
            this.snapshots = snapshots;
        }

        @Override public B_RendezvousSolution solve(RendezvousCommand cmd) {
            if (dynamic.get()) return facade.solve(cmd);
            // Static policy fixes the latest observed ship position at dispatch time.
            var first = snapshots.latest(cmd.shipMmsi()).points().get(0);
            double distance = GeoApprox.distanceM(cmd.nestLon(), cmd.nestLat(), first.lon(), first.lat());
            long arriveMs = cmd.nowMs() + Math.round(distance / cmd.cruiseSpeedMs() * 1000.0);
            return B_RendezvousSolution.solved("static-" + cmd.comboId(), cmd.comboId(),
                    first.lon(), first.lat(), arriveMs, arriveMs + cmd.meetWindowMarginSec() * 1000L,
                    arriveMs, Math.max(1, (int) Math.ceil(distance / cmd.cruiseSpeedMs())),
                    cmd.snapshotVersion());
        }
    }
}
