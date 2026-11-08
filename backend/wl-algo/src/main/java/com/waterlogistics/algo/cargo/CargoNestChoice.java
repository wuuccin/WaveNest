package com.waterlogistics.algo.cargo;

/**
 * 选巢结论——命中与否都返回，命中时给出巢编号与巢到船位的距离。
 *
 * @param hit        是否选到巢
 * @param nestId     选中的巢编号；未命中时为 null
 * @param distanceKm 巢到船位的距离，km；未命中时为 0
 * @param reason     命中或落选原因
 */
public record CargoNestChoice(
        boolean hit,
        String nestId,
        double distanceKm,
        Reason reason) {

    /**
     * 落选原因。
     *
     * <p>为什么把原因带出来：选不出巢时若只回一个「没选到」，现场无法判断是船位缺失、
     * 巢全满、还是机型不匹配。这几种成因的处置完全不同，运营必须能从日志或订单轨迹上
     * 一眼看出是哪一种。
     */
    public enum Reason {

        /** 选中 */
        HIT,

        /** 船位不可用，没有距离基准 */
        NO_SHIP_POSITION,

        /** 没有落在距离半径内的机巢 */
        NO_NEST_AVAILABLE,

        /** 半径内的机巢都没有空位 */
        NO_CAPACITY,

        /** 有空位的机巢都停不下符合条件的无人机 */
        NO_COMPATIBLE_DRONE,

        /**
         * 货巢决策开关关闭，系统未做选巢。
         *
         * <p>该结果由调用方在读到开关后直接给出，算法层不产出：算法层不知道开关的存在。
         */
        DISABLED
    }

    /** 构造一个未命中的结论。 */
    public static CargoNestChoice miss(Reason reason) {
        return new CargoNestChoice(false, null, 0.0, reason);
    }
}
