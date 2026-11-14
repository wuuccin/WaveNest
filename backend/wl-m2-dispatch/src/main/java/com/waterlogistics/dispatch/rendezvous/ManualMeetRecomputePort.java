package com.waterlogistics.dispatch.rendezvous;

/**
 * 人工「调整会面点」的重算出口（M2 · 序号 3）。
 *
 * <p>《1112-功能做实批次｜接口约定》§3.2 对该动作的要求是「修改会面点并触发重算」，
 * §3.1 请求体只有 {@code action}／{@code orderId}／{@code source} 三个字段，
 * <b>不含新会面点坐标</b>——故重算口径定为「按最新预测重算」：调用方只给订单号，
 * 具体会面点由 M3 门面按当前快照重解，人工不指定坐标。
 *
 * <p>实现由 {@code wl-app} 提供（{@code RendezvousRecomputeRequester}），因为重算要
 * 同时用到预测快照、执行机台账与 M3 门面三者，只有装配层能把它们接起来；
 * M2 只依赖本接口，不出现门面类型。
 *
 * <p><b>与 6a 判据的关系</b>：6a 的冷却期、最小间隔、迟滞、死区都是「同一段变向反复触发」
 * 的抑制手段，面向自动重算；人工干预是一次显式指令，<b>不按六键放行判断</b>，
 * 但仍遵守会面决策窗口（{@code rendezvousWindowMarginSec}）：已进窗口就不再挪会面点。
 * 人工重算成功后登记一次 6a 冷却起点，避免手动调完紧接着被自动重算覆盖。
 *
 * <p><b>只算会面点、不重算航迹</b>：与 {@code RendezvousFacade#recompute} 的量判分离口径一致，
 * 航迹是否重生成归 33 纠偏循环，本端口不做。
 */
public interface ManualMeetRecomputePort {

    /** 重算结论；只有 {@link #COMMITTED} 表示会面点已改并已推帧。 */
    enum Outcome {

        /** 会面点已按最新预测重算、落库并推送 {@code rendezvous} 帧。 */
        COMMITTED("会面点已按最新预测重算"),

        /** 订单尚未派单（无执行机或非在飞状态），没有可调整的会面点。 */
        NOT_DISPATCHED("订单尚未派单，无可调整的会面点"),

        /** 已进会面决策窗口，按 {@code rendezvousWindowMarginSec} 不再调整。 */
        DEADLINE("已进会面决策窗口，不再调整会面点"),

        /** 该船尚无可用预测快照，无法按最新预测重算。 */
        NO_SNAPSHOT("该船暂无可用预测快照，保留原会面点"),

        /** 按最新预测求无解，保留原会面点。 */
        NO_SOLUTION("按最新预测求无解，保留原会面点"),

        /** 求解或落库异常，保留原会面点。 */
        FAILED("重算失败，保留原会面点");

        private final String message;

        Outcome(String message) {
            this.message = message;
        }

        /** 回吐给调用方的中文说明，同时用于 {@code task_log.op_type} 的结果后缀。 */
        public String message() {
            return message;
        }

        /** 是否为「已改成功」；{@code false} 一律表示原会面点未动。 */
        public boolean committed() {
            return this == COMMITTED;
        }
    }

    /**
     * 按订单当前最新预测重算会面点。
     *
     * <p>不抛异常：失败一律以 {@link Outcome} 回吐，由调用方决定是否回吐给前端；
     * 会面点未改成功时<b>不得</b>推帧、不得改 {@code meet_time}。
     *
     * @param orderId       订单号，非空
     * @param triggerReason 触发原因（写进 M3 重算请求，供追溯），非空
     * @return 重算结论，非 {@code null}
     */
    Outcome recomputeForOrder(String orderId, String triggerReason);
}
