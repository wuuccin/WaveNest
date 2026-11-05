package com.waterlogistics.dispatch.candidate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;

/**
 * 候选链的表格查询条件——<b>两处候选入口（生产主链 planner / 备用 selector）的唯一口径来源</b>。
 *
 * <h3>为什么单独抽成一个类（逐字审查）</h3>
 * <p>HIGH.6 要求「告警机不得进候选」。修复时两处各写了一遍 {@code .eq(healthStatus, "健康")}，
 * 当时的单测是对<b>两个文件</b>做源码断言——能守住「这两个文件里都有」，
 * 但守不住「将来第三个候选入口也会写」。条件集中到这里以后，
 * 新入口只能拿到同一份 wrapper，漏写的窗口就被结构性堵住了。
 *
 * <h3>口径要点</h3>
 * <ul>
 *   <li>健康状态用 {@code eq} 白名单而不是 {@code ne(告警)}：NULL／拼错的值一律排除——
 *       {@code ne} 在 SQL 里对 NULL 恒不成立，看起来安全，但只要有人写成
 *       {@code or()} 串联就会把 NULL 行放回来；白名单没有这个坑。</li>
 *   <li>状态词照 {@code 001_init.sql} 列注释（{@code 23 §7}）：{@code 空闲}／{@code 可用}／
 *       {@code 健康}／{@code 告警}。</li>
 * </ul>
 */
public final class CandidateQueries {

    /** 无人机「空闲」状态词（{@code 23 §7}）。 */
    public static final String STATUS_IDLE = "空闲";

    /** 无人机「健康」状态词（{@code 001_init.sql} 列注释「健康/告警」）。 */
    public static final String HEALTH_OK = "健康";

    /** 机巢「可用」状态词。 */
    public static final String NEST_AVAILABLE = "可用";

    private CandidateQueries() {
    }

    /**
     * 可进候选的无人机：<b>状态＝空闲 且 健康状态＝健康</b>。
     *
     * <p>每次返回新实例——{@code LambdaQueryWrapper} 有可变状态，
     * 共用同一实例会让第二次调用的条件叠加。
     */
    public static LambdaQueryWrapper<DroneEntity> idleAndHealthyDrones() {
        return new LambdaQueryWrapper<DroneEntity>()
                .eq(DroneEntity::getStatus, STATUS_IDLE)
                .eq(DroneEntity::getHealthStatus, HEALTH_OK);
    }

    /** 可进候选的机巢：<b>状态＝可用</b>。 */
    public static LambdaQueryWrapper<NestEntity> availableNests() {
        return new LambdaQueryWrapper<NestEntity>()
                .eq(NestEntity::getStatus, NEST_AVAILABLE);
    }

    /**
     * 可进候选的机巢，且<b>带出当前在巢占用数</b>（11-05 新增，容量闸用）。
     *
     * <p>{@code nest} 表没有占用数列（001_init.sql §5.1 只有 {@code capacity}），
     * 本批<b>禁改表结构</b>，故占用数按 {@code drone} 台账现算：同巢且
     * {@code position_type = '原巢'} 的机，即算占着位置。
     *
     * <p><b>口径依据</b>（001_init.sql §5.2）：{@code position_type} 取值
     * {@code 原巢／空中／他巢临时停靠／迫降点}；只有 {@code 原巢} 表示「停在自己的巢里」。
     * 空中飞着的不占位，迫降在野外的也不占位。
     *
     * <p><b>为什么用子查询而不是再查一次 drone 表在 Java 里分组</b>：
     * 候选链每单都要跑一次，多一次全表查询就多一次往返；子查询与主查询同一语句，
     * 也不存在「两次查询之间台账变了」的窗口。
     *
     * <p><b>为什么不用 {@link #idleAndHealthyDrones()} 的数</b>：那里数的是「空闲」机，
     * 而占用要数「停在巢里的全部机」（含充电中、满载、告警）。用空闲数当占用数，
     * 容量闸会永远通过，等于没有这道闸。
     *
     * <h3>★ 为什么改回 {@code availableNests()}（11-05 自纠）</h3>
     * <p>本方法原先要被候选链的两处入口调用，实现方式是
     * {@code select(NestEntity.class, pred)} ＋ {@code last(...)} 拼一个子查询列。
     * 编译能过、MyBatis-Plus 也接受，但<b>一旦没有表元数据就 NPE</b>：
     * {@code select(...)} 内部要调 {@code TableInfoHelper.getTableInfo(NestEntity.class)}，
     * 纯单测（不起 MyBatis 上下文、只桩 mapper）拿到 {@code null}，直接抛
     * {@code NullPointerException}——11-05 跑 {@code :wl-m2-dispatch:test} 时
     * 连带打死 11 个与本改动无关的用例（{@code PlanCandidateReviewTest} 等）。
     * 换句话说：这条子查询把「候选主链能不能被单测覆盖」这个能力换掉了，
     * 不划算。
     *
     * <p><b>占用数改由组装器算</b>：{@code CandidateInputAssembler} 手里已经有全部
     * 空闲健康机的 {@code DroneEntity}，按 {@code nestId} 分组计数即可，
     * 不必再让 SQL 多带一列。两个后果都更好：
     * <ul>
     *   <li>候选链恢复为「两处入口都只用普通 wrapper」，单测可继续桩 mapper；</li>
     *   <li>占用数口径由 Java 一处定义，不必再依赖 {@code position_type} 字面量散落在 SQL 字符串里。</li>
     * </ul>
     * 本方法保留为「语义明确的别名」，不删除：调用点的意图（要占用数）比方法名更重要，
     * 删除会让后来者再写一次子查询。
     */
    public static LambdaQueryWrapper<NestEntity> availableNestsWithOccupancy() {
        return availableNests();
    }
}
