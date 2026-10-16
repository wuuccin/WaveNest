package com.waterlogistics.contract.mq;

import com.waterlogistics.contract.ws.ShipTrajFrame;
import java.util.List;

/**
 * 任务方案 {@code task.plan} —— <b>M2 → M4</b>，依据《22-接口契约》§1.2 与 §2。
 *
 * <p>§1.2 定内容、§2 定流向，原文两处一致：
 * 「任务方案（<b>机、巢、会面点、起飞时刻、航迹、船舶预测轨迹</b>）｜生产方 M2｜消费方 M4」，
 * 且「下发给 M4，<b>M4 不再向 M3 直取航迹</b>」——本对象是 M4 执行飞行的<b>唯一方案来源</b>。
 *
 * <h3>字段一律照《22》§2.1 投影表「只投影 X」裁剪，不得自行加回</h3>
 *
 * <ul>
 *   <li>会面点 ← {@code B_RendezvousSolution}：<b>只投影</b>
 *       {@code meetLon}／{@code meetLat}／{@code meetTMin}／{@code meetTMax}；
 *       {@code tauCoarse}、{@code snapshotVersion} <b>不下发</b>。</li>
 *   <li>无人机航迹 ← {@code B_FlyableRoute}：<b>只投影</b>
 *       {@code waypoints} ＋ {@code routeVersion}；
 *       精确能耗 {@code energyExact}、路径风险 {@code pathRisk}、
 *       迫降点标注 {@code landingPointsAlong}、避让标注 {@code avoidZones} <b>不下发</b>。</li>
 *   <li>起飞时刻 ← {@code B_TakeoffTimeResult}：<b>只投影</b>
 *       起飞时刻与「是否不可行」结论；
 *       {@code infeasibleReason}、{@code tauCoarseUsed} <b>不下发</b>。</li>
 *   <li>船舶预测轨迹 ← {@code PredictionSnapshot}：取
 *       {@link ShipTrajFrame}（其本身即已登记的模块层投影子集）。</li>
 * </ul>
 *
 * <p><b>被裁掉的字段消费方一律回算法层取，不得从本对象反推</b>
 * （《22》§2.1 投影表纪律①：投影＝裁字段，不改语义）。
 * 典型反例：E 组功能 17 的 NIS／σ 缩放判据须取 Redis 完整
 * {@code PredictionSnapshot}，不得用 {@link #shipTrack()} 反推。
 *
 * <p><b>判新旧一律看版本字段，不靠时间戳推测</b>（《81》§2.3 口径约定④）：
 * 航迹版本看 {@link #routeVersion()}，预测轨迹版本看
 * {@link ShipTrajFrame#version()}，两者各自独立递增。
 *
 * @param taskId          任务标识
 * @param orderId         订单标识（与《84》§七关联键需求一致；标识字段，
 *                        《22》§1.2 只枚举方案内容六项，未列标识，此处由收口方补齐）
 * @param droneId         执行机标识（＝「机」）
 * @param homeNestId      <b>归属机巢</b>（＝「巢」）。用 {@code homeNestId} 而非
 *                        {@code nestId}：归属稳定，迫停只改 {@code currentNestId}、
 *                        不改归属（《37》§5.2、《02》§2、《23》§7 约定 3）
 * @param meetLon         会面点经度，十进制度
 * @param meetLat         会面点纬度，十进制度
 * @param meetTMin        会面时刻窗下界，Unix 毫秒
 * @param meetTMax        会面时刻窗上界，Unix 毫秒
 * @param takeoffT        起飞时刻，Unix 毫秒（《81》§2.3 第 3 项 {@code takeoffT}；
 *                        实际起飞时刻由 D 组功能 7 定案，本字段为其定案结果）
 * @param takeoffFeasible 起飞可行性结论，投影自 {@code B_TakeoffTimeResult.feasible}
 * @param routeVersion    航迹版本（本次生成），重算即换新；重算比对用
 * @param waypoints       可飞航点序列，时间升序；M4 照此执行
 * @param shipTrack       船舶预测轨迹（{@code PredictionSnapshot} 的模块层投影子集），
 *                        供 M4 偏差比对；取不到时为 {@code null}
 */
public record TaskPlanMessage(
        String taskId,
        String orderId,
        String droneId,
        String homeNestId,
        double meetLon,
        double meetLat,
        long meetTMin,
        long meetTMax,
        long takeoffT,
        boolean takeoffFeasible,
        String routeVersion,
        List<RouteWaypoint> waypoints,
        ShipTrajFrame shipTrack) {

    /** 本对象对应的 MQ 主题 */
    public static final String TOPIC = TopicName.TASK_PLAN;
}
