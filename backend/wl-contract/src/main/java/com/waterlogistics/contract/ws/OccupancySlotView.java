package com.waterlogistics.contract.ws;

/**
 * 占位结果视图——{@code rendezvous} 帧的可选成分（11-14 序号 1-2 · 张睿哲）。
 *
 * <p>承载派单第 4 步（《22-接口契约》§3「占用这架机、这个巢位、这个会面窗口、这个归巢时隙」）
 * 实际登记下来的那一条占位，供前端在订单卡上显示「占了谁、占多久」。
 *
 * <p><b>帧归属</b>：只随 {@code rendezvous} 帧下发，不单独设帧类型（{@code WsFrameType} 六类不变）。
 *
 * <p><b>缺省值与空值口径</b>：整块缺省为 {@code null}（未登记占位，例如队列侧投影或重算路径）；
 * 块内 {@code slotId} 非空即视为已登记，其余字段为空的含义如下——
 * {@code objectType}／{@code objectId} 空表示未记录占用对象，{@code startTs}／{@code endTs}
 * 空表示未记录时窗，{@code status} 空表示状态未知。前端遇到任何空值只显示「占位未登记」，
 * 不得自行推断。
 *
 * @param slotId     占位时隙号（{@code SLOT-<UUID>}）；{@code null} 表示未登记
 * @param objectType 对象类型：{@code 机占用}／{@code 迫停占用}（《37-M6-数据库》§5.5 取值）；
 *                   本期派单链固定 {@code 机占用}
 * @param objectId   占用对象 Id：{@code 机占用} 时为空机号，{@code 迫停占用} 时为巢号
 * @param startTs    占位开始，Unix 毫秒（Asia/Shanghai 落库口径换算）
 * @param endTs      占位结束，Unix 毫秒；半开区间 {@code [startTs, endTs)}
 * @param status     占位状态：{@code 有效}／{@code 已释放}（《23-状态机定义》§10）
 */
public record OccupancySlotView(

        String slotId,

        String objectType,

        String objectId,

        Long startTs,

        Long endTs,

        String status) {
}
