package com.waterlogistics.algo.meeting.model;

/**
 * 会面点解（{@code B_} 前缀＝算法层 B 组产出）——字段照《81-B组算法方案》§2.3 第（1）项
 * 一字不改；{@code hasSolution=false} 时会面字段一律为 {@code null}，由工厂方法钉死该不变量。
 *
 * <p>消费边界：C 组只用会面点／时刻窗／{@code tauCoarse}／无解标志与原因码；
 * 本对象<b>不含精算量</b>（精算量见 {@code B_FlyableRoute}），两笔账不混（《81》§2.3 口径 3）。
 *
 * @param solutionId       本次求解结果标识（确定性生成，同输入同输出）
 * @param comboId          绑定的候选组合标识
 * @param hasSolution      无实数解标志
 * @param noSolutionReason 无解原因（有解时为 null）
 * @param meetLon          会面点经度（无解 null）
 * @param meetLat          会面点纬度（无解 null）
 * @param meetTMin         会面时刻窗下界，Unix 毫秒（无解 null）
 * @param meetTMax         会面时刻窗上界，Unix 毫秒（无解 null）
 * @param shipArriveT      船方到达会面点时刻，Unix 毫秒
 * @param droneArriveT     机方到达会面点时刻，Unix 毫秒
 * @param tauCoarse        理论（粗估）飞行时长 τ_coarse，秒——C 组第一关据此反推最迟起飞时刻（粗估账）
 * @param snapshotVersion  求解所用预测快照版本
 */
public record B_RendezvousSolution(

        String solutionId,

        String comboId,

        boolean hasSolution,

        NoSolutionReason noSolutionReason,

        Double meetLon,

        Double meetLat,

        Long meetTMin,

        Long meetTMax,

        Long shipArriveT,

        Long droneArriveT,

        Integer tauCoarse,

        String snapshotVersion) {

    /**
     * 有解构造：窗口＝[t*, t*＋余量]（调用方算好上下界），双方到达时刻＝最早相遇时刻。
     */
    public static B_RendezvousSolution solved(String solutionId, String comboId,
            double meetLon, double meetLat, long meetTMin, long meetTMax, long arriveT,
            int tauCoarseSec, String snapshotVersion) {
        return new B_RendezvousSolution(solutionId, comboId, true, null,
                meetLon, meetLat, meetTMin, meetTMax, arriveT, arriveT, tauCoarseSec, snapshotVersion);
    }

    /**
     * 无解构造：会面字段一律 null（《81》§2.3：hasSolution=false 时会面字段为 null）。
     */
    public static B_RendezvousSolution unsolvable(String solutionId, String comboId,
            NoSolutionReason reason, String snapshotVersion) {
        return new B_RendezvousSolution(solutionId, comboId, false, reason,
                null, null, null, null, null, null, null, snapshotVersion);
    }
}
