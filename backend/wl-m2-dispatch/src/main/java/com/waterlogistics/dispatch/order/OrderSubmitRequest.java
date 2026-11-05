package com.waterlogistics.dispatch.order;

/**
 * {@code POST /api/orders} 的请求体（M2 · 卢肄文）。
 *
 * <p>字段对齐 {@code order_task} 的权威列名（{@code 37} §5.4，与
 * {@code deploy/sql/001_init.sql} 一字不改）：{@code ship_mmsi}／{@code goods_weight}／
 * {@code goods_type}／{@code expected_time}／{@code priority}；11-05 序号 1 起增
 * {@code cargo_nest_id}（列由张睿哲的 {@code 010_order_cargo_eta.sql} 补）。
 *
 * <p>契约 {@code wl-contract} 只定义了<b>响应</b>对象（{@link
 * com.waterlogistics.contract.order.OrderView}、{@link
 * com.waterlogistics.contract.rest.OrderListResponse}），未定义请求体，
 * 故本记录体放在模块层（M2 的 {@code order} 子包）。字段名不得偏离上表。
 *
 * <p><b>不由客户端提交的字段</b>：{@code ship_lon}／{@code ship_lat} 是「下单时目标船位」，
 * 应由服务端自行掌握（今天未落库，落库时由 M6 在写入前补），不由船员端指定。
 *
 * <h3>11-05 序号 1 的三处口径变化</h3>
 * <ol>
 *   <li><b>{@code cargoNestId} 可空</b>：空＝取离船最近的巢（由候选生成侧按船位挑），
 *       不是「默认第一个巢」。空与「填了不存在的巢」是两件事：后者在判定链里被拒，
 *       前者走就近策略。</li>
 *   <li><b>{@code expectedTimeMs} 可空</b>：字段类型本来就是包装类型，此前缺省也会被
 *       判定链的 {@code isComplete()} 判成「信息不完整 → 转人工」。11-05 序号 2 起
 *       {@code deadline} 改由系统算，故「不填」是<b>正常输入</b>而非缺字段，
 *       本记录体在 javadoc 层把这一点说清，避免下游再把 null 当缺失。</li>
 *   <li><b>{@code shipMmsi}／{@code goodsType}／{@code cargoNestId} 归一</b>：
 *       构造期一律 trim，全空白视同未填（→ {@code null}）。理由见紧凑构造器。</li>
 * </ol>
 *
 * @param shipMmsi       目标船 MMSI，9 位数字（必填）
 * @param goodsWeight    物资载重，kg（必填，须为正）
 * @param goodsType      物资类型（可空；长度 ≤ {@value #GOODS_TYPE_MAX_LENGTH}）
 * @param expectedTimeMs 期望送达时间，Unix 毫秒（可空且「不填」属正常；填了必须晚于当前时刻）
 * @param priority       优先级 {@code NORMAL/URGENT/EMERGENCY}（可空，缺省 NORMAL）
 * @param cargoNestId    货物所在巢（可空＝取离船最近的巢）
 */
public record OrderSubmitRequest(
        String shipMmsi,
        Double goodsWeight,
        String goodsType,
        Long expectedTimeMs,
        String priority,
        String cargoNestId) {

    /**
     * {@code goods_type} 的列宽（{@code 001_init.sql} 为 {@code VARCHAR(64)}）。
     *
     * <p>超长在数据库层是<b>静默截断或报错</b>（取决于 {@code sql_mode}）：截断会让两笔
     * 不同货型的订单被判成同货型，报错则把一次下单失败变成 500。故在入口显式拦下，
     * 返回业务原因码 {@code GOODS_TYPE_TOO_LONG}（仍走 200 ＋ {@code reasonCode} 口径）。
     */
    public static final int GOODS_TYPE_MAX_LENGTH = 64;

    /**
     * 字段归一：trim ＋ 全空白转 {@code null}。
     *
     * <p><b>为什么归一放在记录体而不是服务层</b>：这份请求会被三处消费——基本校验、
     * 判定链、{@code OrderSubmittedEvent} 落库。只在服务层 trim 一次，事件里仍是原始串，
     * 于是「判重按 A、落库按 A␠」两颗数据对不上。放在记录体的紧凑构造器里，
     * <b>任何拿到 {@code OrderSubmitRequest} 的地方看到的都是归一后的值</b>，
     * 不存在某条支路漏归一。
     *
     * <p><b>为什么全空白转 {@code null} 而不是保留空串</b>：空串在库里是「有值但为空」，
     * {@code VARCHAR} 列上会与 {@code NULL} 分成两种语义；判定链用的
     * {@code isBlank()} 判据与前端「未填」也都要落到同一个表示上。
     */
    public OrderSubmitRequest {
        shipMmsi = trimToNull(shipMmsi);
        goodsType = trimToNull(goodsType);
        cargoNestId = trimToNull(cargoNestId);
    }

    /**
     * 兼容 11-05 之前的 5 参构造（不带货巢）。
     *
     * <p>保留理由同 {@link com.waterlogistics.contract.order.OrderView} 的 8 参构造器：
     * 调用点分散在单测与旧链路里，新增字段不该逼它们同时改；
     * 「没填货巢」本来就该是 {@code null}。
     *
     * @param shipMmsi       目标船 MMSI
     * @param goodsWeight    物资载重，kg
     * @param goodsType      物资类型
     * @param expectedTimeMs 期望送达时间，Unix 毫秒
     * @param priority       优先级编码
     */
    public OrderSubmitRequest(String shipMmsi, Double goodsWeight, String goodsType,
            Long expectedTimeMs, String priority) {
        this(shipMmsi, goodsWeight, goodsType, expectedTimeMs, priority, null);
    }

    /** 货巢是否由客户端指定（{@code false} ＝ 走「离船最近的巢」策略）。 */
    public boolean hasCargoNest() {
        return cargoNestId != null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
