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

import java.util.List;

/**
 * 候选方案综合择优（模块二 功能，10-16 简化版）。
 *
 * <p>从 DB 读空闲健康无人机与可用机巢，组装 {@link CandidateInput}，
 * 调序 3 的 {@link CandidateGenerator} 生成候选三元组，按距离最近择优
 * （简化目标：只看机巢→取货点直线距离；多目标精算顺延）。
 *
 * <p>约束：不做业务判断，只做数据装配 + 择优；阈值读 sys_param，不硬编码。
 *
 * <p><b>查询与组装口径都不在本类（逐字审查）</b>：表格条件来自
 * {@link CandidateQueries}，实体→输入的映射来自 {@link CandidateInputAssembler}。
 * 本类只剩「查 → 生成 → 取第一个」三行，任何人改口径不必再记着改两处。
 */
@Service
public class CandidateSelector {

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
     * @throws IllegalArgumentException {@code order} 为 {@code null}（调用方缺陷）
     * @throws IllegalStateException    订单缺 {@code ship_lon}/{@code ship_lat}/{@code goods_weight}
     *                                  （MEDIUM.13：不填 0 兜底，避免造出 (0,0) 取货点）
     */
    public CandidateGenerator.CandidateCombo selectBest(OrderTaskEntity order) {
        List<DroneEntity> drones = droneMapper.selectList(CandidateQueries.idleAndHealthyDrones());
        List<NestEntity> nests = nestMapper.selectList(CandidateQueries.availableNests());

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
        // CandidateGenerator 已按距离升序，取第一个即为最优（简化）。
        // 注意（Open CodeReview）：该排序契约未在本类与生成器之间显式声明，
        // 生成器排序键变更（如接入 rank_key）时这里须同步——生产主链已不走本类
        // （DispatchOrchestrator 走 CandidateFeasibilityPlanner），本类保留待组长裁定去留。
        return combos.get(0);
    }

    /**
     * 委托唯一组装实现（Open CodeReview：两份同源副本必须收敛成一份）。
     *
     * <p>保留这一层薄封装而不是直接内联，是为了让本类后续若被删除时
     * 调用点集中在一处（本类如被裁定删除，只需删这一处调用）。
     */
    private CandidateInput buildInput(OrderTaskEntity order,
                                      List<DroneEntity> drones,
                                      List<NestEntity> nests) {
        return CandidateInputAssembler.assemble(order, drones, nests);
    }
}
