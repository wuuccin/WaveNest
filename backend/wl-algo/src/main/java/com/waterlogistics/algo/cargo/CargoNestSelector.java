package com.waterlogistics.algo.cargo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 货巢选巢器——按离船距离选出一个能装货、也停得下机的机巢。
 *
 * <p>判定链（短路顺序即成本顺序，先便宜后贵）：
 * <ol>
 *   <li>船位可用，否则没有距离基准；</li>
 *   <li>巢坐标可用，且巢到船位距离在半径内；</li>
 *   <li>该巢还有空位（容量闸）；</li>
 *   <li>该巢允许至少一台符合条件的无人机停放：状态空闲、电量不低于下限、
 *       载重不低于本单货物、机型被该巢允许，且该机的归属巢坐标查得到。</li>
 * </ol>
 *
 * <h3>为什么「可用机」要连归属巢坐标一起查</h3>
 * <p>选巢结论会落库，随后由候选生成按同一套过滤口径产出候选组合。两段口径必须同源：
 * 若选巢认为某巢「有空闲机可用」，而候选生成按机归属巢算空机转场距离时发现该机
 * 归属巢查不到坐标、一个组合都产不出来，那么这批订单从下单那一刻起就没有任何候选，
 * 永远卡在调度中，现场只能看到一句「无可用候选组合」。把归属巢坐标查得到这一条
 * 收进选巢口径，选出的货巢就一定产得出组合。
 *
 * <p>排序：离船距离升序，同距离按巢编号升序。距离相同时按编号排而不按入参顺序排，
 * 是为了让同一份台账在任何调用次序下都选出同一个巢——选巢结果会落库并决定
 * 空机转场段与起飞时刻，入参顺序若能左右结果，同一订单在不同调用路径上会得到
 * 不同货巢。
 *
 * <p>本类无 Spring、无数据库依赖，数据由调用方注入，因此可纯单元测试。
 */
public class CargoNestSelector {

    /**
     * 选巢。
     *
     * @param input 选巢输入
     * @return 选巢结论，命中与否都返回
     * @throws IllegalArgumentException {@code input} 为 {@code null}
     */
    public CargoNestChoice select(CargoNestInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input 不能为 null：调用方前置校验缺失");
        }
        if (!isUsablePoint(input.shipLon(), input.shipLat())) {
            return CargoNestChoice.miss(CargoNestChoice.Reason.NO_SHIP_POSITION);
        }

        Map<String, CargoNestInput.NestView> nestById = new HashMap<>();
        for (CargoNestInput.NestView nest : input.nests()) {
            if (nest.nestId() != null) {
                nestById.put(nest.nestId(), nest);
            }
        }

        List<CargoNestInput.NestView> inRange = new ArrayList<>();
        for (CargoNestInput.NestView nest : input.nests()) {
            if (!isUsablePoint(nest.lon(), nest.lat())) {
                continue;
            }
            if (haversineKm(nest.lon(), nest.lat(), input.shipLon(), input.shipLat())
                    > input.maxNestDistanceKm()) {
                continue;
            }
            inRange.add(nest);
        }
        if (inRange.isEmpty()) {
            return CargoNestChoice.miss(CargoNestChoice.Reason.NO_NEST_AVAILABLE);
        }

        List<CargoNestInput.NestView> withSlot = inRange.stream()
                .filter(CargoNestInput.NestView::hasFreeSlotFor)
                .toList();
        if (withSlot.isEmpty()) {
            return CargoNestChoice.miss(CargoNestChoice.Reason.NO_CAPACITY);
        }

        List<CargoNestInput.NestView> usable = withSlot.stream()
                .filter(nest -> hasCompatibleDrone(nest, input, nestById))
                .toList();
        if (usable.isEmpty()) {
            return CargoNestChoice.miss(CargoNestChoice.Reason.NO_COMPATIBLE_DRONE);
        }

        CargoNestInput.NestView best = usable.stream()
                .min(Comparator
                        .comparingDouble((CargoNestInput.NestView n) -> haversineKm(
                                n.lon(), n.lat(), input.shipLon(), input.shipLat()))
                        .thenComparing(CargoNestInput.NestView::nestId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow();

        double distanceKm = haversineKm(best.lon(), best.lat(), input.shipLon(), input.shipLat());
        return new CargoNestChoice(true, best.nestId(), round2(distanceKm), CargoNestChoice.Reason.HIT);
    }

    /**
     * 该巢是否停得下符合条件的一台机。
     *
     * <p>未登记的字段按不限制处理，与候选生成的过滤口径保持一致：电量与载重未登记
     * 不淘汰（台账缺失不等于能力不足），机型未登记不限制，电量下限只对已登记的机生效。
     *
     * <p>机归属巢坐标查不到时该机不算可用机：候选生成算空机转场距离要用归属巢坐标，
     * 查不到就产不出组合，选巢若把它算作可用，会选出一个必然无候选的货巢。
     */
    private static boolean hasCompatibleDrone(CargoNestInput.NestView nest, CargoNestInput input,
            Map<String, CargoNestInput.NestView> nestById) {
        for (CargoNestInput.DroneView drone : input.drones()) {
            if (!"空闲".equals(drone.status())) {
                continue;
            }
            if (drone.hasBattery() && drone.battery() < input.minBatteryPercent()) {
                continue;
            }
            if (drone.hasMaxPayload() && drone.maxPayloadKg() < input.payloadKg()) {
                continue;
            }
            if (!nest.allowedTypes().isEmpty() && !nest.allowedTypes().contains(drone.type())) {
                continue;
            }
            CargoNestInput.NestView home = nestById.get(drone.nestId());
            if (home == null || !isUsablePoint(home.lon(), home.lat())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isUsablePoint(double lon, double lat) {
        return Double.isFinite(lon) && Double.isFinite(lat)
                && lon >= -180.0 && lon <= 180.0 && lat >= -90.0 && lat <= 90.0;
    }

    private static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
