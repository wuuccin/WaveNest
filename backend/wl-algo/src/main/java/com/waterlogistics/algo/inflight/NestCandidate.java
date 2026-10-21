package com.waterlogistics.algo.inflight;

import java.util.List;

/**
 * 候选他巢（功能10 第二级「借位（他巢）准入判定」的输入），
 * 依据《84-E组算法方案》功能10 第4章 4.1–4.4。
 *
 * <p><b>本类型只是「准入」的输入，不是「择优」的输入。</b>
 * 《84》第5章易混点：「返航目标｜<b>唯一化＝原巢</b>。"偏离大／别处更近"不是改投理由」——
 * 故调用方<b>不得</b>按距离排序候选，{@link HomecomingReview} 只按给定顺序取第一个准入者。
 * 4.3 原文亦强调第二级「<b>只判准入、不判分配</b>——分配落子归 D 组」。
 *
 * <p>三项数据与三条判据一一对应：
 * <ul>
 *   <li>{@code returnEnergyPercent} → <b>可达性筛除 C3</b>（到他巢能耗 ≤ 可用电量×{@code e10_nest_avail_factor}）</li>
 *   <li>{@code totalSlots} → <b>容量约束检查 D2</b>（总机位数是否已全占）</li>
 *   <li>{@code occupiedWindows} → <b>D1 时隙可行性／D5 重叠判定</b>（两端加 {@code e10_slot_buffer} 缓冲）</li>
 * </ul>
 *
 * <p>数据源为 D 组归巢时隙占用台账（{@code occupancy_slot}），<b>由模块层读出后注入</b>；
 * {@code wl-algo} 不读库、不依赖 Spring。
 *
 * @param nestId              机巢标识
 * @param totalSlots          该巢总机位数（0 表示不可用）
 * @param occupiedWindows     已占用时窗；不可为 {@code null}，空列表表示当前无占用
 * @param returnEnergyPercent 飞到该巢的返程能耗，<b>与电量同口径</b>（百分比）
 */
public record NestCandidate(
        String nestId,
        int totalSlots,
        List<TimeWindow> occupiedWindows,
        double returnEnergyPercent) {

    public NestCandidate {
        if (nestId == null || nestId.isBlank()) {
            throw new IllegalArgumentException("候选他巢须有 nestId，无法定位机巢");
        }
        if (totalSlots < 0) {
            throw new IllegalArgumentException("总机位数不可为负，实测 totalSlots=" + totalSlots
                    + "（nestId=" + nestId + "）");
        }
        if (returnEnergyPercent < 0 || !Double.isFinite(returnEnergyPercent)) {
            throw new IllegalArgumentException("返程能耗须为非负有限值，实测 returnEnergyPercent="
                    + returnEnergyPercent + "（nestId=" + nestId + "）");
        }
        if (occupiedWindows == null) {
            throw new IllegalArgumentException("候选他巢 " + nestId
                    + " 缺 occupiedWindows：台账读出时须给空列表表示无占用，不得给 null");
        }
        occupiedWindows = List.copyOf(occupiedWindows);
    }

    /**
     * 当前剩余空位数（容量约束 D2 的粗筛依据）。
     *
     * <p>注意走访问器而非直接读字段：IDE 会把「直接读 record 集合字段且从不 add」判为
     * 「从未添加到集合中」误报，改用 {@code this.xxx()} 即消失。别改回字段直读。
     */
    public int freeSlots() {
        return Math.max(0, totalSlots - this.occupiedWindows().size());
    }
}