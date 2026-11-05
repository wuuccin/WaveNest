package com.waterlogistics.dispatch.rendezvous;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.algo.meeting.GeoApprox;
import com.waterlogistics.algo.meeting.model.B_RendezvousSolution;
import com.waterlogistics.algo.meeting.model.ShipPoint;
import com.waterlogistics.algo.meeting.model.ShipTrackSnapshot;
import com.waterlogistics.algo.meeting.model.Wind;
import com.waterlogistics.rendezvous.facade.RendezvousFacade;
import com.waterlogistics.rendezvous.solver.RendezvousCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Twelve reproducible moving-ship comparisons through the M3 facade used by M2. */
class DynamicMeetingComparisonTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final double LAT = 28.668;
    private static final double NEST_LON = 115.848;
    private static final double SHIP_LON = 115.863;
    private static final double DRONE_SPEED = 12.0;
    // Experiment-only proxy: 30 km round-trip energy budget, supplied here rather than production config.
    private static final double BATTERY_RANGE_M = 30_000.0;
    private static final double METERS_PER_LON = GeoApprox.METERS_PER_DEG * Math.cos(Math.toRadians(LAT));
    private static final int STEP_SEC = 10;
    private static final int POINTS = 181;
    private static final String[] TYPES = {"speed-step", "heading-jump", "ais-gap", "wind-change"};
    private static final String[] LEVELS = {"light", "medium", "heavy"};

    @Test
    void compareTwelvePerturbations() {
        int rows = 0;
        int dynamicWins = 0;
        System.out.println("type,level,mode,meetingErrorM,shipWaitS,droneEnergyPct,hasSolution");
        for (int type = 0; type < TYPES.length; type++) {
            for (int level = 0; level < LEVELS.length; level++) {
                Scenario scenario = scenario(type, level);
                B_RendezvousSolution dynamic = solve(scenario.forecast(), scenario.wind());
                // Static baseline fixes the current ship position as the meet point.
                double staticLon = scenario.forecast().points().get(0).lon();
                double staticLat = scenario.forecast().points().get(0).lat();
                Metrics baseline = evaluate(staticLon, staticLat, scenario.truth(), scenario.wind());
                Metrics active = dynamic.hasSolution()
                        ? evaluate(dynamic.meetLon(), dynamic.meetLat(), scenario.truth(), scenario.wind())
                        : Metrics.failed();
                print(type, level, "dynamic", active, dynamic.hasSolution());
                print(type, level, "static", baseline, true);
                double deltaError = active.errorM() - baseline.errorM();
                double deltaWait = active.waitS() - baseline.waitS();
                double deltaEnergy = active.energyPct() - baseline.energyPct();
                System.out.printf(Locale.ROOT, "%s,%s,delta,%.2f,%.2f,%.2f,%s%n",
                        TYPES[type], LEVELS[level], deltaError, deltaWait, deltaEnergy,
                        dynamic.hasSolution());
                String caseName = TYPES[type] + "/" + LEVELS[level];
                assertTrue(dynamic.hasSolution(), caseName + " has no dynamic solution");
                assertTrue(Double.isFinite(baseline.errorM()) && Double.isFinite(baseline.waitS())
                        && Double.isFinite(baseline.energyPct()), caseName + " has invalid static metrics");
                assertTrue(Double.isFinite(active.errorM()) && Double.isFinite(active.waitS())
                        && Double.isFinite(active.energyPct()), caseName + " has invalid dynamic metrics");
                // 三项差值必须有限（可复算）——杜绝 NaN 只进打印、无人发现的现状。
                assertTrue(Double.isFinite(deltaError) && Double.isFinite(deltaWait)
                        && Double.isFinite(deltaEnergy), caseName + " has non-finite delta");
                // 会面误差更小视为「动态占优」；误差是三项指标中最直接衡量方案优劣的口径。
                if (deltaError < 0) dynamicWins++;
                rows++;
            }
        }
        assertEquals(12, rows);
        // 至少一个档位动态方案误差优于静态基线，证明「动态会面」能力在可判定档位上生效。
        assertTrue(dynamicWins > 0, "no perturbation level where dynamic beats static baseline");
    }

    @Test
    void aisGapForecastFreezesAtLastKnownPoint() {
        for (int level = 0; level < LEVELS.length; level++) {
            Scenario gap = scenario(2, level);
            double lastKnownLon = gap.forecast().points().get(5).lon();
            int gapEnd = 6 + 6 * (level + 1);
            for (int i = 6; i <= gapEnd; i++) {
                assertEquals(lastKnownLon, gap.forecast().points().get(i).lon(), 1e-12);
                assertTrue(gap.truth().get(i).lon() > lastKnownLon);
            }
            assertTrue(gap.forecast().points().get(gapEnd + 1).lon() > lastKnownLon);
        }
    }

    private static Scenario scenario(int type, int level) {
        int severity = level + 1;
        List<ShipPoint> forecast = new ArrayList<>();
        List<ShipPoint> truth = new ArrayList<>();
        Wind wind = new Wind(type == 3 ? 1.5 * severity : 0.0, 90.0, 0.0);
        for (int i = 0; i < POINTS; i++) {
            double sec = i * STEP_SEC;
            double predictedEast = 3.0 * sec;
            double actualEast = predictedEast;
            double actualNorth = 0;
            if (i >= 6) {
                double affectedSec = sec - 60;
                if (type == 0) actualEast += 0.8 * severity * affectedSec;
                if (type == 1) {
                    double angle = Math.toRadians(20.0 * severity);
                    actualEast = 180 + 3.0 * affectedSec * Math.cos(angle);
                    actualNorth = 3.0 * affectedSec * Math.sin(angle);
                }
                if (type == 2) actualEast += 0.35 * severity * affectedSec;
            }
            if (type == 2 && i >= 6 && i <= 6 + 6 * severity) {
                // No new AIS observation: freeze at the last known position while truth keeps moving.
                predictedEast = 3.0 * (6 - 1) * STEP_SEC;
            }
            forecast.add(new ShipPoint(NOW + i * STEP_SEC * 1_000L,
                    SHIP_LON + predictedEast / METERS_PER_LON, LAT, 90.0, 10, 10));
            truth.add(new ShipPoint(NOW + i * STEP_SEC * 1_000L,
                    SHIP_LON + actualEast / METERS_PER_LON,
                    LAT + actualNorth / GeoApprox.METERS_PER_DEG, 90.0, 10, 10));
        }
        return new Scenario(new ShipTrackSnapshot("1", "123456789", NOW, 1_800, forecast), truth, wind);
    }

    private static B_RendezvousSolution solve(ShipTrackSnapshot forecast, Wind wind) {
        RendezvousFacade facade = new RendezvousFacade(mmsi -> forecast, droneId -> null);
        return facade.solve(new RendezvousCommand("combo-1", forecast.shipMmsi(),
                forecast.version(), NEST_LON, LAT, DRONE_SPEED, wind,
                NOW, NOW + 1_800_000L, 120, 1.0, 50));
    }

    private static Metrics evaluate(double meetLon, double meetLat, List<ShipPoint> truth, Wind wind) {
        // Wind convention is "from"; eastbound headwind reduces air-to-ground speed.
        double groundSpeed = DRONE_SPEED - wind.speedMs();
        double distance = GeoApprox.distanceM(NEST_LON, LAT, meetLon, meetLat);
        double arrivalSec = distance / groundSpeed;
        int arrivalIndex = Math.min(truth.size() - 1, (int) Math.round(arrivalSec / STEP_SEC));
        ShipPoint actual = truth.get(arrivalIndex);
        double error = GeoApprox.distanceM(meetLon, meetLat, actual.lon(), actual.lat());
        double shipArrivalSec = firstArrival(truth, meetLon, meetLat);
        double wait = Math.max(0, arrivalSec - shipArrivalSec);
        double energy = 100.0 * 2.0 * distance / BATTERY_RANGE_M;
        return new Metrics(error, wait, energy);
    }

    private static double firstArrival(List<ShipPoint> truth, double lon, double lat) {
        double best = Double.POSITIVE_INFINITY;
        int bestIndex = 0;
        for (int i = 0; i < truth.size(); i++) {
            ShipPoint point = truth.get(i);
            double gap = GeoApprox.distanceM(lon, lat, point.lon(), point.lat());
            if (gap < best) {
                best = gap;
                bestIndex = i;
            }
        }
        return bestIndex * STEP_SEC;
    }

    private static void print(int type, int level, String mode, Metrics m, boolean solved) {
        System.out.printf(Locale.ROOT, "%s,%s,%s,%.2f,%.2f,%.2f,%s%n",
                TYPES[type], LEVELS[level], mode, m.errorM(), m.waitS(), m.energyPct(), solved);
    }

    private record Scenario(ShipTrackSnapshot forecast, List<ShipPoint> truth, Wind wind) {}
    private record Metrics(double errorM, double waitS, double energyPct) {
        static Metrics failed() { return new Metrics(Double.NaN, Double.NaN, Double.NaN); }
    }
}
