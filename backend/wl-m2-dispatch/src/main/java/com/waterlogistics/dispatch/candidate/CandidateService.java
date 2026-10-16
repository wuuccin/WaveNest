package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.algo.candidate.FeasibilityJudge;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模块二·候选生成与可行性服务（杨添爻，dispatch.candidate）。
 *
 * <p>职责：从 storage 取 drone/nest（演示期可先由内存 stub 提供），组装
 * {@link CandidateInput} 调 {@code algo.candidate} 纯计算，返回候选组合列表＋
 * 逐组合可行性判定（10-16：候选生成 + 可行性判断简化版成对交付）。
 * 本类不写算法——算法全在 wl-algo（无 Spring/DB，可独立单测）。
 *
 * <p>数据来源：16 号接张睿哲的 {@code SysParamLoader} 与 drone/nest 表；
 * 在那之前由 {@link #generate(CandidateInput)} 直接入参（联调友好）。
 */
@Service
public class CandidateService {

    private final CandidateGenerator generator = new CandidateGenerator();
    private final FeasibilityJudge judge = new FeasibilityJudge();

    /**
     * 由调用方直接给输入（演示/单测友好）。
     */
    public List<CandidateGenerator.CandidateCombo> generate(CandidateInput input) {
        return generate(input, CandidateGenerator.Params.defaults());
    }

    /**
     * 指定标定参数（正式路径：参数读 sys_param，由调用方注入）。
     */
    public List<CandidateGenerator.CandidateCombo> generate(CandidateInput input,
                                                            CandidateGenerator.Params params) {
        return generator.generate(input, params);
    }

    /**
     * 生成候选并对每个组合做简化可行性判断（10-16 成对口径）。
     *
     * @param input        候选生成输入
     * @param taskDistKm   本次任务主航段距离（km，取货点→交货点，调用方按订单给）
     * @param worstWindMs  现场最不利风速（m/s，取自 data.weather 按位置取最不利）
     * @param genParams    候选生成参数
     * @param judgeParams  可行性判定参数
     */
    public List<FeasibilityJudge.FeasibilityResult> generateAndJudge(CandidateInput input,
                                                                     double taskDistKm,
                                                                     double worstWindMs,
                                                                     CandidateGenerator.Params genParams,
                                                                     FeasibilityJudge.Params judgeParams) {
        List<CandidateGenerator.CandidateCombo> combos = generator.generate(input, genParams);
        return combos.stream()
                .map(c -> judge.judge(c, taskDistKm, worstWindMs, input.payloadKg(), judgeParams))
                .toList();
    }
}
