package com.waterlogistics.dispatch.prediction;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 预测快照端口适配器（M2 · 卢肄文）——<b>薄适配层</b>。
 *
 * <p><b>职责边界（只做这两件事，不承载算法）</b>：
 * <ol>
 *   <li>把 M3 需要的「船侧预测快照」翻译成本模块的 {@link PredictionSnapshot}；</li>
 *   <li>转发到 {@link PredictionService}。</li>
 * </ol>
 * 算法、状态维护、版本号全在 {@link PredictionService} 内，本类不做任何计算。
 *
 * <p><b>★ 端口反转（L-7 裁决方案 A）</b>：接口由 <b>M3 声明</b>
 * （{@code rendezvous/port/TrajectorySnapshotPort}）、<b>M2 提供实现</b>、
 * <b>{@code wl-app} 装配接线</b>；M3 全程不知道 M2 存在。
 *
 * <p><b>当前状态</b>：M3 尚未提交该接口（{@code wl-m3-rendezvous} 目前只有
 * {@code build.gradle.kts}），故本类暂为普通 Bean，<b>未</b> {@code implements} 任何接口。
 * 待接口签名公布后，只需在本类上加 {@code implements TrajectorySnapshotPort} 并补齐方法，
 * <b>改动被关在本类之内</b>（这正是做成薄适配层的目的）。M2 编译期依赖 M3 是允许的
 * 唯一例外（{@code wl-m2-dispatch/build.gradle.kts} 已引入 {@code wl-m3-rendezvous}）。
 *
 * <p>若接口迟迟未到，本类暴露的
 * {@link #snapshotOf(String)}／{@link #latestSnapshots()} 已足够让 M3 先按此形状对齐。
 */
@Component
public class TrajectorySnapshotAdapter {

    private final PredictionService predictionService;

    public TrajectorySnapshotAdapter(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 取指定船的最新预测快照。
     *
     * @param mmsi 船舶 MMSI
     * @return 快照；该船尚无观测时为空
     */
    public Optional<PredictionSnapshot> snapshotOf(String mmsi) {
        return predictionService.snapshotOf(mmsi);
    }

    /**
     * 取全部在跟踪船舶的快照。
     *
     * <p>供 M3 在「候选组合求解」时批量取用；调用方应自行用
     * {@link PredictionSnapshot#isStale(long)} 过滤过期快照。
     */
    public List<PredictionSnapshot> latestSnapshots() {
        return predictionService.latestSnapshots();
    }
}
