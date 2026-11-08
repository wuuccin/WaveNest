package com.waterlogistics.algo.cargo;

import java.util.List;

/**
 * 选巢输入（纯 Java 对象，无 Spring/DB——数据由调用方注入）。
 *
 * <p>选巢回答的是「这批货该送到哪个巢去装」，候选生成回答的是「从哪个巢起飞、
 * 由哪架机执行」。两者看巢的角度不同：前者看巢<b>能不能当装货点</b>，后者看巢
 * <b>能不能当出发地</b>。因此本输入的巢视图与机视图不复用
 * {@code CandidateInput} 的同名视图，任一侧口径调整都不牵连另一侧。
 *
 * @param shipLon            船位经度（选巢的距离基准，取不到则无法选巢）
 * @param shipLat            船位纬度
 * @param payloadKg          本单货物载重，kg
 * @param nests              机巢候选列表
 * @param drones             无人机候选列表
 * @param maxNestDistanceKm  巢到船位的最大距离，km（超出则该巢不参与选巢）
 * @param minBatteryPercent  最低保留电量，%
 */
public record CargoNestInput(
        /** 船位经度 */
        double shipLon,
        /** 船位纬度 */
        double shipLat,
        /** 货物载重，kg */
        double payloadKg,
        /** 机巢候选列表 */
        List<NestView> nests,
        /** 无人机候选列表 */
        List<DroneView> drones,
        /** 巢到船位的最大距离，km */
        double maxNestDistanceKm,
        /** 最低保留电量，% */
        double minBatteryPercent) {

    public CargoNestInput {
        nests = nests == null ? List.of() : List.copyOf(nests);
        drones = drones == null ? List.of() : List.copyOf(drones);
    }

    /**
     * 可当装货点的机巢视图。
     *
     * @param nestId        巢 Id
     * @param lon           经度
     * @param lat           纬度
     * @param allowedTypes  可驻留机型（空列表＝未登记＝不限制）
     * @param freeSlots     剩余可停数（{@link Integer#MAX_VALUE}＝未登记不限制）
     */
    public record NestView(
            String nestId,
            double lon,
            double lat,
            /** 可驻留机型（机型名列表） */
            List<String> allowedTypes,
            /** 剩余可停数（{@link Integer#MAX_VALUE}＝不限制） */
            int freeSlots) {

        public NestView {
            allowedTypes = allowedTypes == null ? List.of() : List.copyOf(allowedTypes);
        }

        /** 兼容构造：未登记容量（视为不限制）。 */
        public NestView(String nestId, double lon, double lat, List<String> allowedTypes) {
            this(nestId, lon, lat, allowedTypes, Integer.MAX_VALUE);
        }

        /**
         * 该巢是否还能再停一架（容量闸）。
         *
         * <p>未登记容量时不拦：容量闸的目的是防止选出一个停不下机的巢，
         * 容量根本没登记就一律拦死，等于用数据缺失换停机。
         */
        public boolean hasFreeSlotFor() {
            return freeSlots == Integer.MAX_VALUE || freeSlots > 0;
        }
    }

    /**
     * 可用机视图。
     *
     * <p><b>为什么带巢归属</b>：选巢要回答的不只是「这台机配不配得上这个巢」，还有
     * 「这台机的巢坐标查不查得到」——候选生成算空机转场距离要用机的归属巢坐标，
     * 归属巢查不到坐标的机产不出任何组合。若选巢不查这一条，就会选出一个
     * 「看着有空闲机、实际一个组合都产不出来」的货巢，订单一出生就卡在调度中。
     *
     * @param droneId       无人机 Id
     * @param nestId        无人机当前所在巢（无当前巢时取归属巢）；空白＝台账没登记
     * @param type          机型
     * @param status        状态：空闲/占用/故障/充电中（照状态机既定状态词）
     * @param battery       剩余电量（%）；{@code NaN}＝未登记
     * @param maxPayloadKg 最大载荷（kg）；{@code NaN}＝未登记
     */
        public record DroneView(
                String droneId,
                /** 无人机当前所在巢（无当前巢时取归属巢） */
                String nestId,
                String type,
                /** 状态 */
                String status,
                /** 剩余电量（%）；{@code NaN}＝未登记 */
                double battery,
                /** 最大载荷（kg）；{@code NaN}＝未登记 */
                double maxPayloadKg) {

            public DroneView {
                nestId = (nestId == null || nestId.isBlank()) ? null : nestId.trim();
            }

        /** 电量是否已登记（可判定）。 */
        public boolean hasBattery() {
            return !Double.isNaN(battery) && Double.isFinite(battery);
        }

        /** 载荷能力是否已登记（可判定）。 */
        public boolean hasMaxPayload() {
            return !Double.isNaN(maxPayloadKg) && Double.isFinite(maxPayloadKg);
        }
    }
}
