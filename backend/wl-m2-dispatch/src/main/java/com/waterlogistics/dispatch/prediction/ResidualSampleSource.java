package com.waterlogistics.dispatch.prediction;

import java.util.List;

/**
 * 残差样本来源（11-02 阶段二 5）——<b>卢肄文侧定义的消费口</b>，由吴可欣侧精算引擎实现。
 *
 * <h3>为什么在消费方定义接口</h3>
 * <p>残差只有引擎能算（它同时握有滤波状态、参考线几何与实测点），但把它们变成
 * 「可持久化、可复标、可回写 {@code sys_param}」的校准链路是本模块的职责。
 * 若本模块直接依赖 {@code PrecisePredictionEngine} 的具体方法，就把两人份的实现细节
 * 焊在一起了：引擎一改签名，校准链路跟着改；单测要造整台引擎才能验校准。
 *
 * <p>反过来在<b>消费方</b>声明接口（依赖倒置），双方只共享 {@link ResidualSample} 这一种
 * 数据形状：生产方实现本接口，消费方只认本接口。这与 M3 的
 * {@code TrajectorySnapshotPort}（M3 声明、M2 实现、wl-app 装配）同一手法——《包结构设计》§二。
 *
 * <h3>装配与缺省</h3>
 * <p>{@link PredictionCalibrationService} 以<b>可选依赖</b>方式注入：容器内没有实现时，
 * 校准链路整体退化为「不可用」（快照 {@code calibrationFactor} 为 {@code null}），
 * 预测主链不受任何影响。这样本 PR 可先合、吴的实现后到，不会出现「等他接口才能合并」的死锁。
 *
 * <h3>实现方契约</h3>
 * <ul>
 *   <li><b>不得抛异常</b>：本方法在预测刷新热路径上被调用，异常会打断整条预测链。
 *       内部异常应收敛为「返回已算出的部分」或空列表。</li>
 *   <li><b>必须幂等可重入</b>：同一船同一批样本可能被重复索取（消费方按
 *       {@code sampleTsMs} 去重），实现方不应因此改变内部状态。</li>
 *   <li><b>只返回未过期样本</b>：建议保留最近一轮观测窗口即可，无需全量历史。</li>
 * </ul>
 */
public interface ResidualSampleSource {

    /**
     * 取该船当前可用的残差样本。
     *
     * @param mmsi 目标船标识
     * @return 样本列表，按 {@code sampleTsMs} <b>升序</b>；无样本或该船未被跟踪时返回空列表
     *         （<b>返回 {@code null} 视为空</b>，消费方已容错）
     */
    List<ResidualSample> residualSamplesOf(String mmsi);
}
