package com.waterlogistics.algo.meeting.model;



/**
 * 会面点解（{@code B_} 前缀＝算法层 B 组产出）——字段照《81-B组算法方案》§2.3 第（1）项
 * 一字不改；{@code hasSolution=false} 时会面字段一律为 {@code null}，由工厂方法钉死该不变量。
 *
 * <p>消费边界：C 组只用会面点／时刻窗／{@code tauCoarse}／无解标志与原因码；
 * 本对象<b>不含精算量</b>（精算量见 {@code B_FlyableRoute}），两笔账不混（《81》§2.3 口径 3）。
 *
 * @param solutionId       本次求解结果标识（确定性生成，同输入同输出）
 * @param comboId          绑定的候选组合标识（重算路径取不到组合时为 null）
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

    /** 理论飞行时长上界：保证下游 int 秒 × 1000 换算毫秒时不溢出 */
    private static final int MAX_TAU_SEC = Integer.MAX_VALUE / 1000;

    public B_RendezvousSolution {
        if (solutionId == null || solutionId.isBlank()) {
            throw new IllegalArgumentException("solutionId 不能为空");
        }
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("snapshotVersion 不能为空");
        }
        if (hasSolution) {
            if (noSolutionReason != null) {
                throw new IllegalArgumentException("有解时 noSolutionReason 必须为空");
            }
            if (meetLon == null || meetLat == null || meetTMin == null || meetTMax == null
                    || shipArriveT == null || droneArriveT == null || tauCoarse == null) {
                throw new IllegalArgumentException("有解时会面字段不能为空");
            }
            if (!Double.isFinite(meetLon) || meetLon < -180.0 || meetLon > 180.0
                    || !Double.isFinite(meetLat) || meetLat < -90.0 || meetLat > 90.0) {
                throw new IllegalArgumentException("会面点坐标必须为有限数且在合法范围内");
            }
            if (meetTMin < EpochMillis.MIN_VALID_MS || meetTMax < EpochMillis.MIN_VALID_MS
                    || shipArriveT < EpochMillis.MIN_VALID_MS || droneArriveT < EpochMillis.MIN_VALID_MS) {
                throw new IllegalArgumentException("时刻字段须为 Unix 毫秒值（下界 "
                        + EpochMillis.MIN_VALID_MS + "）");
            }
            if (meetTMin > meetTMax) {
                throw new IllegalArgumentException("会面时刻窗下界不得晚于上界");
            }
            if (shipArriveT < meetTMin || shipArriveT > meetTMax
                    || droneArriveT < meetTMin || droneArriveT > meetTMax) {
                throw new IllegalArgumentException("双方到达时刻必须落在会面时刻窗内");
            }
            if (tauCoarse < 0 || tauCoarse > MAX_TAU_SEC) {
                throw new IllegalArgumentException("理论飞行时长须在 [0, " + MAX_TAU_SEC + "] 秒内，实际 " + tauCoarse);
            }
        } else {
            if (noSolutionReason == null) {
                throw new IllegalArgumentException("无解时 noSolutionReason 不能为空");
            }
            if (meetLon != null || meetLat != null || meetTMin != null || meetTMax != null
                    || shipArriveT != null || droneArriveT != null || tauCoarse != null) {
                throw new IllegalArgumentException("无解时会面字段必须为 null");
            }
        }
        if (comboId != null && comboId.isBlank()) {
            throw new IllegalArgumentException("comboId 只能为 null 或非空白字符串");
        }
    }

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
