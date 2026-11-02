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
}
