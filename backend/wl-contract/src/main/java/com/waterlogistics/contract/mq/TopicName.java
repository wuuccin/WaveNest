package com.waterlogistics.contract.mq;

/**
 * MQ 主题名——全项目唯一权威，任何模块不得自造主题名。
 *
 * <p>依据《22-接口契约》v1.2 §1.2。MQ 基调为 MQTT（遥测／指令链路，由 M1 建链）
 * ＋ RabbitMQ（业务消息），本项目不引入 Kafka（《30-M1》§5 类名裁决 C3）。
 *
 * <p>五类前缀分区：原始 {@code raw.*}、解析后 {@code data.*}、告警 {@code alert.*}、
 * 任务方案 {@code task.*}、会面偏差回流 {@code rendezvous.*}。共 13 个主题，
 * 成员见 {@link #TOPICS}（增减须由收口方裁决）。
 */
public final class TopicName {

    /** 原始 AIS 船位报文。生产方 M1，消费方 M1 内部（解码）。 */
    public static final String RAW_AIS = "raw.ais";

    /** 原始无人机遥测报文。生产方 M1，消费方 M1 内部（解码）。 */
    public static final String RAW_UAV_TELEMETRY = "raw.uav.telemetry";

    /** 原始气象数据。生产方 M1，消费方 M1 内部（解码）。 */
    public static final String RAW_WEATHER = "raw.weather";

    /** 原始水文数据。生产方 M1，消费方 M1 内部（解码）。 */
    public static final String RAW_HYDRO = "raw.hydro";

    /** 原始空域公告。生产方 M1，消费方 M1 内部（解码）。 */
    public static final String RAW_NOTICE = "raw.notice";

    /** 解析后的船位对象 {@code AisMessage}。生产方 M1，消费方 M2、M5。 */
    public static final String DATA_AIS = "data.ais";

    /**
     * 解析后的遥测对象 {@code UavTelemetry}。生产方 M1，消费方 M4、M5。
     *
     * <p>遥测只接入一次：M4 不另建订阅链路；上行指令由 M4 编码后经 M1 的 MQTT 通道下发。
     */
    public static final String DATA_UAV_TELEMETRY = "data.uav.telemetry";

    /** 解析后的气象对象。生产方 M1，消费方 M2、M3、M4、M5。 */
    public static final String DATA_WEATHER = "data.weather";

    /** 解析后的水文对象。生产方 M1，消费方 M2、M3、M5。 */
    public static final String DATA_HYDRO = "data.hydro";

    /** 解析后的空域公告对象。生产方 M1，消费方 M2、M3、M5。 */
    public static final String DATA_NOTICE = "data.notice";

    /** M4 异常告警（与 WebSocket 双推）。生产方 M4，消费方 M2、M5。 */
    public static final String ALERT_EVENT = "alert.event";

    /**
     * 任务方案（机、巢、会面点、起飞时刻、航迹、船舶预测轨迹）。生产方 M2，消费方 M4。
     *
     * <p>字段来源见《22》§5 第 5 步：会面点 ← {@code B_RendezvousSolution}；
     * 航迹 ← {@code B_FlyableRoute.waypoints}；起飞时刻 ← {@code B_TakeoffTimeResult}；
     * 船舶预测轨迹 ← A 组经 M2 常驻层转供的完整 {@code PredictionSnapshot}。
     */
    public static final String TASK_PLAN = "task.plan";

    /**
     * 会面偏差样本回流（功能 19）。生产方 M3（G 组），消费方 M2、E 组功能 17、A 组功能 2。
     *
     * <p>字段照《86》附 G-01 会面偏差样本对象级字段表全 13 项，见
     * {@link RendezvousDeviationMessage}。
     *
     * <p><b>归 {@code rendezvous.*} 前缀而非 {@code alert.*}</b>：《86》§5 明写
     * 「偏差样本经数据回流行推 M2，<b>不走告警通道</b>」——它不写 {@code alert} 表、不产级别。
     */
    public static final String RENDEZVOUS_DEVIATION = "rendezvous.deviation";

    /**
     * 全部主题名快照，供启动自检与文档核对使用。
     *
     * <p>约定：M6 不作为 MQ 消费方（落库由 M1/M2 → M6 的调用完成），
     * 故 {@code data.*} 消费方只列 M2、M3、M4、M5。
     */
    public static final java.util.List<String> TOPICS = java.util.List.of(
            RAW_AIS, RAW_UAV_TELEMETRY, RAW_WEATHER, RAW_HYDRO, RAW_NOTICE,
            DATA_AIS, DATA_UAV_TELEMETRY, DATA_WEATHER, DATA_HYDRO, DATA_NOTICE,
            ALERT_EVENT, TASK_PLAN, RENDEZVOUS_DEVIATION);

    private TopicName() {
        // 常量容器，禁止实例化
    }
}
