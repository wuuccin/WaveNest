package com.waterlogistics.dispatch.alert;

import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.WindSnapshot;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.AlertType;
import com.waterlogistics.contract.state.OrderStatus;
import com.waterlogistics.dispatch.weather.WeatherSnapshotStore;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 返航复核输入组装器（F1 · 2026-10-23）。
 *
 * <p>把 {@link AlertEventMessage}（检测层消息）＋ {@code order_task}（订单表）＋
 * {@code drone}（无人机表）拼成算法层 {@link HomecomingInput}——
 * <b>契约层与算法层已有的类型直接用，不自造同义结构</b>（《10-23 清单》F1 边界 4）。
 *
 * <h3>字段来源逐项（取不到的字段不臆造，返回 null 交调用方记日志跳过）</h3>
 *
 * <table border="1">
 *   <caption>§7.3 输入表 → 本侧来源</caption>
 *   <tr><th>字段</th><th>来源</th><th>缺失处置</th></tr>
 *   <tr><td>eventId</td><td>告警 {@code eventId}</td><td>告警必带</td></tr>
 *   <tr><td>triggerSource</td><td>常量 {@code ALERT}（告警驱动）</td><td>—</td></tr>
 *   <tr><td>taskId</td><td>{@code order_task} 无独立任务号列，以订单号兼任</td><td>订单必在</td></tr>
 *   <tr><td>orderId／droneId</td><td>告警消息 ＋ 订单表（告警未带机号时回落订单 {@code drone_id}）</td>
 *       <td>两处皆缺 → 跳过</td></tr>
 *   <tr><td>homeNestId</td><td>{@code drone.home_nest_id}（订单表无此列）</td><td>缺 → 跳过</td></tr>
 *   <tr><td>position</td><td><b>告警自带 {@code lon/lat}</b>（T1-3 填入的告警目标位置，失联为最后已知位置）；
 *       缺失或非法时才回落订单会面点（具名局部变量 ＋ 有限／范围校验）</td><td>两者皆缺 → 跳过</td></tr>
 *   <tr><td>batteryPercent</td><td><b>仅低电量取告警 {@code currentValue}</b>（检测层电量%），无值时可回落
 *       {@code drone.battery}；<b>非低电量告警（尤其失联）不复用电量</b>——电量不可得即跳过，
 *       不把「上一次已知电量」当成当前电量（MEDIUM.7）</td><td>不可得 → 跳过</td></tr>
 *   <tr><td>homewardEnergyBaselinePercent</td><td><b>B 组返程精算未接</b>——演示期取
 *       {@code e10ReserveRatio}×100 同值（键只读一次），A1 判据退化为「电量 ≥ 2×储备」；
 *       B 组接链后改为精算值注入（此处注释即接链点）</td><td>必有（键有默认）</td></tr>
 *   <tr><td>cargoOnboard</td><td><b>订单机号与告警机号一致，且订单仍处交付前</b>
 *       （已派发／去程中／末段交接中）；返航中／已完成等视为已交付（任务 21）</td><td>推导得出</td></tr>
 *   <tr><td>windSnapshot</td><td>M2 气象快照中距该位置<b>最近且新鲜</b>的一条（{@code WeatherSnapshotStore.nearest}）；
 *       风速／风向非有限或越界时按缺省处理，不把 NaN 塞进 {@link WindSnapshot}（MEDIUM.8）</td>
 *       <td>无 → null（§7.3「无风况快照按固定裕度系数 k 判」）</td></tr>
 *   <tr><td>abortReason</td><td>告警类型中文值</td><td>—</td></tr>
 *   <tr><td>commCovered</td><td>失联 {@code false}、其余 {@code true}</td>
 *       <td>—（FALSE 仅作风险标记不作否决，《84》§7.4）</td></tr>
 * </table>
 *
 * <p>候选他巢台账（{@code NestCandidate} 列表）不由本类产出：第二级判据需要「飞到他巢的
 * 返程能耗」，须 B 组精算供给，当前接不上——调用方不传时由 {@code HomecomingService}
 * 从 {@code nest}＋{@code occupancy_slot} 组装粗估台账，本类<b>不伪造能耗</b>。
 */
@Component
public class HomecomingInputAssembler {

    private static final Logger log = LoggerFactory.getLogger(HomecomingInputAssembler.class);

    private final SysParamLoader sysParamLoader;
    private final WeatherSnapshotStore weatherStore;

    public HomecomingInputAssembler(SysParamLoader sysParamLoader, WeatherSnapshotStore weatherStore) {
        this.sysParamLoader = sysParamLoader;
        this.weatherStore = weatherStore;
    }

    /**
     * 组装返航复核输入。
     *
     * @return 可用的 {@link HomecomingInput}；必填字段取不到时返回 {@code null}
     *         （已记警告日志，调用方直接跳过，不臆造字段）
     */
    public HomecomingInput build(AlertEventMessage message, OrderTaskEntity order, DroneEntity drone) {
        // MEDIUM.9：先判空再解引用，坏消息不得抛 NPE
        if (message == null) {
            log.warn("返航复核跳过：告警消息为空");
            return null;
        }
        if (order == null) {
            log.warn("返航复核跳过：订单为空 eventId={} orderId={}",
                    message.eventId(), message.orderId());
            return null;
        }
        if (drone == null || drone.getHomeNestId() == null || drone.getHomeNestId().isBlank()) {
            log.warn("返航复核跳过：查不到无人机或无归属机巢 droneId={} eventId={}",
                    message.droneId(), message.eventId());
            return null;
        }

        // MEDIUM.5（告警）：告警只带 orderId 不带 droneId 时，机号回落订单绑定机
        String droneId = isBlank(message.droneId()) ? order.getDroneId() : message.droneId();
        if (isBlank(droneId)) {
            log.warn("返航复核跳过：告警与订单均无机号 orderId={} eventId={}",
                    order.getOrderId(), message.eventId());
            return null;
        }

        Optional<GeoPoint> alertPoint = alertPositionOf(message);
        Optional<GeoPoint> meetPoint = meetPositionOf(order);
        if (alertPoint.isEmpty() && meetPoint.isEmpty()) {
            log.warn("返航复核跳过：告警无位置且订单 {} 无会面点坐标（未派发无从知机位），不臆造位置 eventId={}",
                    order.getOrderId(), message.eventId());
            return null;
        }

        Double battery = batteryOf(message, drone);
        if (battery == null || !Double.isFinite(battery) || battery < 0 || battery > 100) {
            log.warn("返航复核跳过：电量不可得（告警类型={} 当前值={}，drone 表最近电量={}）eventId={}",
                    message.alertType(), message.currentValue(), drone.getBattery(), message.eventId());
            return null;
        }

        // MEDIUM.3（可维护）：位置用具名局部变量，避免嵌套三元与重复解引用
        GeoPoint position = alertPoint.isPresent() ? alertPoint.get() : meetPoint.get();
        boolean lostLink = AlertType.LOST_LINK.getValue().equals(message.alertType());
        // MEDIUM.4（可维护）：e10ReserveRatio 在本次组装内只读一次
        double reservePct = reservePct();
        boolean cargoOnboard = cargoOnboard(order, droneId);

        return new HomecomingInput(
                message.eventId(),
                "ALERT",
                order.getOrderId(),
                order.getOrderId(),
                droneId,
                drone.getHomeNestId(),
                position,
                battery,
                reservePct,
                cargoOnboard,
                null,
                windSnapshot(position.lon(), position.lat()),
                message.alertType(),
                message.timestamp(),
                lostLink ? Boolean.FALSE : Boolean.TRUE);
    }

    /**
     * 阈值参数：能落键的从 {@code sys_param} 读，无键的用 {@code HomecomingParams}
     * 建议值并在上表注明（不臆造换算，见 {@code e10HystExitRatio} 待拍板记录）。
     *
     * <ul>
     *   <li>{@code e10ReserveRatio}(0.20 比率) ×100 → 储备裕度 20.0 百分点；</li>
     *   <li>解除裕度取同值＝等价「不迟滞」（退出＝进入，迟滞语义未被键覆盖）；</li>
     *   <li>A5 兜底 {@code lowBatteryTrigger}(20)——2026-10-18 口径：与 G 组告警共用一键；</li>
     *   <li>{@code e10MarginK}(1.2) 固定裕度系数（仅无风况快照时生效）；</li>
     *   <li>{@code e10NestAvailFactor}(0.8)／{@code e10SlotBuffer}(58000)／{@code e10SlotHold}(58000)
     *       已落键（011），此处从 {@code sys_param} 读，读不到时取同名建议值；</li>
     *   <li>{@code arrivalEtaMs} 传 null——由 {@code HomecomingService} 按「当前位置 → 原巢」
     *       距离／巡航速度补齐；补不齐则第二级时隙判据不可算（不谎报冲突）。</li>
     * </ul>
     */
    public HomecomingParams params() {
        double marginPct = reservePct();
        double marginK = sysParamLoader.getDouble("e10MarginK", 1.2);
        Double batteryFloor = sysParamLoader.getDouble("lowBatteryTrigger", 20.0);
        double nestAvailFactor = sysParamLoader.getDouble("e10NestAvailFactor",
                HomecomingParams.SUGGESTED_NEST_AVAIL_FACTOR);
        long slotBufferMs = sysParamLoader.getInt("e10SlotBuffer", 58_000);
        long slotHoldMs = sysParamLoader.getInt("e10SlotHold", 58_000);
        return new HomecomingParams(marginPct, marginPct, batteryFloor,
                nestAvailFactor, marginK, slotBufferMs, null, slotHoldMs);
    }

    /**
     * 电量口径（MEDIUM.7）：<b>只有低电量告警</b>的当前值才是电量；告警无当前值时可回落
     * {@code drone.battery}。<b>失联等非低电量告警一律不推断电量</b>——电量取不到即跳过复核，
     * 不把「上一次已知电量」当成实时电量去判是否回得来。
     */
    private static Double batteryOf(AlertEventMessage message, DroneEntity drone) {
        if (!AlertType.LOW_BATTERY.getValue().equals(message.alertType())) {
            return null;
        }
        if (message.currentValue() != null) {
            return message.currentValue();
        }
        return drone.getBattery() == null ? null : drone.getBattery().doubleValue();
    }

    /** 返程电量基线：B 组精算未接的演示退化（见类表），取储备裕度同值。 */
    private double reservePct() {
        return sysParamLoader.getDouble("e10ReserveRatio", 0.20) * 100;
    }

    /**
     * 是否未交付（任务 21）：订单机号与告警机号一致，且订单仍在交付前阶段
     * （已派发／去程中／末段交接中）；返航中及已完成等视为货已交付。
     */
    private static boolean cargoOnboard(OrderTaskEntity order, String droneId) {
        if (order.getDroneId() == null || !order.getDroneId().equals(droneId)) {
            return false;
        }
        try {
            OrderStatus status = OrderStatus.fromValue(order.getStatus());
            return status == OrderStatus.DISPATCHED
                    || status == OrderStatus.OUTBOUND
                    || status == OrderStatus.HANDOVER;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 告警自带位置；缺失或非有限／越界时为空，由调用方回落订单会面点。 */
    private static Optional<GeoPoint> alertPositionOf(AlertEventMessage message) {
        Double lon = message.lon();
        Double lat = message.lat();
        if (lon == null || lat == null
                || !Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0
                || lat < -90.0 || lat > 90.0) {
            return Optional.empty();
        }
        return Optional.of(new GeoPoint(lon, lat));
    }

    /** 订单会面点；缺任一坐标或非有限／越界时为空，不臆造位置。 */
    private static Optional<GeoPoint> meetPositionOf(OrderTaskEntity order) {
        if (order.getMeetLon() == null || order.getMeetLat() == null) {
            return Optional.empty();
        }
        double lon = order.getMeetLon().doubleValue();
        double lat = order.getMeetLat().doubleValue();
        if (!Double.isFinite(lon) || !Double.isFinite(lat)
                || lon < -180.0 || lon > 180.0
                || lat < -90.0 || lat > 90.0) {
            return Optional.empty();
        }
        return Optional.of(new GeoPoint(lon, lat));
    }

    /** 机位附近新鲜风况；无新鲜数据或数值非法时 null（§7.3 允许缺省，判定侧退 k 系数档）。 */
    private WindSnapshot windSnapshot(double lon, double lat) {
        Optional<com.waterlogistics.contract.mq.WeatherData> nearest =
                weatherStore.nearest(lon, lat, System.currentTimeMillis());
        if (nearest.isEmpty()) {
            return null;
        }
        com.waterlogistics.contract.mq.WeatherData weather = nearest.get();
        // MEDIUM.8：风向／风速非有限值不构造 WindSnapshot，避免构造器抛异常打断整条告警
        if (!Double.isFinite(weather.windSpeedMs()) || weather.windSpeedMs() < 0
                || !Double.isFinite(weather.windDirDeg())) {
            log.warn("风况快照数值非法，按缺省处理：风速={} 风向={}",
                    weather.windSpeedMs(), weather.windDirDeg());
            return null;
        }
        return new WindSnapshot(weather.windSpeedMs(), weather.windDirDeg(), weather.obsTs());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
