package com.waterlogistics.rendezvous.solver;

/**
 * 6a 会面点重算请求（接收侧形态）——照《81-B组算法方案》§2.5 的字段分档；E 组
 * {@code RendezvousRecalcRequest} 按此校准（同源《29-各组算法层接口定义》§3.5.1(1a)、C-06）。
 *
 * <p><b>{@code instructionType} 不进本入参</b>（§2.5 末段）：指令是执行层动作（去向 M4），
 * 其效果体现在下一轮遥测里，不改变本次求解的解析前提；它归 E ＋ 33 ＋ M4。
 *
 * <p>请求<b>不带</b> {@code comboId}／{@code nestId}：归属机巢与机型速度由
 * {@link com.waterlogistics.rendezvous.port.DroneContextPort} 按 {@code droneId} 反查；
 * 返回可带 {@code comboId}（由该机当前任务回溯，取不到为 null）。
 *
 * <p>本接口<b>纯计算</b>：不落库、不改占位、不生成航迹（《81》§2.4、§2.5 返回结构 3）。
 *
 * @param requestId       请求标识（求解必需；随结果原样返回）
 * @param droneId         执行机实例（求解必需；反查机型速度、归属机巢、当前组合）
 * @param snapshotVersion 期望快照版本（求解必需；一致性锚，不一致拒绝求解）
 * @param shipMmsi        目标船标识（校验用；快照按此取件，取件即校验）
 * @param orderId         订单标识（追溯用，不参与求解）
 * @param triggerReason   触发原因（追溯用，不参与求解）
 * @param timestamp       请求时刻，Unix 毫秒（追溯用，不参与求解）
 * @param stateSnapshot   当前状态快照（船侧求解必需，机侧追溯用）
 */
public record RendezvousRecalcRequest(

        String requestId,

        String droneId,

        String snapshotVersion,

        String shipMmsi,

        String orderId,

        String triggerReason,

        long timestamp,

        RecalcStateSnapshot stateSnapshot) {
}
