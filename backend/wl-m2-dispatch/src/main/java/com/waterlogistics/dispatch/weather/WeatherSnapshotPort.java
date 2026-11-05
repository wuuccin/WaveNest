package com.waterlogistics.dispatch.weather;

/**
 * 最新风况快照端口——会面代表风况的取数口（《81-B组算法方案》§3.1 步骤 2）。
 *
 * <p>取数口径：按位置取最近的新鲜观测；无观测、过期或数据不可用时返回 {@code null}，
 * 由调用方回落到 {@code sys_param} 的静态风况，气象链路异常不得中断调度主链。
 *
 * <h3>11-05 评审关闭要点（本文件承载 3 条）</h3>
 * <ul>
 *   <li><b>{@code MED-DOC.4}</b>：{@link #latest} 的<b>入参范围与时钟关系</b>原来没写。
 *       现在明确：经纬度须为有限值且在合法范围内；{@code nowMs} 是<b>调用方的时钟</b>，
 *       与写入侧的时钟必须是同一时间基准（同一 JVM 或 NTP 已同步），
 *       否则「新鲜度」比较没有意义。参数非法时返回 {@code null} 而不抛（取数口不制造故障）。</li>
 *   <li><b>{@code MED-BUG.71}</b>：{@link LatestWind} 原来是个<b>无任何不变式</b>的 record
 *       ——{@code new LatestWind(-1, 999, -5)} 也构造得出来，风速为负会一路带到航迹与告警。
 *       现在紧凑构造器做三项校验：风速为正有限值、风向为有限值（由构造器归一进 [0,360)）、
 *       观测时刻为正。</li>
 *   <li><b>{@code LOW-MAINT.32}</b>：本接口<b>只有一个抽象方法</b>，设计上就是给 lambda 用的
 *       （见 {@code RendezvousCoordinatorTest} 里 {@code (lon, lat, now) -> ...}），
 *       但缺 {@code @FunctionalInterface} 注解——别人加第二个方法时编译器不会拦，
 *       所有 lambda 调用点会一起断。补注解把这条约束交给编译器。</li>
 * </ul>
 */
@FunctionalInterface
public interface WeatherSnapshotPort {

    /**
     * 指定位置的最新观测风况。
     *
     * <p>返回 {@code null} 的三种情形（调用方一律回落静态风况，不得据此判「无风可飞」）：
     * 位置附近无任何快照、快照已过期（{@code nowMs − obsTs > ttlMs}）、数据不可用。
     *
     * @param lon   位置经度，须为有限值且落在 [-180, 180]；越界返回 {@code null}
     * @param lat   位置纬度，须为有限值且落在 [-90, 90]；越界返回 {@code null}
     * @param nowMs 当前时刻，Unix 毫秒——<b>调用方的时钟</b>，须与写入侧同一时间基准
     *              （同一 JVM 或 NTP 已同步）；新鲜度判定为 {@code nowMs − obsTs ≤ ttlMs}
     * @return 最新风况；无新鲜快照或入参非法时为 {@code null}
     */
    LatestWind latest(double lon, double lat, long nowMs);

    /**
     * 最新风况观测。
     *
     * <p><b>不变式（MED-BUG.71）</b>：{@code speedMs} 为正有限值、{@code dirDeg} 已归一到
     * [0,360)、{@code obsMs} 为正。构造期即校验——本对象会一路传到航迹生成与告警落库，
     * 在那里才发现「风速是 -1」已经太晚（错误值早已参与过计算）。
     * 这些不变式同时也让 {@code RendezvousCoordinator} 里原有的兜底分支成为「防御纵深」，
     * 而不是唯一一道关。
     *
     * @param speedMs 风速，m/s，须为正有限值
     * @param dirDeg  风向（来向），度，构造时归一进 [0,360)
     * @param obsMs   观测时刻，Unix 毫秒，须为正
     */
    record LatestWind(double speedMs, double dirDeg, long obsMs) {

        public LatestWind {
            if (!Double.isFinite(speedMs) || speedMs <= 0.0) {
                throw new IllegalArgumentException("风速须为正有限值：" + speedMs);
            }
            if (!Double.isFinite(dirDeg)) {
                throw new IllegalArgumentException("风向须为有限值：" + dirDeg);
            }
            if (obsMs <= 0L) {
                throw new IllegalArgumentException("观测时刻须为正：" + obsMs);
            }
            // 归一进 [0,360)：负角与超 360 都在这里一次性收口，
            // 免得到达下游时再各写一遍取模（已有两处，写法还不一样）
            dirDeg = ((dirDeg % 360.0) + 360.0) % 360.0;
        }
    }
}
