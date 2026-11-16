package com.waterlogistics.contract.order;

/**
 * 在飞安全复核结论三值，随 {@link OrderView#safetyReview()} 在 REST 快照与
 * WS {@code order_state} 帧间共用同一份字段。
 *
 * <p>三值原样取自 {@code InflightSafetyVerdict.Verdict.getValue()} 与
 * {@code getOpType()}，为原样中文词，不得翻译或映射；本类型放在 {@code wl-contract}
 * 是为了避免契约层反向依赖 {@code wl-m2-dispatch}。
 *
 * @param verdict 复核结论中文词：继续／调整／终止
 * @param opType  落 {@code task_log.op_type} 的原样审计词
 * @param eventTs 本次复核时刻，Unix 毫秒；取审计行 {@code ts} 按写库同一时区换算
 */
public record SafetyReview(String verdict, String opType, long eventTs) {
}
