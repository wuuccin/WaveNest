package com.waterlogistics.contract.mq;

import com.waterlogistics.contract.rest.GeoPoint;

/**
 * 会面偏差样本 {@code rendezvous.deviation} —— <b>M3 → M2</b>，
 * 依据《86-G组算法方案》附「会面偏差样本对象级字段表（G-01）」。
 *
 * <p><b>这是数据回流，不是告警</b>：《86》§5 明写「偏差样本经数据回流行推 M2，
 * <b>不走告警通道</b>」，故主题前缀取 {@code rendezvous.*} 而<b>不并入 {@code alert.*}</b>
 * ——它不写 {@code alert} 表、不产级别。
 *
 * <p>功能归属：《03》§6 功能 19「会面偏差回流」，属 M3，G 组（宋冰鑫）产出。
 * 输入是 M3 末段交接完成确认中的计划／实际会面点与时刻；
 * 消费方 M2（按机型／机巢／风况／船速段分层统计，校准纠偏阈值与会面策略偏好）、
 * E 组功能 17（纠偏阈值标定）、A 组功能 2（预测模型参数标定）。
 *
 * <p><b>字段照 G-01 全表 13 项，一个不多一个不少</b>；命名沿用项目 camelCase 惯例，
 * 逐项映射见各 {@code @param} 注。
 *
 * <p><b>不写 {@code sys_param}</b>（跨组裁决 G-1）：阈值更新由 M2 决策侧统一发起。
 * <b>不判断处置</b>（G-6）：继续／改航／返航／终止归 E 组功能 9。
 *
 * <h3>TODO 冻结前复议（2026-10-16 收口方裁决，<b>尚未与全组确认</b>）</h3>
 *
 * <p>等 16 号各线合并、功能冻结前，须与<b>宋冰鑫（生产端字段名）</b>、<b>卢肄文（订阅前缀）</b>当面确认以下四条：
 *
 * <ol>
 *   <li>线上字段名一律 <b>camelCase</b>（与已合并的 {@code task.plan} 一致），非 G-01 文档的 snake_case</li>
 *   <li>G-01 的 {@code d_space} → {@code dSpaceM}、{@code d_time} → {@code dTimeS}：加单位后缀消歧</li>
 *   <li>G-01 的 {@code nest_id} → {@code homeNestId}：取归属机巢口径（与 {@code task.plan} 同字段）</li>
 *   <li>主题归 {@code rendezvous.*} 新前缀，<b>不并入 {@code alert.*}</b></li>
 * </ol>
 *
 * <p><b>切勿把 {@code dSpaceM} 改名为 {@code deviationDistance}／{@code deviation}</b>——
 * 本项目有两个都叫 deviation、语义<b>完全不同</b>的概念：
 * <ul>
 *   <li>本字段：<b>会面偏差</b>（功能 19）＝实际会面点 − 计划会面点，M3</li>
 *   <li>{@code RouteMeasurement.Projection.deviationDistance()}：<b>偏航距离</b>（功能 24）＝无人机偏离规划航线，M4</li>
 * </ul>
 * 两者单位同为米，MQ 上一旦混淆，会污染 M2 分层统计与 E 组纠偏标定，且极难排查。
 *
 * <p>改名须同步改 {@code ContractDoctrineTest.rendezvousDeviation_shouldFollowG01FieldTable}
 * （反射钉死 13 字段与顺序，改名必红——这正是该守卫的作用）。
 *
 * @param sampleId        偏差样本唯一标识（G-01 {@code sample_id}，UUID，由 G 组生成）
 * @param droneId         无人机实体标识（{@code drone_id}，来自任务方案 {@code droneId}）
 * @param orderId         订单实体标识（{@code order_id}，来自任务方案 {@code orderId}）
 * @param plannedRendezvous 计划会面点（{@code planned_rendezvous}，来自 M2 任务方案）
 * @param actualRendezvous  实际会面点（{@code actual_rendezvous}，来自 M3 末段交接完成确认）
 * @param plannedTime     计划会面时刻（{@code planned_time}，Unix 毫秒，取任务方案会面窗<b>中点</b>）
 * @param actualTime      实际交接时刻（{@code actual_time}，Unix 毫秒）
 * @param dSpaceM         空间偏差（米）（G-01 {@code d_space}＝Haversine(实际, 计划)，本组计算；
 *                        收口方加单位后缀 {@code M} 以消歧，G-01 原表未带单位）
 * @param dTimeS          时间偏差（秒）（G-01 {@code d_time}＝{@code actualTime − plannedTime}；
 *                        收口方加单位后缀 {@code S} 以消歧）
 * @param model           机型（G-01 {@code model}，来自任务方案）
 * @param homeNestId      机巢标识（G-01 {@code nest_id}，来自任务方案；<b>取归属机巢口径</b>
 *                        {@code homeNestId}，与 {@link TaskPlanMessage#homeNestId()} 同字段同义，
 *                        不用 {@code nestId} 避免与「当前所在巢」混淆）
 * @param windCondition   风况段（G-01 {@code wind_condition}，可选分层维度，可为 {@code null}）
 * @param shipSpeedRange  船速段（G-01 {@code ship_speed_range}，可选分层维度，可为 {@code null}）
 */
public record RendezvousDeviationMessage(
        String sampleId,
        String droneId,
        String orderId,
        GeoPoint plannedRendezvous,
        GeoPoint actualRendezvous,
        long plannedTime,
        long actualTime,
        double dSpaceM,
        double dTimeS,
        String model,
        String homeNestId,
        String windCondition,
        String shipSpeedRange) {

    /** 本对象对应的 MQ 主题 */
    public static final String TOPIC = TopicName.RENDEZVOUS_DEVIATION;
}