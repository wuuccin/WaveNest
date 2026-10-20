package com.waterlogistics.dispatch.order;

import com.waterlogistics.algo.estimation.EtaLowerBound;
import com.waterlogistics.algo.estimation.EstimatedPoint;
import com.waterlogistics.algo.estimation.ServiceSegmentGate;
import com.waterlogistics.algo.estimation.WaterwayGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 接单受理判定流水线（M2 · 卢肄文 · 功能 1 · {@code 80-A组算法方案} §3.1.2）。
 *
 * <p>把 {@code 80} §3.1.2 的八步判定链完整落到可执行代码：信息完整性 → 幂等与重复 → 时效性
 * （{@code etaMin}）→ 货物合法性 → 数据有效性 → 空间闸门（{@code tEnter}）→ 状态闸门 → 时间闸门。
 *
 * <h3>★ 三处「先空着」的判定，以及为什么空着不等于放过</h3>
 * <p>有几步依赖<b>系统里还不存在的数据</b>，本类不臆造、也不用默认值糊过去，而是把对应输入声明为
 * 可空；为 {@code null} 时该步<b>不判定</b>，同时把判据名记进 {@link Decision#skippedCriteria()}。
 * 这样调用方与调度员都能看见「这一单其实没被完整判定过」，而不是被静默放行：
 * <ul>
 *   <li><b>空间闸门（{@code tEnter}）</b>：需要航道中心线台账与服务航段里程区间
 *       {@code [sStart, sEnd]}——{@code 37} 的 14 张表内没有这份台账（{@code 80} §5.5），
 *       集结点坐标也尚未定，故 {@link ServiceSegment} 传 {@code null} 即跳过。</li>
 *   <li><b>状态闸门（货已备好）</b>：货物就绪状态属货主／M1 侧，本模块无数据源，
 *       {@link Input#goodsReady()} 传 {@code null} 即跳过。注意这一步是<b>给下游的隐式承诺</b>
 *       ——进了调度就认定货已备好（§3.1.5），所以它必须由有数据源的一方尽快接通。</li>
 *   <li><b>时间闸门（闸门熔断）</b>：{@code dispatch.gate} 的 {@code DispatchGateService}
 *       已存在，但本类保持纯计算、不引 Spring Bean，故以 {@link Input#gateCutoff()}
 *       布尔注入，传 {@code null} 即跳过。</li>
 * </ul>
 *
 * <h3>为什么受理不等于立刻派得出去</h3>
 * <p>{@link Outcome#DEFER}（已受理待唤醒）与 {@link Outcome#REJECT}（已拒绝）语义完全分开：
 * 拒单只来自业务边界，而「没有可用运力」「船还没进服务航段」「货没好」「闸门熔断」都属于
 * <b>延后</b>——尤其<b>熔断期间绝不拒单</b>（§3.1.5）。
 *
 * <p>本类<b>纯计算</b>：不引 Spring、不写库、无共享状态；幂等落库与事件唤醒由调用方负责。
 * 这样可以脱离上下文做单测，也可以安全地先在旁路跑一段时间对账再接通。
 */
public final class AdmissionPipeline {

    /** 出口类型。 */
    public enum Outcome {
        /** 受理并触发调度 */
        ACCEPT_TRIGGER_SCHEDULE("受理并触发调度"),
        /** 延后：已受理待唤醒 */
        DEFER("延后待唤醒"),
        /** 拒绝 */
        REJECT("拒绝"),
        /** 转人工：信息不完整，不流转 */
        MANUAL("转人工");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        /** 中文出口名。 */
        public String label() {
            return label;
        }
    }

    /** 原因码（{@code 80} §3.1.4 的内部枚举；落库编码由 M6 定）。 */
    public enum Reason {
        /** 无（受理时） */
        NONE("无"),
        /** 信息不完整 */
        INCOMPLETE("信息不完整"),
        /** 重复单 */
        DUPLICATE("重复提交"),
        /** 已过截止时刻 */
        EXPIRED("已过截止时刻"),
        /** 时间上不可行 */
        TIME_INFEASIBLE("时间不可行"),
        /** 货物类型不在白名单 */
        CARGO_TYPE("货物类型不受理"),
        /** 超载 */
        OVERLOAD("超出载重上界"),
        /** MMSI 非法 */
        MMSI_INVALID("船舶标识非法"),
        /** 数据不足 */
        DATA_STALE("数据不足"),
        /** 船未进入服务航段（可延后） */
        SHIP_OUT_OF_SEGMENT("尚未进入服务航段"),
        /** 始终进不了服务航段（拒单） */
        OUT_OF_SERVICE("不在服务航段内"),
        /** 货未备好 */
        GOODS_NOT_READY("货物未就绪"),
        /** 闸门熔断 */
        GATE_CUTOFF("闸门熔断");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        /** 中文说明。 */
        public String label() {
            return label;
        }
    }

    /** 唤醒事件（{@link Outcome#DEFER} 时有效）。 */
    public enum WakeEvent {
        /** 无需唤醒 */
        NONE("NONE"),
        /** 数据恢复 */
        DATA_RESTORED("DATA_RESTORED"),
        /** 船驶入服务航段 */
        SHIP_ENTERED("SHIP_ENTERED"),
        /** 货已备好 */
        GOODS_READY("GOODS_READY"),
        /** 闸门解除 */
        GATE_RESUME("GATE_RESUME");

        private final String code;

        WakeEvent(String code) {
            this.code = code;
        }

        /** 事件编码。 */
        public String code() {
            return code;
        }
    }

    /**
     * 下单请求（调用方已解析好的字段）。
     *
     * @param orderId       订单号（幂等键）
     * @param mmsi          船舶 MMSI
     * @param cargoType     货物类型
     * @param goodsWeightKg 物资载重，kg
     * @param deadlineMs    期望送达时刻，Unix 毫秒；{@code null} 表示未填
     * @param priority      优先级编码；{@code null} 表示未填
     * @param goodsId       货物单号（查就绪状态用）
     */
    public record OrderRequest(String orderId, String mmsi, String cargoType, Double goodsWeightKg,
            Long deadlineMs, String priority, String goodsId) {

        /** 信息是否完整（§3.1.2 ①）。{@code priority} 与 {@code deadline} 缺一即转人工。 */
        public boolean isComplete() {
            return orderId != null && !orderId.isBlank()
                    && mmsi != null && !mmsi.isBlank()
                    && cargoType != null && !cargoType.isBlank()
                    && goodsWeightKg != null && goodsWeightKg > 0.0
                    && Double.isFinite(goodsWeightKg)
                    && deadlineMs != null
                    && priority != null && !priority.isBlank();
        }
    }

    /**
     * 船舶状态观测（由步骤 C／F 的估计结果给出）。
     *
     * @param currentS    当前沿航道弧长估计，m
     * @param lastAisTsMs 最近一次有效 AIS 的时刻，Unix 毫秒
     * @param dataQuality 数据质量编码（{@code OK}／{@code EXTRAPOLATED}／{@code INSUFFICIENT}）
     */
    public record ShipObservation(double currentS, long lastAisTsMs, String dataQuality) {
    }

    /**
     * 预测点列的只读视图（{@code PredictionSnapshot} 的算法层投影）。
     *
     * @param t0Ms  预测起始时刻，Unix 毫秒
     * @param points 预测点列（含 {@code τ = 0}）
     */
    public record SnapshotView(long t0Ms, List<EstimatedPoint> points) {

        public SnapshotView {
            points = List.copyOf(points);
        }
    }

    /**
     * 集结点（机巢）位置。
     *
     * @param nestId 机巢编号
     * @param lon    经度
     * @param lat    纬度
     */
    public record NestPoint(String nestId, double lon, double lat) {
    }

    /**
     * 服务航段（由运营配置给出的航道里程区间 ＋ 参考线）。
     *
     * <p><b>这份台账目前不存在</b>（{@code 37} 的 14 张表里没有，{@code 80} §5.5 列为待补）；
     * 传 {@code null} 给 {@link Input} 表示空间闸门未接通。
     *
     * @param geometry 航道中心线
     * @param startS   服务航段起点弧长，m
     * @param endS     服务航段终点弧长，m
     */
    public record ServiceSegment(WaterwayGeometry geometry, double startS, double endS) {

        public ServiceSegment {
            if (geometry == null) {
                throw new IllegalArgumentException("中心线不得为 null");
            }
            if (!(endS > startS)) {
                throw new IllegalArgumentException("服务航段区间非法：[" + startS + ", " + endS + "]");
            }
        }

        /** 给定弧长是否落在服务航段内。 */
        public boolean contains(double s) {
            return s >= startS && s <= endS;
        }
    }

    /**
     * 既有订单（做重复单判定用）。
     *
     * @param mmsi          船 MMSI
     * @param cargoType     货物类型
     * @param goodsWeightKg 载重，kg
     * @param submitTsMs    提交时刻
     * @param terminal      是否已处于终止态（终止态不参与重复判定）
     */
    public record PriorOrder(String mmsi, String cargoType, double goodsWeightKg, long submitTsMs,
            boolean terminal) {
    }

    /**
     * 判据参数（取值归 {@code sys_param}，本类只承接，不认键名）。
     *
     * @param cargoWhitelist    货物类型白名单
     * @param maxPayloadAllTypesKg 全机型载重上界，kg
     * @param maxPayloadMargin  载重上界裕度
     * @param ttlAisMs          订单数据新鲜度 TTL，ms
     * @param vMaxCruiseMs      最大巡航速度，m/s（{@code etaMin} 用）
     * @param tHandoverMinSec   交接时长下界，s（{@code etaMin} 用）
     * @param wakeMarginSec     提前唤醒余量，s
     * @param suspendRetrySec   暂缓重试周期，s
     * @param horizonSec        预测时域，s
     * @param weightTolKg       重复单重量容差，kg
     * @param dupWindowSec      重复单时间窗，s
     */
    public record Policy(Set<String> cargoWhitelist, double maxPayloadAllTypesKg,
            double maxPayloadMargin, long ttlAisMs, double vMaxCruiseMs, double tHandoverMinSec,
            long wakeMarginSec, long suspendRetrySec, long horizonSec, double weightTolKg,
            long dupWindowSec) {

        public Policy {
            cargoWhitelist = cargoWhitelist == null ? Set.of() : Set.copyOf(cargoWhitelist);
            if (!(maxPayloadAllTypesKg > 0.0)) {
                throw new IllegalArgumentException("载重上界必须为正：" + maxPayloadAllTypesKg);
            }
            if (maxPayloadMargin < 0.0 || maxPayloadMargin >= 1.0) {
                throw new IllegalArgumentException("载重裕度须在 [0,1) 内：" + maxPayloadMargin);
            }
            if (!(ttlAisMs > 0) || !(vMaxCruiseMs > 0.0) || tHandoverMinSec < 0.0) {
                throw new IllegalArgumentException("TTL / 巡航速度必须为正");
            }
            if (wakeMarginSec < 0 || suspendRetrySec <= 0 || horizonSec <= 0
                    || weightTolKg < 0.0 || dupWindowSec <= 0) {
                throw new IllegalArgumentException("唤醒／重试／时域／容差参数非法");
            }
        }
    }

    /**
     * 判定输入：所有外部世界状态都在这里注入，{@code null} 表示该判据的数据源尚未接通。
     *
     * @param request       下单请求
     * @param nowMs         判定时刻，Unix 毫秒
     * @param ship          船舶状态观测；{@code null} 视为数据不足
     * @param snapshot      预测快照；{@code null} 视为数据不足（调用方应先现算）
     * @param nests         机巢位置；空列表或 {@code null} 时 {@code etaMin} 判据跳过
     * @param serviceSegment 服务航段；{@code null} 时空间闸门跳过
     * @param goodsReady    货是否就绪；{@code null} 时状态闸门跳过
     * @param gateCutoff    闸门是否熔断；{@code null} 时时间闸门跳过
     * @param priorOrders   既有订单；{@code null} 时重复单判据跳过
     * @param connectorAlive 采集链路是否存活；{@code null} 时只按 TTL 判数据有效性
     * @param policy        判据参数
     */
    public record Input(OrderRequest request, long nowMs, ShipObservation ship,
            SnapshotView snapshot, List<NestPoint> nests, ServiceSegment serviceSegment,
            Boolean goodsReady, Boolean gateCutoff, List<PriorOrder> priorOrders,
            Boolean connectorAlive, Policy policy) {

        public Input {
            if (policy == null) {
                throw new IllegalArgumentException("判据参数不得为 null");
            }
            nests = nests == null ? List.of() : List.copyOf(nests);
            priorOrders = priorOrders == null ? null : List.copyOf(priorOrders);
        }
    }

    /**
     * 判定结论。
     *
     * @param outcome         出口类型
     * @param reason          原因码
     * @param wakeAtMs        唤醒时刻，Unix 毫秒（仅定时唤醒有值，其余为 0）
     * @param wakeEvent       唤醒事件
     * @param skippedCriteria <b>本次未判定的判据名</b>——空着不等于放过，必须能被看见
     * @param etaLowerBoundSec 时间可行性下界 {@code etaMin}，s（未算出时为 {@code NaN}）
     * @param enterTsMs       驶入服务航段时刻，Unix 毫秒（未算出时为 {@code null}）
     * @param detail          人读说明
     */
    public record Decision(Outcome outcome, Reason reason, long wakeAtMs, WakeEvent wakeEvent,
            List<String> skippedCriteria, double etaLowerBoundSec, Long enterTsMs, String detail) {

        public Decision {
            skippedCriteria = List.copyOf(skippedCriteria);
        }

        /** 是否放行进入调度。 */
        public boolean allowsSchedule() {
            return outcome == Outcome.ACCEPT_TRIGGER_SCHEDULE;
        }

        /** 是否有判据没判定（需要有人补数据源）。 */
        public boolean hasSkippedCriteria() {
            return !skippedCriteria.isEmpty();
        }
    }

    /** 判据名常量，供 {@link Decision#skippedCriteria()} 检索。 */
    public static final String CRITERION_SPACE_GATE = "SPACE_GATE(tEnter)";
    /** 判据名：货已备好。 */
    public static final String CRITERION_GOODS_READY = "GOODS_READY";
    /** 判据名：闸门熔断。 */
    public static final String CRITERION_GATE_CUTOFF = "GATE_CUTOFF";
    /** 判据名：重复单。 */
    public static final String CRITERION_DUPLICATE = "DUPLICATE";
    /** 判据名：时间可行性下界。 */
    public static final String CRITERION_ETA_MIN = "ETA_MIN";
    /** 判据名：采集链路健康。 */
    public static final String CRITERION_CONNECTOR_HEALTH = "CONNECTOR_HEALTH";

    private AdmissionPipeline() {
    }

    /**
     * 跑一遍判定链。
     *
     * @param input 判定输入
     * @return 判定结论
     */
    public static Decision admit(Input input) {
        Policy policy = input.policy();
        OrderRequest request = input.request();
        long nowMs = input.nowMs();
        List<String> skipped = new ArrayList<>();

        // ① 信息完整性
        if (request == null || !request.isComplete()) {
            return new Decision(Outcome.MANUAL, Reason.INCOMPLETE, 0L, WakeEvent.NONE, skipped,
                    Double.NaN, null, "请求字段不完整，转人工补录");
        }
        // MMSI 格式（§3.1.2 ⑤ 的形式部分，提前到此处以免后续白跑）
        if (!isValidMmsi(request.mmsi())) {
            return new Decision(Outcome.REJECT, Reason.MMSI_INVALID, 0L, WakeEvent.NONE, skipped,
                    Double.NaN, null, "MMSI 非 9 位数字：" + request.mmsi());
        }

        // ② 幂等与重复（幂等落库由调用方负责；这里只判「重复单」）
        if (input.priorOrders() == null) {
            skipped.add(CRITERION_DUPLICATE);
        } else if (isDuplicate(request, input.priorOrders(), policy, nowMs)) {
            return new Decision(Outcome.REJECT, Reason.DUPLICATE, 0L, WakeEvent.NONE, skipped,
                    Double.NaN, null, "窗口内存在同船、同货类、同重量的非终止订单");
        }

        // ③ 时效性：先算 etaMin（需要快照 ＋ 机巢），再用它卡截止时刻
        double etaMin = Double.NaN;
        if (input.snapshot() == null || input.snapshot().points().isEmpty()) {
            skipped.add(CRITERION_ETA_MIN);
        } else if (input.nests().isEmpty()) {
            skipped.add(CRITERION_ETA_MIN);
        } else {
            EtaLowerBound.Result eta = EtaLowerBound.compute(input.snapshot().points(),
                    toNestPositions(input.nests()), policy.vMaxCruiseMs(), policy.tHandoverMinSec());
            etaMin = eta.etaLowerBoundSec();
        }
        if (nowMs >= request.deadlineMs()) {
            return new Decision(Outcome.REJECT, Reason.EXPIRED, 0L, WakeEvent.NONE, skipped, etaMin,
                    null, "已过截止时刻");
        }
        if (!Double.isNaN(etaMin) && request.deadlineMs() - nowMs < etaMin * 1000.0) {
            return new Decision(Outcome.REJECT, Reason.TIME_INFEASIBLE, 0L, WakeEvent.NONE, skipped,
                    etaMin, null, String.format("剩余时间 %.0f s 小于最短任务时长下界 %.0f s",
                            (request.deadlineMs() - nowMs) / 1000.0, etaMin));
        }

        // ④ 货物合法性
        if (!policy.cargoWhitelist().contains(request.cargoType())) {
            return new Decision(Outcome.REJECT, Reason.CARGO_TYPE, 0L, WakeEvent.NONE, skipped,
                    etaMin, null, "货物类型不在白名单：" + request.cargoType());
        }
        double payloadLimit = policy.maxPayloadAllTypesKg() * (1.0 - policy.maxPayloadMargin());
        if (request.goodsWeightKg() > payloadLimit) {
            return new Decision(Outcome.REJECT, Reason.OVERLOAD, 0L, WakeEvent.NONE, skipped, etaMin,
                    null, String.format("载重 %.1f kg 超过上界 %.1f kg", request.goodsWeightKg(),
                            payloadLimit));
        }

        // ⑤ 数据有效性：状态存在 ＋ TTL ＋ 链路健康
        if (input.ship() == null || nowMs - input.ship().lastAisTsMs() > policy.ttlAisMs()) {
            return new Decision(Outcome.DEFER, Reason.DATA_STALE,
                    nowMs + policy.suspendRetrySec() * 1000L, WakeEvent.DATA_RESTORED, skipped, etaMin,
                    null, "船舶状态缺失或已超 TTL，按 suspendRetrySec 重判");
        }
        if (input.connectorAlive() == null) {
            skipped.add(CRITERION_CONNECTOR_HEALTH);
        } else if (!input.connectorAlive()) {
            return new Decision(Outcome.DEFER, Reason.DATA_STALE,
                    nowMs + policy.suspendRetrySec() * 1000L, WakeEvent.DATA_RESTORED, skipped, etaMin,
                    null, "采集链路断流，按 suspendRetrySec 重判");
        }

        // ⑥ 空间闸门
        Long enterTsMs = null;
        if (input.serviceSegment() == null || input.snapshot() == null) {
            skipped.add(CRITERION_SPACE_GATE);
        } else {
            ServiceSegment segment = input.serviceSegment();
            if (segment.contains(input.ship().currentS())) {
                enterTsMs = input.snapshot().t0Ms();      // 已在段内：驶入时刻即当前
            } else {
                ServiceSegmentGate.EntryResult entry = ServiceSegmentGate.firstEntry(
                        input.snapshot().points(), segment.geometry(), segment.startS(),
                        segment.endS());
                if (!entry.reaches()) {
                    return new Decision(Outcome.REJECT, Reason.OUT_OF_SERVICE, 0L, WakeEvent.NONE,
                            skipped, etaMin, null, "预测时域内船舶不会进入服务航段");
                }
                enterTsMs = entry.enterTsMs();
                // etaMin 没算出来时不得拿它去卡上界：NaN 转 long 会变 0，等于凭空放宽
                long deadlineBound = Double.isNaN(etaMin)
                        ? request.deadlineMs()
                        : request.deadlineMs() - (long) (etaMin * 1000.0);
                long latest = Math.min(deadlineBound,
                        input.snapshot().t0Ms() + policy.horizonSec() * 1000L);
                if (enterTsMs > latest) {
                    return new Decision(Outcome.REJECT, Reason.OUT_OF_SERVICE, 0L, WakeEvent.NONE,
                            skipped, etaMin, enterTsMs,
                            "驶入时刻晚于「截止时刻 − etaMin」或预测时域上界");
                }
                long wakeAt = enterTsMs - policy.wakeMarginSec() * 1000L;
                return new Decision(Outcome.DEFER, Reason.SHIP_OUT_OF_SEGMENT, wakeAt,
                        WakeEvent.SHIP_ENTERED, skipped, etaMin, enterTsMs,
                        "船尚未进入服务航段，按 tEnter − wakeMargin 定时唤醒");
            }
        }

        // ⑦ 状态闸门：货已备好（进调度即默认货已好的隐式承诺，必须守住）
        if (input.goodsReady() == null) {
            skipped.add(CRITERION_GOODS_READY);
        } else if (!input.goodsReady()) {
            return new Decision(Outcome.DEFER, Reason.GOODS_NOT_READY, 0L, WakeEvent.GOODS_READY,
                    skipped, etaMin, enterTsMs, "货物未就绪，等 GOODS_READY 事件唤醒");
        }

        // ⑧ 时间闸门：熔断期间不拒单，只延后
        if (input.gateCutoff() == null) {
            skipped.add(CRITERION_GATE_CUTOFF);
        } else if (input.gateCutoff()) {
            return new Decision(Outcome.DEFER, Reason.GATE_CUTOFF, 0L, WakeEvent.GATE_RESUME, skipped,
                    etaMin, enterTsMs, "闸门熔断，等解除后恢复（熔断期间不拒单）");
        }

        return new Decision(Outcome.ACCEPT_TRIGGER_SCHEDULE, Reason.NONE, 0L, WakeEvent.NONE, skipped,
                etaMin, enterTsMs, "受理并触发调度");
    }

    // ───────────── 判据细节 ─────────────

    /**
     * 重复单：同一 {@code mmsi} ＋ 同一 {@code cargoType} ＋ 重量差 ≤ {@code weightTolKg}，
     * 且下单时间落在 {@code dupWindowSec} 内的既有<b>非终止态</b>订单（§3.1.3）。
     */
    public static boolean isDuplicate(OrderRequest request, List<PriorOrder> priorOrders,
            Policy policy, long nowMs) {
        if (request == null || priorOrders == null || request.goodsWeightKg() == null) {
            return false;
        }
        long windowMs = policy.dupWindowSec() * 1000L;
        for (PriorOrder prior : priorOrders) {
            if (prior.terminal()) {
                continue;
            }
            if (!request.mmsi().equals(prior.mmsi())
                    || !request.cargoType().equals(prior.cargoType())) {
                continue;
            }
            if (Math.abs(request.goodsWeightKg() - prior.goodsWeightKg()) > policy.weightTolKg()) {
                continue;
            }
            if (nowMs - prior.submitTsMs() <= windowMs) {
                return true;
            }
        }
        return false;
    }

    /** MMSI 为 9 位十进制数字。 */
    public static boolean isValidMmsi(String mmsi) {
        if (mmsi == null) {
            return false;
        }
        String trimmed = mmsi.trim();
        if (trimmed.length() != 9) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<EtaLowerBound.NestPosition> toNestPositions(List<NestPoint> nests) {
        List<EtaLowerBound.NestPosition> result = new ArrayList<>(nests.size());
        for (NestPoint nest : nests) {
            result.add(new EtaLowerBound.NestPosition(nest.nestId(), nest.lon(), nest.lat()));
        }
        return result;
    }
}
