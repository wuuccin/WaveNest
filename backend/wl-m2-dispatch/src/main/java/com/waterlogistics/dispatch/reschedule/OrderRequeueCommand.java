package com.waterlogistics.dispatch.reschedule;

/**
 * 订单重入队动作的输入（重派闭环第 1 环）。
 *
 * <p><b>为什么必须显式传落点与确认位</b>：《21》§8「货物回收后重派闭环」把重派划在
 * <b>物理约束</b>上——货物同一时刻只能处在一个载体上，「让另一架机去送同一票货」要成立，
 * 必须先把它从当前载体转移走，而<b>唯一可行的转移途径是当前这架机成功降落到机巢</b>。
 * 三种落点结论差别极大：
 *
 * <table border="1">
 *   <caption>《21》§8 原文的落点—重派可行性</caption>
 *   <tr><th>原机的落点</th><th>货物去向</th><th>能否重派</th></tr>
 *   <tr><td>返航至原巢、迫停于他巢</td><td>随机落巢，货物被系统回收，状态转为可用</td><td>能，且是唯一的重派入口</td></tr>
 *   <tr><td>迫降于应急迫降点（野外）</td><td>落在系统设施之外</td><td>不能直接重派，须人工回收后再入队</td></tr>
 *   <tr><td>无人机失联或灭失</td><td>货物随之灭失</td><td>不能，订单异常终止</td></tr>
 * </table>
 *
 * <p>所以「放弃本次会面」<b>不等于</b>可以重入队：那一刻货物还绑在原机上，空中不可转移
 * （《23》§6 约束 1）。本 record 把落点与「回收/灭失是否已确认」一并作为入参，
 * 让 {@link RescheduleService#requeueOrder} 能把这张表变成代码里的硬校验。
 *
 * @param orderId        订单标识
 * @param goodsId        货物标识（同一票货待重派）
 * @param droneId        原机标识（返航落巢的那一架）
 * @param landedAtNest   原机是否已确认落巢；{@code false} 表示还在空中，货物不可转移
 * @param landingPointId 落点标识（原巢／他巢迫停巢／应急迫降点／灭失点），用于审计与货物流向记账
 * @param recyclingConfirmed 货物是否已确认回收为可用（落巢后由 M2 确认，《23》§8 写入方口径）
 * @param lossConfirmed  是否已确认无人机灭失（灭失走订单「异常终止」，不重入队）
 */
public record OrderRequeueCommand(
        String orderId,
        String goodsId,
        String droneId,
        boolean landedAtNest,
        String landingPointId,
        boolean recyclingConfirmed,
        boolean lossConfirmed) {

    public OrderRequeueCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("订单标识不得为空");
        }
        if (goodsId == null || goodsId.isBlank()) {
            throw new IllegalArgumentException("货物标识不得为空：重派的是同一票货，缺它无法接回《21》§8 闭环");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("原机标识不得为空");
        }
        if (landingPointId == null || landingPointId.isBlank()) {
            throw new IllegalArgumentException("落点标识不得为空：野外迫降与落巢走不同的货物去向（《21》§8）");
        }
        if (lossConfirmed && landedAtNest) {
            throw new IllegalArgumentException("灭失与落巢互斥：灭失后无人机不存在，不可能有落巢记录");
        }
    }
}