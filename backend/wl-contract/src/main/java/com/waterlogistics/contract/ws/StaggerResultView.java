package com.waterlogistics.contract.ws;

/**
 * 错峰结果视图——{@code rendezvous} 帧的可选成分（11-14 序号 1-2 · 张睿哲）。
 *
 * <p>航迹级空域冲突二查的处置顺序是「先错峰 → 再绕行 → 最后回退换候选」
 * （《33-路径规划》§3.2、《83》§5.2.2）。错峰一旦成立，<b>整帧计划点与会面时刻必须同步时移</b>；
 * 本视图把「移了多少、移到哪」显式带出，前端据此显示「错峰 +N 秒」并核对会面时刻已同步。
 *
 * <p><b>帧归属</b>：只随 {@code rendezvous} 帧下发，不单独设帧类型。
 *
 * <p><b>缺省值与空值口径</b>：整块缺省为 {@code null}（未进入定案校验，如队列侧投影）；
 * 块内默认值由 {@link #none(long)} 给出——{@code applied=false}、{@code shiftMs=0}、
 * {@code reason=null}、{@code committedMeetTs=originalMeetTs}，表示「无冲突、未错峰」。
 * <b>错峰失败不产生本视图</b>：冲突经错峰仍不能消除时派单事务整体回滚、订单不派发，
 * 失败原因以异常消息（中文可读句）与 warn 日志呈现，不伪造一条已派发帧。
 *
 * @param applied         是否发生错峰（{@code shiftMs > 0} 时为 {@code true}）
 * @param shiftMs         平移毫秒；{@code 0} 表示未错峰，不得为负
 * @param originalMeetTs  错峰前会面时刻，Unix 毫秒；未错峰时与 {@code committedMeetTs} 相同
 * @param committedMeetTs 定案会面时刻，Unix 毫秒（{@code order_task.meet_time} 落库值）
 * @param reason          可读原因；{@code null} 表示未触发错峰（无冲突）
 */
public record StaggerResultView(

        boolean applied,

        long shiftMs,

        Long originalMeetTs,

        Long committedMeetTs,

        String reason) {

    /** 可读原因前缀，前后端与日志共用同一口径。 */
    public static final String REASON_PREFIX = "空域冲突经错峰 ";

    /**
     * 未错峰（无冲突）的缺省结果。
     *
     * @param meetTs 本单会面时刻，Unix 毫秒；错峰前与定案值相同
     */
    public static StaggerResultView none(long meetTs) {
        return new StaggerResultView(false, 0L, meetTs, meetTs, null);
    }

    /**
     * 错峰成立的定案结果。
     *
     * @param shiftMs         平移毫秒，须为正
     * @param originalMeetTs  错峰前会面时刻
     * @param committedMeetTs 时移后定案会面时刻
     */
    public static StaggerResultView shifted(long shiftMs, long originalMeetTs, long committedMeetTs) {
        return new StaggerResultView(true, shiftMs, originalMeetTs, committedMeetTs,
                REASON_PREFIX + shiftMs + " ms 消除");
    }
}
