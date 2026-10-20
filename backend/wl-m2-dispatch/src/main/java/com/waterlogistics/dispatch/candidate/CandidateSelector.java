package com.waterlogistics.dispatch.candidate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.param.SysParamLoader;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 候选方案综合择优（模块二 功能，10-16 简化版）。
 *
 * <p>从 DB 读空闲健康无人机与可用机巢，组装 {@link CandidateInput}，
 * 调序 3 的 {@link CandidateGenerator} 生成候选三元组，按距离最近择优
 * （简化目标：只看机巢→取货点直线距离；多目标精算顺延）。
 *
 * <p>约束：不做业务判断，只做数据装配 + 择优；阈值读 sys_param，不硬编码。
 */
@Service
public class CandidateSelector {

    private static final String DEFAULT_STATUS_IDLE = "空闲";
    private static final String DEFAULT_NEST_AVAILABLE = "可用";

    private final DroneMapper droneMapper;
    private final NestMapper nestMapper;
    private final SysParamLoader sysParamLoader;
    private final CandidateGenerator candidateGenerator;

    public CandidateSelector(DroneMapper droneMapper, NestMapper nestMapper,
                             SysParamLoader sysParamLoader) {
        this.droneMapper = droneMapper;
        this.nestMapper = nestMapper;
        this.sysParamLoader = sysParamLoader;
        this.candidateGenerator = new CandidateGenerator();
    }

    /**
     * 为指定订单选最优候选组合。
     *
     * @return 最优候选；无可用候选时返回 null
     */
    public CandidateGenerator.CandidateCombo selectBest(OrderTaskEntity order) {
        List<DroneEntity> drones = droneMapper.selectList(
            new LambdaQueryWrapper<DroneEntity>()
                .eq(DroneEntity::getStatus, DEFAULT_STATUS_IDLE));
        List<NestEntity> nests = nestMapper.selectList(
            new LambdaQueryWrapper<NestEntity>()
                .eq(NestEntity::getStatus, DEFAULT_NEST_AVAILABLE));

        if (drones.isEmpty() || nests.isEmpty()) {
            return null;
        }

        CandidateInput input = buildInput(order, drones, nests);
        CandidateGenerator.Params params = new CandidateGenerator.Params(
            sysParamLoader.getDouble("minBatteryPercent", 30.0),
            sysParamLoader.getDouble("maxNestDistanceKm", 5.0));

        List<CandidateGenerator.CandidateCombo> combos = candidateGenerator.generate(input, params);
        if (combos.isEmpty()) {
            return null;
        }
        // CandidateGenerator 已按距离升序，取第一个即为最优（简化）
        return combos.get(0);
    }

    private CandidateInput buildInput(OrderTaskEntity order,
                                      List<DroneEntity> drones,
                                      List<NestEntity> nests) {
        double pickupLon = order.getShipLon() != null ? order.getShipLon().doubleValue() : 0.0;
        double pickupLat = order.getShipLat() != null ? order.getShipLat().doubleValue() : 0.0;
        double payloadKg = order.getGoodsWeight() != null ? order.getGoodsWeight().doubleValue() : 0.0;

        List<CandidateInput.NestView> nestViews = new ArrayList<>();
        for (NestEntity n : nests) {
            List<String> allowed = n.getSupportedModels() != null
                ? Arrays.asList(n.getSupportedModels().split(","))
                : List.of();
            nestViews.add(new CandidateInput.NestView(
                n.getNestId(),
                n.getLon() != null ? n.getLon().doubleValue() : 0.0,
                n.getLat() != null ? n.getLat().doubleValue() : 0.0,
                allowed));
        }

        List<CandidateInput.DroneView> droneViews = new ArrayList<>();
        for (DroneEntity d : drones) {
            droneViews.add(new CandidateInput.DroneView(
                d.getDroneId(),
                d.getCurrentNestId() != null ? d.getCurrentNestId() : d.getHomeNestId(),
                d.getModel(),
                d.getStatus(),
                d.getBattery() != null ? d.getBattery().doubleValue() : 0.0,
                d.getMaxPayload() != null ? d.getMaxPayload().doubleValue() : 0.0,
                // 机型能力两列（10-20 B1）：DB 缺失时给 NaN 表示"未登记"，
                // 由 FeasibilityJudge 按回退口径处理——这里既不臆造 0，也不填默认值冒充真值
                optionalDouble(d.getWindLimitMs()),
                optionalDouble(d.getCruiseSpeedMs())));
        }

        return new CandidateInput(pickupLon, pickupLat, payloadKg, nestViews, droneViews);
    }

    /** {@code BigDecimal} → {@code double}；为 {@code null} 时给 {@code NaN}（＝未登记） */
    private static double optionalDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : Double.NaN;
    }
}
