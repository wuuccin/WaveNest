package com.waterlogistics.contract.rest;

/**
 * REST 接口路径——依据《22-接口契约》v1.2 §3，前缀统一 {@code /api/}。
 *
 * <p>本表只收已定接口；鉴权采用 JWT Bearer（调度员与船员双角色）：
 * 调度员可调干预与回放，船员仅下单与查询（《22》§7.1 第 3 条）。
 */
public final class ApiPath {

    /** 船员下单（POST）／调度后台拉订单列表（GET，含 {@code dispatchGate}） */
    public static final String ORDERS = "/api/orders";

    /** 机队状态（GET） */
    public static final String DRONES = "/api/drones";

    /** 机巢状态（GET） */
    public static final String NESTS = "/api/nests";

    /**
     * 人工干预（POST）：调整会面点、撤回尚未起飞的订单、取消订单、
     * 冻结派单、紧急叫停某单、转人工接管共六项（以《35-M5》§3.2 干预台为准）。
     */
    public static final String INTERVENE = "/api/intervene";

    /** 时间轴回放取历史帧（GET），需替换 {@code {taskId}} */
    public static final String REPLAY_TEMPLATE = "/api/replay/{taskId}";

    /** 飞行记录统计（GET）；与调度侧 {@code /api/monitor/statistics} 分开。 */
    public static final String REPLAY_STATISTICS = "/api/replay/statistics";

    private ApiPath() {
        // 常量容器，禁止实例化
    }

    /**
     * 生成指定任务的回放路径。
     *
     * @param taskId 任务标识
     */
    public static String replay(String taskId) {
        return "/api/replay/" + taskId;
    }
}
