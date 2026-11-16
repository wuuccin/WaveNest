package com.waterlogistics.dispatch.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.contract.state.GoodsStatus;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.storage.entity.GoodsEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.repository.GoodsMapper;
import com.waterlogistics.storage.repository.NestMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 接单判定输入装配器（阶段一 26 · M2 · 卢肄文）。
 *
 * <p>把 {@link AdmissionPipeline#admit} 需要的「外部世界状态」从各数据源取齐：
 * 船舶快照与当前弧长来自 {@link PredictionService}，机巢位置来自 {@link NestMapper}，
 * 闸门状态来自 {@link DispatchGateService}，判据参数来自 {@code wavenest.m2.admission.*} 配置。
 *
 * <p><b>为什么装配器单独成类</b>：{@link AdmissionPipeline} 刻意保持纯计算（不引 Spring、
 * 不碰 Mapper），才能脱离环境单测；装配职责集中在这一处，数据源接通与否的变化只改本类。
 *
 * <p><b>两处「空着」的判定</b>（与 {@code AdmissionPipeline} 类头的约定一致，
 * 空着会被记进 {@code skippedCriteria}，不是静默放过）：
 * <ul>
 *   <li>{@code goodsReady}：11-05 序号 2 起<b>已接通</b>——按 {@code goods.status = 已就绪}
 *       且（指定了货巢时）{@code goods.location} 与之一致判定，见 {@link #goodsReady}；</li>
 *   <li>{@code connectorAlive}：采集链路健康暂无查询口，传 {@code null} 只按 TTL 判。</li>
 * </ul>
 *
 * <p><b>服务航段台账</b>：{@code 37} 十四表内无此台账（{@code 80} §5.5 待补），本类以
 * {@code wavenest.m2.admission.segment-vertices} 配置的航道中心线折线代替（取值为
 * 《73-赣江场景说明》§3.1 的服务航段：朝阳大桥 → 八一大桥），区间取配置的
 * {@code [segment-start-m, segment-end-m]}；台账建表后应改为读表，本类是唯一改动点。
 */
@Service
public class AdmissionInputAssembler {

    private static final Logger log = LoggerFactory.getLogger(AdmissionInputAssembler.class);

    /** 货物状态词，取值以 {@link GoodsStatus} 为准，不在本类重复字面量。 */
    private static final String GOODS_READY = GoodsStatus.READY.getValue();

    private final PredictionService predictionService;

    private final NestMapper nestMapper;

    private final DispatchGateService dispatchGateService;

    /**
     * 货物表查询（11-05 序号 2 接货况用）；{@code null} 表示未装配。
     *
     * <p>{@code null} 不是「静默放过」：{@link #goodsReady} 会返回 {@code null}，
     * 判定链把它记进 {@code skippedCriteria（GOODS_READY）}，调用方与调度员都能看见
     * 「这一单其实没被完整判定过」。保留可空是为了让不关心货况的单元测试
     * 不必造一张货物桩表。
     */
    private final GoodsMapper goodsMapper;

    private final WaterwayGeometry segmentGeometry;

    private final double segmentStartS;

    private final double segmentEndS;

    /** 走廊半宽，m：垂距超出即视为「不在段内」（与预测侧 lane-half-width-m 同源同值）。 */
    private final double corridorHalfWidthM;

    private final AdmissionPipeline.Policy policy;

    /**
     * 生产装配用。
     *
     * <p><b>{@code @Autowired} 不能省</b>：本类有两个构造器（本方法 ＋ 下面那个不带
     * {@link GoodsMapper} 的兼容构造器），Spring 的「唯一构造器自动推断」只在构造器
     * <b>恰好一个</b>时才生效；有多个而无一标注，容器会退回找无参构造器并报
     * {@code No default constructor found}。这个坑在单测里完全看不见——单测直接
     * {@code new}，只有加载 {@code ApplicationContext} 的集成测试才会炸。
     *
     * @param predictionService  预测服务（船位与快照）
     * @param nestMapper         机巢表
     * @param dispatchGateService 闸门状态
     * @param segmentVertices    服务航段折线「lon,lat;lon,lat;…」
     * @param segmentStartS      服务航段起点弧长，m
     * @param segmentEndS        服务航段终点弧长，m（0＝取折线全长）
     * @param corridorHalfWidthM 走廊半宽，m
     * @param cargoWhitelist     货物类型白名单
     * @param maxPayloadAllTypesKg 全机型载重上界，kg
     * @param maxPayloadMargin   载重上界裕度
     * @param ttlAisMs           数据新鲜度 TTL，ms
     * @param vMaxCruiseMs       最大巡航速度，m/s
     * @param tHandoverMinSec    交接时长下界，s
     * @param wakeMarginSec      提前唤醒余量，s
     * @param suspendRetrySec    暂缓重试周期，s
     * @param horizonSec         预测时域，s
     * @param weightTolKg        重复单重量容差，kg
     * @param dupWindowSec       重复单时间窗，s
     * @param maxDeliverySec     最大配送时限，s
     * @param goodsMapper        货物表（接货况判据的数据源，11-05 序号 2）
     */
    @Autowired
    public AdmissionInputAssembler(
            PredictionService predictionService,
            NestMapper nestMapper,
            DispatchGateService dispatchGateService,
            // 服务航段折线：「lon,lat;lon,lat;…」，缺省为《73》§3.1 定稿服务航段（朝阳→八一）
            @Value("${wavenest.m2.admission.segment-vertices:"
                    + "115.852,28.646;115.859,28.663;115.879,28.692}") String segmentVertices,
            @Value("${wavenest.m2.admission.segment-start-m:0}") double segmentStartS,
            // 0 表示取折线全长（服务航段＝整条演示航段）
            @Value("${wavenest.m2.admission.segment-end-m:0}") double segmentEndS,
            // 与预测侧 lane-half-width-m 同源（50 m）
            @Value("${wavenest.m2.admission.corridor-half-width-m:50}") double corridorHalfWidthM,
            // 当前允许配送的显式子集，缺省值须与 CargoType 默认全集保持一致。
            // 未知取值与显式空集合会在启动期失败，本类不静默过滤。
            @Value("${wavenest.m2.admission.cargo-whitelist:医疗物资,应急药品,食品,生活物资}")
                    String cargoWhitelist,
            @Value("${wavenest.m2.admission.max-payload-all-types-kg:50.0}") double maxPayloadAllTypesKg,
            @Value("${wavenest.m2.admission.max-payload-margin:0.1}") double maxPayloadMargin,
            @Value("${wavenest.m2.admission.ttl-ais-ms:10000}") long ttlAisMs,
            // 机队最大巡航速度（无人机，m/s）：与 sys_param.vMaxCruiseMs=20 同值。
            // 此前默认 6.0 是照船速上界抄的，会让 etaMin 抬高 3.3 倍（11-05 序号 2 修正）。
            @Value("${wavenest.m2.admission.v-max-cruise-ms:20.0}") double vMaxCruiseMs,
            @Value("${wavenest.m2.admission.t-handover-min-sec:60}") double tHandoverMinSec,
            @Value("${wavenest.m2.admission.wake-margin-sec:60}") long wakeMarginSec,
            @Value("${wavenest.m2.admission.suspend-retry-sec:60}") long suspendRetrySec,
            // 与预测侧 horizonSec 同源（1800 s）：空间闸门只信一个预测时域内的承诺
            @Value("${wavenest.m2.admission.horizon-sec:1800}") long horizonSec,
            @Value("${wavenest.m2.admission.weight-tol-kg:0.5}") double weightTolKg,
            @Value("${wavenest.m2.admission.dup-window-sec:300}") long dupWindowSec,
            // 11-05 序号 2：系统 deadline 的业务上限（s）。与 horizonSec 分开，因为二者语义不同——
            // horizonSec 是「预测能看多远」，本值才是「最多允许多久送到」。
            @Value("${wavenest.m2.admission.max-delivery-sec:1800}") long maxDeliverySec,
            GoodsMapper goodsMapper) {
        this.predictionService = predictionService;
        this.nestMapper = nestMapper;
        this.dispatchGateService = dispatchGateService;
        this.goodsMapper = goodsMapper;
        this.segmentGeometry = parseVertices(segmentVertices);
        this.segmentStartS = segmentStartS;
        this.segmentEndS = segmentEndS > segmentStartS
                ? segmentEndS : segmentGeometry.totalLengthM();
        this.corridorHalfWidthM = corridorHalfWidthM;
        this.policy = new AdmissionPipeline.Policy(parseWhitelist(cargoWhitelist),
                maxPayloadAllTypesKg, maxPayloadMargin, ttlAisMs, vMaxCruiseMs, tHandoverMinSec,
                wakeMarginSec, suspendRetrySec, horizonSec, weightTolKg, dupWindowSec,
                maxDeliverySec);
    }

    /**
     * 兼容 11-05 序号 2 之前的 19 参构造：不接货物表，接货况判据跳过并可见。
     *
     * <p>保留理由与 {@link OrderSubmitRequest} 的 5 参构造器一致：调用点主要在单测里，
     * 「不关心货况」的用例不该被迫造一张货物桩表。
     *
     * @param predictionService  预测服务
     * @param nestMapper         机巢表
     * @param dispatchGateService 闸门状态
     * @param segmentVertices    服务航段折线
     * @param segmentStartS      服务航段起点弧长，m
     * @param segmentEndS        服务航段终点弧长，m
     * @param corridorHalfWidthM 走廊半宽，m
     * @param cargoWhitelist     货物类型白名单
     * @param maxPayloadAllTypesKg 全机型载重上界，kg
     * @param maxPayloadMargin   载重上界裕度
     * @param ttlAisMs           数据新鲜度 TTL，ms
     * @param vMaxCruiseMs       最大巡航速度，m/s
     * @param tHandoverMinSec    交接时长下界，s
     * @param wakeMarginSec      提前唤醒余量，s
     * @param suspendRetrySec    暂缓重试周期，s
     * @param horizonSec         预测时域，s
     * @param weightTolKg        重复单重量容差，kg
     * @param dupWindowSec       重复单时间窗，s
     * @param maxDeliverySec     最大配送时限，s
     */
    public AdmissionInputAssembler(
            PredictionService predictionService,
            NestMapper nestMapper,
            DispatchGateService dispatchGateService,
            String segmentVertices,
            double segmentStartS,
            double segmentEndS,
            double corridorHalfWidthM,
            String cargoWhitelist,
            double maxPayloadAllTypesKg,
            double maxPayloadMargin,
            long ttlAisMs,
            double vMaxCruiseMs,
            double tHandoverMinSec,
            long wakeMarginSec,
            long suspendRetrySec,
            long horizonSec,
            double weightTolKg,
            long dupWindowSec,
            long maxDeliverySec) {
        this(predictionService, nestMapper, dispatchGateService, segmentVertices, segmentStartS,
                segmentEndS, corridorHalfWidthM, cargoWhitelist, maxPayloadAllTypesKg,
                maxPayloadMargin, ttlAisMs, vMaxCruiseMs, tHandoverMinSec, wakeMarginSec,
                suspendRetrySec, horizonSec, weightTolKg, dupWindowSec, maxDeliverySec, null);
    }

    /**
     * 组装一次接单判定的全部输入。
     *
     * <p>船舶观测与预测快照取自该船最新 {@code PredictionSnapshot}：缺失或已过
     * {@code validitySec} 时两者都传 {@code null}（判定链按「数据不足」延后，
     * 不拿过期轨迹算 {@code etaMin} 与空间闸门）。
     *
     * @param request     判定用请求（调用方已完成基本校验与 mmsi 归一）
     * @param nowMs       判定时刻，Unix 毫秒
     * @param priorOrders 既有订单（重复单判定）；{@code null} 表示不判重复
     * @return 判定输入
     */
    public AdmissionPipeline.Input assemble(AdmissionPipeline.OrderRequest request, long nowMs,
            List<AdmissionPipeline.PriorOrder> priorOrders) {
        // 快照：有且未过期才可用；首点即当前观测位置
        Optional<PredictionSnapshot> snapshot = predictionService.snapshotOf(request.mmsi())
                .filter(s -> !s.isStale(nowMs) && !s.points().isEmpty());

        AdmissionPipeline.SnapshotView snapshotView = snapshot.map(s -> new AdmissionPipeline.SnapshotView(
                s.t0Ms(), s.points().stream()
                        .map(p -> new EstimatedPoint(p.t(), p.lon(), p.lat(),
                                p.sigmaAlong(), p.sigmaCross()))
                        .toList()))
                .orElse(null);

        // 船舶观测：当前弧长＝当前点对服务航段中心线的投影弧长；
        // 「在段内」＝弧长落在服务区间且横向垂距不超走廊半宽（投影垂足已夹进折线，
        // 只看弧长会把横偏离的船误判成段内）
        AdmissionPipeline.ShipObservation ship = snapshot.map(s -> {
            var current = s.points().get(0);
            WaterwayGeometry.Projection projection = segmentGeometry.project(
                    current.lon(), current.lat());
            boolean inSegment = projection.s() >= segmentStartS
                    && projection.s() <= segmentEndS
                    && Math.abs(projection.crossTrackDistanceM()) <= corridorHalfWidthM;
            String quality = "VALID".equals(s.premise()) ? "OK" : s.premise();
            return new AdmissionPipeline.ShipObservation(projection.s(), s.t0Ms(), quality,
                    inSegment);
        }).orElse(null);

        List<AdmissionPipeline.NestPoint> nests = nestMapper.selectList(null).stream()
                .map(AdmissionInputAssembler::toNestPoint)
                .toList();

        return new AdmissionPipeline.Input(request, nowMs, ship, snapshotView, nests,
                new AdmissionPipeline.ServiceSegment(segmentGeometry, segmentStartS, segmentEndS),
                goodsReady(request),
                !dispatchGateService.isOpen(),
                priorOrders,
                null /* connectorAlive：暂无查询口，只按 TTL 判 */,
                policy);
    }

    /**
     * 接货况判据（{@code 80} §3.1.2 ⑦ 状态闸门）——11-05 序号 2 接通。
     *
     * <p>判据：该订单的全部货物行 {@code goods.status = 已就绪}，且<b>指定了货巢时</b>
     * {@code goods.location} 与之一致。返回三值，与 {@link AdmissionPipeline.Input#goodsReady}
     * 的三态一一对应：
     * <ul>
     *   <li>{@code null} —— <b>查无货物行</b>（或未装配货物表）：判据跳过，由判定链记进
     *       {@code skippedCriteria}。为什么不判 {@code false}：货行由 M6 在下单落库时建，
     *       尚未接通前「查无行」是<b>数据源缺失</b>而不是「货没好」——按 {@code false} 处理会让
     *       每一笔正常下单都延后等一个永远不来的 {@code GOODS_READY} 事件，主链停摆。
     *       这与本类既有的「空着但可见」口径一致。</li>
     *   <li>{@code false} —— 有货行，但存在一行未就绪，或货不在指定巢：延后等货。</li>
     *   <li>{@code true} —— 全部就绪（且货巢对得上）。</li>
     * </ul>
     *
     * <p><b>为什么 {@code location} 为空时不算「不在指定巢」</b>：{@code goods.location}
     * 是 {@code 001_init.sql} §5.3 的可空列（「当前位置：所在巢/迫降点/空中」），
     * 下单建行时通常不填。若把 NULL 判成「位置不匹配」，那些没填位置就绪的货会永远等不到
     * 派单——一个可空字段变成全局死锁。故只在 {@code location} 有值时比对；
     * 要更严可后续由货主侧强制回填，判据本身不必改。
     *
     * <p><b>为什么任一行未就绪就整单判否</b>：{@code goods} 允许一单多货行
     * （{@code idx_goods_order} 非唯一），货齐才叫「可以派」，取 {@code allMatch} 而不是
     * 「有一行就绪就行」——后者会让半单货先飞走。
     *
     * @param request 判定请求（{@code orderId} 与 {@code cargoNestId} 均已归一）
     * @return {@code null}＝跳过；否则货是否已备好
     */
    private Boolean goodsReady(AdmissionPipeline.OrderRequest request) {
        if (goodsMapper == null) {
            return null;
        }
        List<GoodsEntity> rows;
        try {
            rows = goodsMapper.selectList(new LambdaQueryWrapper<GoodsEntity>()
                    .eq(GoodsEntity::getOrderId, request.orderId()));
        } catch (RuntimeException e) {
            // 查库故障不得把下单打成 500：判定链按「未判定」处理，并让运维从日志看见
            log.warn("查货物就绪状态失败，本次跳过该判据：orderId={} err={}",
                    request.orderId(), e.getMessage());
            return null;
        }
        if (rows == null || rows.isEmpty()) {
            log.debug("订单查无货物行，接货况判据跳过：orderId={}", request.orderId());
            return null;
        }
        String wantedNest = request.cargoNestId();
        for (GoodsEntity row : rows) {
            if (!GOODS_READY.equals(row.getStatus())) {
                return false;
            }
            String location = row.getLocation();
            if (wantedNest != null && location != null && !location.isBlank()
                    && !wantedNest.equals(location.trim())) {
                log.info("货物已就绪但不在指定巢：orderId={} goodsId={} 位置={} 期望={}",
                        request.orderId(), row.getGoodsId(), location, wantedNest);
                return false;
            }
        }
        return true;
    }

    /** 判据参数（只读视图，供测试与日志核对生效值）。 */
    public AdmissionPipeline.Policy policy() {
        return policy;
    }

    /**
     * 返回接单判定实际使用的当前可承运品类，按固定顺序排列。
     *
     * <p>字典查询接口读取本方法的结果，因此接口不会返回判定链不认识的取值。
     *
     * @return 当前生效的可承运品类
     */
    public List<String> cargoTypeOptions() {
        return List.copyOf(policy.cargoWhitelist());
    }

    private static AdmissionPipeline.NestPoint toNestPoint(NestEntity nest) {
        return new AdmissionPipeline.NestPoint(nest.getNestId(),
                nest.getLon().doubleValue(), nest.getLat().doubleValue());
    }

    /** 解析「lon,lat;lon,lat;…」折线；少于两个顶点无法构成中心线，直接失败。 */
    private static WaterwayGeometry parseVertices(String spec) {
        List<WaterwayGeometry.Vertex> vertices = Arrays.stream(spec.split(";"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(part -> {
                    String[] pair = part.split(",");
                    if (pair.length != 2) {
                        throw new IllegalArgumentException("航段顶点格式非法（应为 lon,lat）：" + part);
                    }
                    return new WaterwayGeometry.Vertex(
                            Double.parseDouble(pair[0].trim()), Double.parseDouble(pair[1].trim()));
                })
                .toList();
        if (vertices.size() < 2) {
            throw new IllegalArgumentException("服务航段折线至少需要两个顶点：" + spec);
        }
        return new WaterwayGeometry(vertices);
    }

    /**
     * 解析当前可承运品类配置。
     *
     * <p>本方法委托 {@link CargoTypeCatalog#resolve}，接单判定、字典查询接口与
     * 拒单提示因此共用同一份解析口径。未知取值与显式空集合在此终止启动。
     */
    private static Set<String> parseWhitelist(String spec) {
        return CargoTypeCatalog.resolve(spec);
    }

    /** 供日志输出装配摘要（机巢数与航段区间），排障用。 */
    public String describe() {
        List<NestEntity> nests = nestMapper.selectList(null);
        List<String> ids = new ArrayList<>();
        for (NestEntity nest : nests) {
            ids.add(nest.getNestId());
        }
        return "机巢" + ids + " 航段[" + (long) segmentStartS + "," + (long) segmentEndS + "m]";
    }
}
