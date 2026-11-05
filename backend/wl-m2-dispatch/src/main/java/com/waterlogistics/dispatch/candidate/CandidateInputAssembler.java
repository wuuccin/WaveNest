package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 候选输入组装器——{@code CandidateFeasibilityPlanner} 与 {@code CandidateSelector}
 * 共用的唯一组装实现（Open CodeReview：两份同源副本必须收敛成一份，
 * 否则每次评审修复都要改两处、漏一处）。
 *
 * <p>职责边界：mapper 查询（含 health/status 过滤口径）留在各调用方，
 * 本类只负责<b>查出来的行 → {@link CandidateInput}</b> 的纯组装。
 */
public final class CandidateInputAssembler {

    private static final Logger log = LoggerFactory.getLogger(CandidateInputAssembler.class);

    /** {@code drone.position_type} 的「停在自己巢里」取值（001_init.sql §5.2）。 */
    static final String POSITION_AT_HOME = "原巢";

    private CandidateInputAssembler() {
    }

    /**
     * 组装候选输入。
     *
     * @param order 订单（<b>不得为 null</b>；取货点与载荷从订单取）
     * @param drones 已按「空闲＋健康」查出的无人机行
     * @param nests  已按「可用」查出的机巢行
     * @throws IllegalArgumentException {@code order}/{@code drones}/{@code nests} 为 {@code null}
     *         （调用方缺陷，不是业务数据问题）
     * @throws IllegalStateException    订单 {@code ship_lon}/{@code ship_lat}/{@code goods_weight} 缺失
     *                                  （评审 MEDIUM.13：不填 0 兜底，避免造出 (0,0) 取货点）
     */
    public static CandidateInput assemble(OrderTaskEntity order,
                                          List<DroneEntity> drones,
                                          List<NestEntity> nests) {
        // 入参 null 守卫（逐字审查）：两个调用方里 CandidateSelector.selectBest 没有做
        // order == null 检查，直接落进来就是第 2 行 order.getShipLon() 的 NPE——
        // 消息里没有参数名，排障只能看栈帧猜。这里显式指名，区分「调用方传错」
        // 与下面的「订单数据缺字段」两类错误。
        // 用 IllegalArgumentException 而非 Objects.requireNonNull：后者只会抛 NPE，
        // 与本方法声明的契约（调用方缺陷＝IAE）不一致，且消息格式不可控。
        requireNonNullArg("order", order);
        requireNonNullArg("drones", drones);
        requireNonNullArg("nests", nests);

        // 取货点与载荷缺失即快速失败（评审 MEDIUM.13）：原 0.0 兜底把取货点挪到
        // 几内亚湾 (0,0)，距离全 9000+ km，候选被 maxNestDistanceKm 全数过滤——
        // 现场只看到「无可用候选」，真实原因（订单缺经纬度）被埋掉。宁可抛，让上游修数据。
        requirePresentOrderField("ship_lon", order.getShipLon());
        requirePresentOrderField("ship_lat", order.getShipLat());
        requirePresentOrderField("goods_weight", order.getGoodsWeight());
        double pickupLon = order.getShipLon().doubleValue();
        double pickupLat = order.getShipLat().doubleValue();
        double payloadKg = order.getGoodsWeight().doubleValue();

        // ── 货物所在巢（11-05 新增）：先建巢索引，再按 cargoNestId 取货点 ──
        // 索引只收「坐标齐全」的巢——缺坐标的巢既不能当货点也不能当候选起点，
        // 建索引时一并过滤，后面两处判断共用同一份口径。
        Map<String, NestEntity> nestById = new LinkedHashMap<>();
        for (NestEntity n : nests) {
            if (n.getLon() == null || n.getLat() == null) {
                continue;
            }
            nestById.put(n.getNestId(), n);
        }

        String cargoNestId = normalizeCargoNestId(order.getCargoNestId());
        double cargoLon = Double.NaN;
        double cargoLat = Double.NaN;
        if (cargoNestId != null) {
            NestEntity cargoNest = nestById.get(cargoNestId);
            if (cargoNest == null) {
                // 货巢查不到坐标：记为「货点不可用」，生成端据此不产出任何组合。
                // 不抛——这是台账问题不是调用方缺陷；但必须 WARN，否则现场只看到
                // 「无可用候选」而不知道是货巢缺坐标（与 MED-BUG.12 同一类静默问题）。
                log.warn("订单 {} 的货物所在巢 {} 不在本轮可用巢中（不存在／状态不可用／缺经纬度），"
                        + "货点不可用，本轮不生成任何候选——请核对 nest 台账",
                        order.getOrderId(), cargoNestId);
            } else {
                cargoLon = cargoNest.getLon().doubleValue();
                cargoLat = cargoNest.getLat().doubleValue();
            }
        }

        List<CandidateInput.NestView> nestViews = new ArrayList<>(nests.size());
        // 已纳入的机巢（只有坐标齐全的才在里面）——下面组装无人机时用它判断归属巢是否可用
        Set<String> usableNestIds = new HashSet<>();
        for (NestEntity n : nests) {
            // 机巢坐标缺失 ⇒ 跳过该巢并 warn（原为填 (0,0) 静默被距离淘汰——
            // 结果方向虽安全，但现场同样看不出原因；跳过+日志让数据问题显形。
            // 机巢是系统台账数据而非订单数据，不宜抛单，缺失应修台账。）
            if (n.getLon() == null || n.getLat() == null) {
                log.warn("机巢 {} 缺少经纬度，本轮候选不纳入——请补全台账（nest.lon/lat）", n.getNestId());
                continue;
            }
            usableNestIds.add(n.getNestId());
            int occupied = occupiedCountOf(n, drones);
            nestViews.add(new CandidateInput.NestView(
                    n.getNestId(),
                    n.getLon().doubleValue(),
                    n.getLat().doubleValue(),
                    splitModels(n.getSupportedModels()),
                    freeSlotsOf(n, occupied),
                    occupied));
        }

        List<CandidateInput.DroneView> droneViews = new ArrayList<>(drones.size());
        for (DroneEntity d : drones) {
            // 归属巢不在本轮候选集里（坐标缺失被跳过 / 巢不可用 / 巢号为空）时，
            // 这架机的候选<b>必然</b>在生成端被丢弃。
            // 逐字审查：原来仍把它塞进 droneViews，生成端静默 continue——
            // 「机明明空闲健康却不出候选」在现场无从解释。这里提前跳过并 warn，
            // 把「是哪架机、挂在哪个巢」显式写进日志。
            //
            // ★ 11-05：巢×机枚举后「归属巢不可用」不再等于「这架机不可用」——
            // 它仍可以从<b>别的巢</b>出发。但机型能力、电量、载荷是 <b>机自身</b>的属性，
            // 与从哪个巢出发无关，故这里只在「巢号全空」时跳过（那时连机型校验都做不了），
            // 巢号存在但不在可用列表时保留该机，由生成端按巢×机组合判定。
            String nestId = d.getCurrentNestId() != null ? d.getCurrentNestId() : d.getHomeNestId();
            if (nestId == null || nestId.isBlank()) {
                log.warn("无人机 {} 既无 current_nest_id 也无 home_nest_id，本轮不生成该机候选"
                                + "——请核对该机台账",
                        d.getDroneId());
                continue;
            }
            droneViews.add(new CandidateInput.DroneView(
                    d.getDroneId(),
                    nestId,
                    d.getModel(),
                    d.getStatus(),
                    // ★ 11-05 起缺失给 NaN（＝未登记），不再给 0.0（MED-BUG.12）。
                    // 原「缺失按 0＝保守」的理由站不住：0.0 让这架机在电量闸与载荷闸上
                    // 双双被判死，而日志里看不出「它是因为台账漏填才落选」——
                    // 现场只会说「这架机明明满电却不出候选」。改成 NaN 后由判定端
                    // 按「未登记」显式处理并记 WARN，缺数据在台账里补。
                    optionalDouble(d.getBattery()),
                    optionalDouble(d.getMaxPayload()),
                    // 机型能力两列：DB 缺失时给 NaN 表示"未登记"，
                    // 由 FeasibilityJudge 按保守回落口径处理——既不臆造 0，也不填默认值冒充真值
                    optionalDouble(d.getWindLimitMs()),
                    optionalDouble(d.getCruiseSpeedMs())));
        }

        return new CandidateInput(pickupLon, pickupLat, payloadKg, nestViews, droneViews,
                cargoNestId, cargoLon, cargoLat);
    }

    /**
     * 该巢当前停着的机数（11-05 新增）。
     *
     * <p><b>口径</b>：同巢且 {@code position_type = '原巢'} 的机算占位。
     * 依据 {@code 001_init.sql} §5.2 的 {@code position_type} 取值
     * （{@code 原巢／空中／他巢临时停靠／迫降点}）：只有 {@code 原巢} 表示「停在自己的巢里」，
     * 空中飞着的不占位，迫降在野外的不占位。
     *
     * <p><b>数的是本轮查出的全部空闲健康机，不是「空位数」</b>：占用要数「停在巢里的全部机」
     * （含充电中、满载、告警），但组装器只拿得到空闲健康机那一批。故口径是
     * <b>占用数的下界</b>——真实占用只会更多。配合 {@link #freeSlotsOf} 的「占用未登记不拦」
     * 口径，方向是安全的：不会因为少算而误拦可用巢。
     *
     * <p><b>为什么在 Java 里数而不是让 SQL 带一列子查询</b>：见
     * {@code CandidateQueries.availableNestsWithOccupancy()} 的注释——子查询列依赖
     * MyBatis-Plus 表元数据，纯单测下直接 NPE，把候选主链的可测试性换掉了，不划算。
     *
     * @param nest   机巢
     * @param drones 本轮查出的无人机
     * @return 停在该巢的机数（≥0）
     */
    static int occupiedCountOf(NestEntity nest, List<DroneEntity> drones) {
        if (nest == null || nest.getNestId() == null || drones == null) {
            return 0;
        }
        int count = 0;
        for (DroneEntity d : drones) {
            if (!nest.getNestId().equals(d.getCurrentNestId())) {
                continue;
            }
            if (POSITION_AT_HOME.equals(d.getPositionType())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 巢容量 → 剩余可停数（11-05 新增）。
     *
     * <p>取值语义（与 {@code CandidateInput.NestView.freeSlots()} 的契约一致）：
     * <ul>
     *   <li>{@code capacity} 未登记（{@code null} 或 ≤0）→ {@link Integer#MAX_VALUE}＝不限制；</li>
     *   <li>占用数未登记（负数）→ {@code capacity}，即<b>按空巢放行</b>——
     *       台账里没有占用信息时不拦，缺口由日志提示；</li>
     *   <li>正常 → {@code max(0, capacity − occupied)}。</li>
     * </ul>
     *
     * <p><b>为什么在这里算而不是在算法层算</b>：占用数来自台账，算法包不查库；
     * 组装器是唯一同时握有 {@code capacity} 与无人机行的地方。
     *
     * @param n        机巢
     * @param occupied 已占用数（{@link #occupiedCountOf} 的结果；负数表示未登记）
     * @return 剩余可停数
     */
    static int freeSlotsOf(NestEntity n, int occupied) {
        Integer capacity = n.getCapacity();
        if (capacity == null || capacity <= 0) {
            return Integer.MAX_VALUE;
        }
        if (occupied < 0) {
            return capacity;
        }
        return Math.max(0, capacity - occupied);
    }

    /** 货巢 Id 归一：空白视作未指定。 */
    static String normalizeCargoNestId(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    /** {@code BigDecimal} → {@code double}；为 {@code null} 时给 {@code NaN}（＝未登记，不是 0）。 */
    private static double optionalDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : Double.NaN;
    }

    /** 入参 null 守卫：抛 {@link IllegalArgumentException}（调用方缺陷），消息里指名参数。 */
    private static void requireNonNullArg(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为 null：调用方前置校验缺失");
        }
    }

    /**
     * 订单必需字段缺失即抛（评审 MEDIUM.13）——不填 0.0 造出 (0, 0) 取货点。
     *
     * @throws IllegalStateException 字段为 {@code null}
     */
    private static void requirePresentOrderField(String field, BigDecimal value) {
        if (value == null) {
            throw new IllegalStateException(
                    "订单缺少必要字段 " + field + "，无法组装候选输入（不填 0 兜底，避免造出 (0,0) 取货点）");
        }
    }

    /**
     * 机巢允许机型串 → 机型列表：逐个 {@code trim} 并滤掉空串（评审 HIGH.7 / MEDIUM.18）。
     *
     * <p>不 trim 的后果：台账写 {@code "M30T, M400"} 时拆出 {@code " M400"}，
     * 与机型名精确比较不等，该机型被静默排除（「机巢明明支持却不出候选」）；
     * 空串拆出 {@code [""]} 会被当成「任何机型都不允许」，该巢候选静默清零。
     * 统一归一为空列表＝未登记＝不限制机型。
     */
    public static List<String> splitModels(String supportedModels) {
        if (supportedModels == null || supportedModels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(supportedModels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
