package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模块二·候选生成服务（杨添爻，dispatch.candidate）。
 *
 * <p>职责：从 storage 取 drone/nest（演示期可先由内存 stub 提供），组装
 * {@link CandidateInput} 调 {@code algo.candidate} 纯计算，返回候选组合列表。
 * 本类不写算法——算法全在 wl-algo（无 Spring/DB，可独立单测）。
 *
 * <p>数据来源：16 号接张睿哲的 {@code SysParamLoader} 与 drone/nest 表；
 * 在那之前由 {@link #generate(CandidateInput)} 直接入参（联调友好）。
 */
@Service
public class CandidateService {

    private final CandidateGenerator generator = new CandidateGenerator();

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
}
