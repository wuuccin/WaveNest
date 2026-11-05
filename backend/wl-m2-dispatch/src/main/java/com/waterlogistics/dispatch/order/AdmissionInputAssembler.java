package com.waterlogistics.dispatch.order;

import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import com.waterlogistics.dispatch.gate.DispatchGateService;
import com.waterlogistics.dispatch.prediction.PredictionService;
import com.waterlogistics.dispatch.prediction.PredictionSnapshot;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.repository.NestMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
 *   <li>{@code goodsReady}：货就绪状态属货主／M1 侧，本模块无数据源，传 {@code null} 跳过；</li>
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

    private final PredictionService predictionService;

    private final NestMapper nestMapper;

    private final DispatchGateService dispatchGateService;

    private final WaterwayGeometry segmentGeometry;

    private final double segmentStartS;

    private final double segmentEndS;

    /** 走廊半宽，m：垂距超出即视为「不在段内」（与预测侧 lane-half-width-m 同源同值）。 */
    private final double corridorHalfWidthM;

    private final AdmissionPipeline.Policy policy;

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
            @Value("${wavenest.m2.admission.cargo-whitelist:医疗物资,应急药品,食品,生活物资}")
                    String cargoWhitelist,
            @Value("${wavenest.m2.admission.max-payload-all-types-kg:50.0}") double maxPayloadAllTypesKg,
            @Value("${wavenest.m2.admission.max-payload-margin:0.1}") double maxPayloadMargin,
            @Value("${wavenest.m2.admission.ttl-ais-ms:10000}") long ttlAisMs,
            // 与预测侧 v-max-craft-ms 同源（6 m/s）：etaMin 的巡航速度上界
            @Value("${wavenest.m2.admission.v-max-cruise-ms:6.0}") double vMaxCruiseMs,
            @Value("${wavenest.m2.admission.t-handover-min-sec:60}") double tHandoverMinSec,
            @Value("${wavenest.m2.admission.wake-margin-sec:60}") long wakeMarginSec,
            @Value("${wavenest.m2.admission.suspend-retry-sec:60}") long suspendRetrySec,
            // 与预测侧 horizonSec 同源（1800 s）：空间闸门只信一个预测时域内的承诺
            @Value("${wavenest.m2.admission.horizon-sec:1800}") long horizonSec,
            @Value("${wavenest.m2.admission.weight-tol-kg:0.5}") double weightTolKg,
            @Value("${wavenest.m2.admission.dup-window-sec:300}") long dupWindowSec) {
        this.predictionService = predictionService;
        this.nestMapper = nestMapper;
        this.dispatchGateService = dispatchGateService;
        this.segmentGeometry = parseVertices(segmentVertices);
        this.segmentStartS = segmentStartS;
        this.segmentEndS = segmentEndS > segmentStartS
                ? segmentEndS : segmentGeometry.totalLengthM();
        this.corridorHalfWidthM = corridorHalfWidthM;
        this.policy = new AdmissionPipeline.Policy(parseWhitelist(cargoWhitelist),
                maxPayloadAllTypesKg, maxPayloadMargin, ttlAisMs, vMaxCruiseMs, tHandoverMinSec,
                wakeMarginSec, suspendRetrySec, horizonSec, weightTolKg, dupWindowSec);
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
                null /* goodsReady：无数据源，跳过并可见 */,
                !dispatchGateService.isOpen(),
                priorOrders,
                null /* connectorAlive：暂无查询口，只按 TTL 判 */,
                policy);
    }

    /** 判据参数（只读视图，供测试与日志核对生效值）。 */
    public AdmissionPipeline.Policy policy() {
        return policy;
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

    private static Set<String> parseWhitelist(String spec) {
        return Arrays.stream(spec.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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
