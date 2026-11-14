package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.cargo.CargoNestChoice;
import com.waterlogistics.algo.cargo.CargoNestInput;
import com.waterlogistics.algo.cargo.CargoNestSelector;
import com.waterlogistics.contract.ws.ShipTrajPoint;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 货巢决策服务——下单时刻由系统选定货物所在的机巢。
 *
 * <p><b>为什么货巢改由系统选</b>：货物实际在哪个巢属于客观事实，由货物流转过程决定。
 * 下单人在下单瞬间手工指认，等于让用户替系统认领一个它无从核实的客观状态；一旦指错，
 * 订单会持锁停在等货态，且没有修正窗口。
 *
 * <p><b>为什么落在下单时刻</b>：货巢要作为会面求解的起点，决定空机转场时长与起飞时刻，
 * 因此必须在下单那一刻就确定并落库。派单时再选就来不及了。
 *
 * <p><b>职责边界</b>：本类只做「查台账、读船位、读开关、调算法包」四件事，
 * 判定与排序全在算法包 {@code CargoNestSelector} 内；本类不复制一份判定逻辑，
 * 否则两处口径会分叉。
 *
 * <p><b>为什么放在候选包下</b>：机巢可用性口径、机型拆分口径、占用数与剩余空位口径
 * 都在 {@code candidate} 包内，与候选链共用同一份实现，避免同一个巢在候选链里可选、
 * 在选巢里却不可选。
 */
@Service
public class CargoNestDecisionService {

    private static final Logger log = LoggerFactory.getLogger(CargoNestDecisionService.class);

    /** 货巢决策开关参数键（参数表登记见 {@code deploy/sql/015_cargo_nest_decision.sql}）。 */
    public static final String DECISION_ENABLED_KEY = "cargoNestDecisionEnabled";

    /** 货巢决策开关兜底值：参数缺失时开启选巢。 */
    public static final boolean DEFAULT_DECISION_ENABLED = true;

    /** 巢到船位最大距离参数键（与候选链共用同一把尺）。 */
    private static final String MAX_NEST_DISTANCE_KM_KEY = "maxNestDistanceKm";

    /** 巢到船位最大距离兜底值，km。 */
    private static final double DEFAULT_MAX_NEST_DISTANCE_KM = 5.0;

    /** 最低保留电量参数键（与候选链共用同一把尺）。 */
    private static final String MIN_BATTERY_PERCENT_KEY = "minBatteryPercent";

    /** 最低保留电量兜底值，%。 */
    private static final double DEFAULT_MIN_BATTERY_PERCENT = 30.0;

    private final NestMapper nestMapper;
    private final DroneMapper droneMapper;
    private final SysParamLoader sysParamLoader;

    /**
     * 船位查询：MMSI → 当前船位。
     *
     * <p>为什么抽成窄函数而不是直接依赖预测服务：本类只需要「此刻船在哪」这一件事，
     * 预测服务却是一整套快照缓存与精算编排。窄依赖让本类可被纯单测覆盖，
     * 不必为测一个坐标去装配预测服务。
     */
    private final Function<String, Optional<ShipTrajPoint>> shipPositionLookup;

    private final CargoNestSelector selector = new CargoNestSelector();

    @Autowired
    public CargoNestDecisionService(NestMapper nestMapper, DroneMapper droneMapper,
            PredictionService predictionService, SysParamLoader sysParamLoader) {
        this(nestMapper, droneMapper, mmsi -> predictionService.snapshotOf(mmsi)
                .filter(snapshot -> !snapshot.points().isEmpty())
                .map(snapshot -> snapshot.points().get(0)), sysParamLoader);
    }

    /**
     * 构造器：船位来源由调用方给出。
     *
     * <p>供纯单测使用，注入一个查台账与取船位都可控的版本。
     */
    CargoNestDecisionService(NestMapper nestMapper, DroneMapper droneMapper,
            Function<String, Optional<ShipTrajPoint>> shipPositionLookup,
            SysParamLoader sysParamLoader) {
        this.nestMapper = nestMapper;
        this.droneMapper = droneMapper;
        this.shipPositionLookup = shipPositionLookup;
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 货巢决策开关是否开启。
     *
     * <p>关闭后系统不再选巢，下单后的链路行为与改造前完全一致，作为应急回退手段。
     */
    public boolean decisionEnabled() {
        return sysParamLoader.getBoolean(DECISION_ENABLED_KEY, DEFAULT_DECISION_ENABLED);
    }

    /**
     * 选一个货巢。
     *
     * <p>命中与否都返回结论：未命中时结论里带落选原因，调用方据此留痕，
     * 现场才能看出是没船位、没巢、巢满还是机型不匹配。
     *
     * @param shipMmsi      目标船 MMSI；用于取船位
     * @param goodsWeightKg 本单货物载重，kg；为空时按 0 处理
     * @return 选巢结论
     */
    public CargoNestChoice decide(String shipMmsi, Double goodsWeightKg) {
        if (!decisionEnabled()) {
            log.info("货巢决策开关关闭（参数 {} 为 false），本单不选巢，按无货巢旧口径处理", DECISION_ENABLED_KEY);
            return CargoNestChoice.miss(CargoNestChoice.Reason.DISABLED);
        }

        ShipTrajPoint current = shipPositionLookup.apply(shipMmsi).orElse(null);
        if (current == null) {
            log.info("货巢未选定：目标船 {} 没有可用预测快照，取不到船位", shipMmsi);
            return CargoNestChoice.miss(CargoNestChoice.Reason.NO_SHIP_POSITION);
        }
        double shipLon = current.lon();
        double shipLat = current.lat();
        if (!Double.isFinite(shipLon) || !Double.isFinite(shipLat)) {
            log.info("货巢未选定：目标船 {} 的预测船位非法（lon={} lat={}）", shipMmsi, shipLon, shipLat);
            return CargoNestChoice.miss(CargoNestChoice.Reason.NO_SHIP_POSITION);
        }

        List<DroneEntity> drones = droneMapper.selectList(CandidateQueries.idleAndHealthyDrones());
        List<NestEntity> nests = nestMapper.selectList(CandidateQueries.availableNests());

        double payloadKg = goodsWeightKg == null ? 0.0 : goodsWeightKg;
        CargoNestInput input = new CargoNestInput(
                shipLon,
                shipLat,
                payloadKg,
                toNestViews(nests, drones),
                toDroneViews(drones),
                sysParamLoader.getDouble(MAX_NEST_DISTANCE_KM_KEY, DEFAULT_MAX_NEST_DISTANCE_KM),
                sysParamLoader.getDouble(MIN_BATTERY_PERCENT_KEY, DEFAULT_MIN_BATTERY_PERCENT));

        CargoNestChoice choice = selector.select(input);
        if (choice.hit()) {
            log.info("货巢已选定：mmsi={} 货巢={} 距船 {}km 载重={}kg",
                    shipMmsi, choice.nestId(), choice.distanceKm(), payloadKg);
        } else {
            log.info("货巢未选定：mmsi={} 原因={} 载重={}kg 在巢候选={} 可用机={}",
                    shipMmsi, choice.reason(), payloadKg, nests.size(), drones.size());
        }
        return choice;
    }

    /**
     * 机巢行 → 选巢用巢视图。
     *
     * <p>坐标缺失的巢直接跳过：它既不能当装货点也不能算距离，跳过时留一条日志，
     * 让台账问题显形而不是被静默过滤。
     */
    private static List<CargoNestInput.NestView> toNestViews(List<NestEntity> nests,
            List<DroneEntity> drones) {
        List<CargoNestInput.NestView> views = new ArrayList<>(nests.size());
        for (NestEntity nest : nests) {
            if (nest.getLon() == null || nest.getLat() == null) {
                log.warn("机巢 {} 缺少经纬度，不参与选巢——请补全台账（nest.lon/lat）", nest.getNestId());
                continue;
            }
            int occupied = CandidateInputAssembler.occupiedCountOf(nest, drones);
            views.add(new CargoNestInput.NestView(
                    nest.getNestId(),
                    nest.getLon().doubleValue(),
                    nest.getLat().doubleValue(),
                    CandidateInputAssembler.splitModels(nest.getSupportedModels()),
                    CandidateInputAssembler.freeSlotsOf(nest, occupied)));
        }
        return views;
    }

    /** 无人机行 → 选巢用机视图。 */
    private static List<CargoNestInput.DroneView> toDroneViews(List<DroneEntity> drones) {
        List<CargoNestInput.DroneView> views = new ArrayList<>(drones.size());
        for (DroneEntity drone : drones) {
            views.add(new CargoNestInput.DroneView(
                    drone.getDroneId(),
                    // 归属巢口径与候选链装配器一致：先当前巢，无当前巢时取归属巢
                    drone.getCurrentNestId() != null ? drone.getCurrentNestId() : drone.getHomeNestId(),
                    drone.getModel(),
                    drone.getStatus(),
                    optionalDouble(drone.getBattery()),
                    optionalDouble(drone.getMaxPayload())));
        }
        return views;
    }

    /** {@code BigDecimal} → {@code double}；为 {@code null} 时给 {@code NaN}（＝未登记，不是 0）。 */
    private static double optionalDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : Double.NaN;
    }
}
