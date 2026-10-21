package com.waterlogistics.collector.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 基础数据台账端点（10-21 C4，杨添爻）。
 *
 * <p><b>这是什么</b>：机队与机巢的只读台账，供前端监控台拉基础数据
 * （P0-B 前半：此前 {@code /api/drones}、{@code /api/nests} 均 404）。
 *
 * <p><b>为什么落在 M1 控制层</b>：按《10-21 任务清单》C4 口径「只动 M1 控制层，不碰 M2」——
 * 台账端点是纯展示查询，不参与派发判定；M1 已依赖 {@code wl-storage}，
 * 直接读表即可，不经过 M2 的业务服务（选机判定归 {@code CandidateSelector}，本类不越界）。
 *
 * <p><b>路径纪律</b>：一律取契约常量 {@link ApiPath#DRONES}／{@link ApiPath#NESTS}，
 * <b>不写字面量</b>（路径属契约，改动须由收口方裁决——与 {@code OrderController} 同一口径）。
 *
 * <p><b>为什么必须带 {@code windLimitMs}／{@code cruiseSpeedMs} 两列</b>：
 * 10-20 B1 已让机型抗风上限进入候选判定（同风速下 M30T 淘汰、M400 保留），
 * 监控台若看不到这两列，「抗风分档」在界面上无从核对。两列取值与
 * {@code 003_seed_drone_nest.sql} 一致（M30T＝10.00/12.00，M400＝12.00/15.00）；
 * <b>为 {@code null} 表示机型能力未登记</b>（B1 口径：候选端按回落处理），原样透传不造默认值。
 *
 * <p>本类只做查询转发与装配，无业务判定；映射函数为纯静态，可独立单测。
 */
@RestController
public class FleetController {

    private final DroneMapper droneMapper;
    private final NestMapper nestMapper;

    public FleetController(DroneMapper droneMapper, NestMapper nestMapper) {
        this.droneMapper = droneMapper;
        this.nestMapper = nestMapper;
    }

    /** 机队台账（GET {@link ApiPath#DRONES}），按 droneId 升序保证展示稳定。 */
    @GetMapping(ApiPath.DRONES)
    public List<DroneRow> drones() {
        List<DroneEntity> list = droneMapper.selectList(
                new LambdaQueryWrapper<DroneEntity>().orderByAsc(DroneEntity::getDroneId));
        return list.stream().map(FleetController::toRow).toList();
    }

    /** 机巢台账（GET {@link ApiPath#NESTS}），按 nestId 升序保证展示稳定。 */
    @GetMapping(ApiPath.NESTS)
    public List<NestRow> nests() {
        List<NestEntity> list = nestMapper.selectList(
                new LambdaQueryWrapper<NestEntity>().orderByAsc(NestEntity::getNestId));
        return list.stream().map(FleetController::toRow).toList();
    }

    /** 机队台账行（字段与 {@code drone} 表一一对应；{@code null}＝未登记，原样透传）。 */
    public record DroneRow(
            String droneId,
            String homeNestId,
            String currentNestId,
            String positionType,
            String model,
            BigDecimal maxPayload,
            Integer maxEndurance,
            /** 机型抗风上限（m/s）——B1 抗风分档的展示列；null＝未登记 */
            BigDecimal windLimitMs,
            /** 机型巡航速度（m/s）；null＝未登记 */
            BigDecimal cruiseSpeedMs,
            BigDecimal battery,
            String healthStatus,
            String status) {
    }

    /** 机巢台账行（{@code supportedModels} 已拆为数组；空串/null 均给空数组）。 */
    public record NestRow(
            String nestId,
            BigDecimal lon,
            BigDecimal lat,
            Integer capacity,
            List<String> supportedModels,
            String owner,
            String status) {
    }

    /** 实体 → 台账行（纯映射，无副作用，独立单测）。 */
    static DroneRow toRow(DroneEntity d) {
        return new DroneRow(
                d.getDroneId(),
                d.getHomeNestId(),
                d.getCurrentNestId(),
                d.getPositionType(),
                d.getModel(),
                d.getMaxPayload(),
                d.getMaxEndurance(),
                d.getWindLimitMs(),
                d.getCruiseSpeedMs(),
                d.getBattery(),
                d.getHealthStatus(),
                d.getStatus());
    }

    /** 实体 → 台账行；{@code supportedModels} 按「逗号拆分＋去空白」，null/空给空数组。 */
    static NestRow toRow(NestEntity n) {
        List<String> models;
        String raw = n.getSupportedModels();
        if (raw == null || raw.isBlank()) {
            models = List.of();
        } else {
            models = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return new NestRow(
                n.getNestId(),
                n.getLon(),
                n.getLat(),
                n.getCapacity(),
                models,
                n.getOwner(),
                n.getStatus());
    }
}
