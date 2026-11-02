package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
            nestViews.add(new CandidateInput.NestView(
                    n.getNestId(),
                    n.getLon().doubleValue(),
                    n.getLat().doubleValue(),
                    splitModels(n.getSupportedModels())));
        }

        List<CandidateInput.DroneView> droneViews = new ArrayList<>(drones.size());
        for (DroneEntity d : drones) {
            // 归属巢不在本轮候选集里（坐标缺失被跳过 / 巢不可用 / 巢号为空）时，
            // 这架机的候选<b>必然</b>在生成端被 nestMap.get(nestId) == null 丢弃。
            // 逐字审查：原来仍把它塞进 droneViews，生成端静默 continue——
            // 「机明明空闲健康却不出候选」在现场无从解释。这里提前跳过并 warn，
            // 把「是哪架机、挂在哪个巢」显式写进日志。
            String nestId = d.getCurrentNestId() != null ? d.getCurrentNestId() : d.getHomeNestId();
            if (nestId == null || nestId.isBlank() || !usableNestIds.contains(nestId)) {
                log.warn("无人机 {} 的归属机巢 {} 本轮不可用（巢缺失/无坐标/未在可用列表），"
                                + "本轮不生成该机候选——请核对该机 current_nest_id/home_nest_id",
                        d.getDroneId(), nestId);
                continue;
            }
            droneViews.add(new CandidateInput.DroneView(
                    d.getDroneId(),
                    nestId,
                    d.getModel(),
                    d.getStatus(),
                    // battery/maxPayload 缺失按 0.0＝有意保守（缺数据当"没电/载不动"，被闸淘汰）。
                    // 切不可学 windLimit 的 NaN 口径——NaN 参与 > / < 比较恒 false，
                    // 会让没电的机器通过电量闸（那才是危险方向）。缺失数据在台账里补。
                    d.getBattery() != null ? d.getBattery().doubleValue() : 0.0,
                    d.getMaxPayload() != null ? d.getMaxPayload().doubleValue() : 0.0,
                    // 机型能力两列：DB 缺失时给 NaN 表示"未登记"，
                    // 由 FeasibilityJudge 按保守回落口径处理——既不臆造 0，也不填默认值冒充真值
                    optionalDouble(d.getWindLimitMs()),
                    optionalDouble(d.getCruiseSpeedMs())));
        }

        return new CandidateInput(pickupLon, pickupLat, payloadKg, nestViews, droneViews);
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
