package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.rendezvous.port.DroneContext;
import com.waterlogistics.rendezvous.port.DroneContextPort;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.param.SysParamLoader;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import org.springframework.stereotype.Component;

/**
 * 执行机台账端口实现（M2 · 吴可欣）—— 6a 会面重算所需的 {@code droneId} 反查。
 *
 * <p><b>端口反转（《42-包结构设计》§3.3）</b>：接口由 <b>M3 声明</b>
 * （{@code rendezvous/port/DroneContextPort}）、<b>M2 提供实现</b>、
 * <b>{@code wl-app} 装配</b> —— M3 全程不知道 M2 存在。本类与
 * {@code prediction/TrajectorySnapshotAdapter} 是 M2 侧仅有的两个向 M3 暴露的出口。
 *
 * <p><b>归属口径</b>：按《23-状态机定义》§7 约定 3，<b>飞行中归属不变</b>——
 * 迫停只改 {@code currentNestId}，{@code homeNestId} 不动。求解起点取
 * {@code currentNestId}，与 {@link DroneContext} 的 javadoc 一致。
 *
 * <p><b>巡航速度的口径说明（重要，勿当硬编码）</b>：
 * {@code drone} 表<b>没有</b>巡航速度列（只有 {@code model} 与
 * {@code maxEndurance}），而 {@code DroneContext} 要求正的巡航速度 m/s。
 * 按项目铁律「阈值与参数一律读 {@code sys_param}、不硬编码」，
 * 此处从 {@code sys_param} 的 {@code droneCruiseSpeedMs} 取，缺省 12.0
 * （与 M1 仿真器 {@code wavenest.sim.uav.speed-ms} 同值）。
 * <b>待办</b>：机型档案落地后应改为按 {@code model} 取档案速度，
 * 本键届时作废——在此之前不得把 12.0 写进算法层。
 */
@Component
public class DroneContextAdapter implements DroneContextPort {

    /** 巡航速度参数键；缺省值与 M1 仿真器 {@code wavenest.sim.uav.speed-ms} 对齐 */
    public static final String CRUISE_SPEED_KEY = "droneCruiseSpeedMs";

    private static final double DEFAULT_CRUISE_SPEED_MS = 12.0;

    private final DroneMapper droneMapper;

    private final NestMapper nestMapper;

    private final SysParamLoader sysParamLoader;

    public DroneContextAdapter(DroneMapper droneMapper, NestMapper nestMapper,
            SysParamLoader sysParamLoader) {
        this.droneMapper = droneMapper;
        this.nestMapper = nestMapper;
        this.sysParamLoader = sysParamLoader;
    }

    /**
     * 按执行机反查上下文：机位与机型速度归 M2 台账，故读库；
     * 组合标识 {@code comboId} 由「当前任务回溯」得出，
     * 当前调度链尚未建立「机 → 组合」反向索引，故<b>首版置 {@code null}</b>
     * （{@code DroneContext} javadoc 已约定取不到可为 null）。
     *
     * @param droneId 执行机实例
     * @return 反查结果；<b>机或归属机巢查无时返回 {@code null}</b>，
     *         由调用方按「拒绝求解」处理（端口契约要求）
     * @throws IllegalArgumentException {@code droneId} 为空/空白（调用方缺陷）
     */
    @Override
    public DroneContext resolve(String droneId) {
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("droneId 不能为空：调用方前置校验缺失");
        }
        DroneEntity drone = droneMapper.selectById(droneId);
        if (drone == null || drone.getHomeNestId() == null || drone.getHomeNestId().isBlank()) {
            return null;
        }
        String currentNestId = drone.getCurrentNestId();
        if (currentNestId == null || currentNestId.isBlank()) {
            currentNestId = drone.getHomeNestId();
        }
        NestEntity nest = nestMapper.selectById(currentNestId);
        if (nest == null || nest.getLon() == null || nest.getLat() == null) {
            currentNestId = drone.getHomeNestId();
            nest = nestMapper.selectById(currentNestId);
        }
        if (nest == null || nest.getLon() == null || nest.getLat() == null) {
            return null;
        }
        double cruiseSpeedMs = sysParamLoader.getDouble(CRUISE_SPEED_KEY, DEFAULT_CRUISE_SPEED_MS);
        return new DroneContext(
                drone.getDroneId(),
                drone.getHomeNestId(),
                currentNestId,
                nest.getLon().doubleValue(),
                nest.getLat().doubleValue(),
                cruiseSpeedMs,
                null);
    }
}