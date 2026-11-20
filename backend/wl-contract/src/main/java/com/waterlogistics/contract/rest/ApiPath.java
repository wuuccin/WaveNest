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

    /**
     * 货物到巢登记（POST，幂等）；需替换 {@code {orderId}}。
     *
     * <p>语义：把该订单的货物置为「已就绪」，是《11-05 任务清单》序号 2「货到巢关」的入口。
     * 幂等口径——重复调用只回 {@code changed=false}，不重复写库、不重复触发后续推进。
     */
    public static final String CARGO_READY_TEMPLATE = "/api/orders/{orderId}/cargo-ready";

    /**
     * 资源释放（POST，无请求体）；需替换 {@code {orderId}}。
     *
     * <p>语义：把宽限期到期转入「等待资源」的订单人工放回待调度（《11-19 任务清单》序号 1-2）。
     * 非等待资源／未到期回 409「不满足释放条件」。
     */
    public static final String RESOURCE_RELEASE_TEMPLATE = "/api/orders/{orderId}/resource-release";

    /**
     * 候选留痕回查（GET，无请求体）；需替换 {@code {orderId}}。
     *
     * <p>语义：按订单返回该单历次派单决策的逐候选九项留痕（《11-19 任务清单》§3 张睿哲·二）。
     * 查无记录回空数组，不返回 404——「还没派过单」与「订单不存在」在展示上是同一件事。
     */
    public static final String CANDIDATES_TEMPLATE = "/api/orders/{orderId}/candidates";

    /** 机队状态（GET） */
    public static final String DRONES = "/api/drones";

    /** 机巢状态（GET） */
    public static final String NESTS = "/api/nests";

    /** 当前可承运配送品类字典（GET） */
    public static final String CARGO_TYPES = "/api/cargo-types";

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

    /**
     * 生成指定订单的「货物到巢」登记路径。
     *
     * @param orderId 订单标识
     * @return 具体路径
     */
    public static String cargoReady(String orderId) {
        return "/api/orders/" + orderId + "/cargo-ready";
    }

    /**
     * 生成指定订单的「资源释放」路径。
     *
     * @param orderId 订单标识
     * @return 具体路径
     */
    public static String resourceRelease(String orderId) {
        return "/api/orders/" + orderId + "/resource-release";
    }

    /**
     * 生成指定订单的「候选留痕」查询路径。
     *
     * @param orderId 订单标识
     * @return 具体路径
     */
    public static String candidates(String orderId) {
        return "/api/orders/" + orderId + "/candidates";
    }
}
