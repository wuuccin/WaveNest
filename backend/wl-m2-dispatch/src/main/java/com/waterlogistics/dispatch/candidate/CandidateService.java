package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.algo.candidate.FeasibilityJudge;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

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
     * @param taskDistKm   本次任务主航段距离（km，取货点→交货点，调用方按订单给）。
     *                    <p><b>口径（已修，原为文档-实现错位）</b>：{@link FeasibilityJudge#judge}
     *                    现按三段估算总里程——{@code 2×taskDistKm}（取↔交）＋
     *                    {@code 2×combo.distKm()}（巢↔取，由生成器用 haversine 算好随组合透传）。
     *                    交货→巢按保守上界并入，不再漏算机巢段。
     * @param worstWindMs  现场最不利风速（m/s，取自 data.weather 按位置取最不利）
     * @param genParams    候选生成参数
     * <p><b>入口非有限值守卫（评审 HIGH.8 · 硬判据 3）</b>：{@code taskDistKm} 与
     * {@code worstWindMs} 必须有限且非负，否则直接抛 {@link IllegalArgumentException}。
     *
     * <p>为什么必须在入口拦：{@code FeasibilityJudge} 的比较用的是 {@code >}，
     * <b>{@code NaN} 参与任何比较都返回 {@code false}</b>——
     * 于是「最不利风 NaN」会通过风况闸、「任务距离 NaN」会算出 {@code remaining≈NaN}
     * 再被「{@code NaN < 保留值} ＝ false」放行，结果是<b>全部候选静默判为 FEASIBLE</b>，
     * 抗风与电量两道闸同时失效。负距离同理会让能耗算成负数、电量闸永远通过。
     * 上游没给数就只能判不出来——宁可抛，不可默认放行。
     *
     * @param judgeParams  可行性判定参数
     * @throws IllegalArgumentException {@code taskDistKm}／{@code worstWindMs} 为 NaN、±Infinity 或负数
     */
    public List<FeasibilityJudge.FeasibilityResult> generateAndJudge(CandidateInput input,
                                                                     double taskDistKm,
                                                                     double worstWindMs,
                                                                     CandidateGenerator.Params genParams,
                                                                     FeasibilityJudge.Params judgeParams) {
        requireFiniteNonNegative("taskDistKm", taskDistKm);
        requireFiniteNonNegative("worstWindMs", worstWindMs);
        // 显式 null 守卫（Open CodeReview）：坏 null 在入口报错并指名参数，
        // 不让它落到算法层变成来历不明的 NPE
        Objects.requireNonNull(input, "input 不能为 null");
        Objects.requireNonNull(genParams, "genParams 不能为 null");
        Objects.requireNonNull(judgeParams, "judgeParams 不能为 null");
        // 委托本类 generate（不走 generator 直调）：将来在 generate 上加的横切行为
        // （校验/缓存/指标）才会同时作用于判定路径，避免长出两条分叉管线
        List<CandidateGenerator.CandidateCombo> combos = generate(input, genParams);
        return combos.stream()
                .map(c -> judge.judge(c, taskDistKm, worstWindMs, input.payloadKg(), judgeParams))
                .toList();
    }

    /**
     * 判据入口守卫：非有限值（{@code NaN}／±Infinity）与负值一律拒绝。
     *
     * <p>抛 {@link IllegalArgumentException} 而不是返回空列表——调用方传了坏数，
     * 那是上游缺陷，静默返回空会被误读成「没有可用候选」。
     */
    static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " 必须是有限非负数，实际 " + value
                            + "——NaN/Infinity 会让可行性判定全部静默通过（NaN 参与比较恒为 false）");
        }
    }
}
