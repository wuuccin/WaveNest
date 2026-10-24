package com.waterlogistics.dispatch.alert;

import com.waterlogistics.algo.inflight.HomecomingInput;
import com.waterlogistics.algo.inflight.HomecomingParams;
import com.waterlogistics.algo.inflight.WindSnapshot;
import com.waterlogistics.contract.mq.AlertEventMessage;
import com.waterlogistics.contract.rest.GeoPoint;
import com.waterlogistics.contract.state.AlertType;
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
 *   <tr><td>orderId／droneId</td><td>告警消息 ＋ 订单表</td><td>缺订单号即跳过</td></tr>
 *   <tr><td>homeNestId</td><td>{@code drone.home_nest_id}（订单表无此列）</td><td>缺 → 跳过</td></tr>
 *   <tr><td>position</td><td><b>订单会面点</b> {@code meet_lon/meet_lat}——在途机的最近已知目标位置，
 *       M2 侧唯一可得坐标（遥测归 M4，不跨模块取）</td><td>缺（未派发）→ 跳过</td></tr>
 *   <tr><td>batteryPercent</td><td>低电量取告警 {@code currentValue}（检测层电量%）；
 *       失联消息无当前值，退 {@code drone.battery} 最近电量</td><td>两者皆缺 → 跳过</td></tr>
 *   <tr><td>homewardEnergyBaselinePercent</td><td><b>B 组返程精算未接</b>——演示期取
 *       {@code e10ReserveRatio}×100 同值，A1 判据退化为「电量 ≥ 2×储备」；
 *       B 组接链后改为精算值注入（此处注释即接链点）</td><td>必有（键有默认）</td></tr>
 *   <tr><td>cargoOnboard</td><td>订单表 {@code drone_id} 与告警机号一致（货随原机）</td><td>推导得出</td></tr>
 *   <tr><td>windSnapshot</td><td>M2 气象快照最新一条（{@code WeatherSnapshotStore.newest()}）</td>
 *       <td>无 → null（§7.3「无风况快照按固定裕度系数 k 判」）</td></tr>
 *   <tr><td>abortReason</td><td>告警类型中文值</td><td>—</td></tr>
 *   <tr><td>commCovered</td><td>失联 {@code false}、其余 {@code true}</td>
 *       <td>—（FALSE 仅作风险标记不作否决，《84》§7.4）</td></tr>
 * </table>
 *
 * <p>候选他巢台账（{@code NestCandidate} 列表）不由本类产出：第二级判据需要「飞到他巢的
 * 返程能耗」，须 B 组精算供给，当前接不上——调用方一律传空列表，第二级自然不准入
 * （降级到一／三级），候选审计为空即「台账未接」的可观察痕迹，<b>不伪造能耗</b>。
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
        if (drone == null || drone.getHomeNestId() == null || drone.getHomeNestId().isBlank()) {
            log.warn("返航复核跳过：查不到无人机或无归属机巢 droneId={} eventId={}",
                    message.droneId(), message.eventId());
            return null;
        }
        if (order.getMeetLon() == null || order.getMeetLat() == null) {
            log.warn("返航复核跳过：订单 {} 无会面点坐标（未派发无从知机位），不臆造位置 eventId={}",
                    order.getOrderId(), message.eventId());
            return null;
        }
        Double battery = batteryOf(message, drone);
        if (battery == null || !Double.isFinite(battery) || battery < 0 || battery > 100) {
            log.warn("返航复核跳过：电量不可得（告警当前值={}，drone 表最近电量={}）eventId={}",
                    message.currentValue(), drone.getBattery(), message.eventId());
            return null;
        }
        GeoPoint position = new GeoPoint(
                order.getMeetLon().doubleValue(), order.getMeetLat().doubleValue());
        boolean lostLink = AlertType.LOST_LINK.getValue().equals(message.alertType());
        return new HomecomingInput(
                message.eventId(),
                "ALERT",
                order.getOrderId(),
                order.getOrderId(),
                message.droneId(),
                drone.getHomeNestId(),
                position,
                battery,
                homewardEnergyBaselinePct(),
                order.getDroneId() != null && order.getDroneId().equals(message.droneId()),
                null,
                windSnapshot(),
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
     *   <li>{@code e10NestAvailFactor}／{@code e10SlotBuffer} 未落键：取建议值 0.8／58000ms，
     *       候选为空时两者不参与判定；</li>
     *   <li>{@code arrivalEtaMs} 传 null——ETA 须会面/精算供给，第二级时隙判据随之不可算
     *       （候选为空本就不可达第二级）。</li>
     * </ul>
     */
    public HomecomingParams params() {
        double marginPct = sysParamLoader.getDouble("e10ReserveRatio", 0.20) * 100;
        double marginK = sysParamLoader.getDouble("e10MarginK", 1.2);
        Double batteryFloor = sysParamLoader.getDouble("lowBatteryTrigger", 20.0);
        return new HomecomingParams(marginPct, marginPct, batteryFloor,
                0.8, marginK, 58_000L, null, 58_000L);
    }

    /** 低电量：告警当前值即电量%；失联：消息无当前值，退 drone 表最近电量。 */
    private Double batteryOf(AlertEventMessage message, DroneEntity drone) {
        if (AlertType.LOW_BATTERY.getValue().equals(message.alertType())
                && message.currentValue() != null) {
            return message.currentValue();
        }
        return drone.getBattery() == null ? null : drone.getBattery().doubleValue();
    }

    /** 返程电量基线：B 组精算未接的演示退化（见类表），取储备裕度同值。 */
    private double homewardEnergyBaselinePct() {
        return sysParamLoader.getDouble("e10ReserveRatio", 0.20) * 100;
    }

    /** 最新风况快照；无观测时 null（§7.3 允许缺省，判定侧退 k 系数档）。 */
    private WindSnapshot windSnapshot() {
        Optional<com.waterlogistics.contract.mq.WeatherData> newest = weatherStore.newest();
        return newest
                .map(w -> new WindSnapshot(w.windSpeedMs(), w.windDirDeg(), w.obsTs()))
                .orElse(null);
    }
}
