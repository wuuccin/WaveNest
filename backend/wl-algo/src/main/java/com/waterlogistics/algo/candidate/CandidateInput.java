package com.waterlogistics.algo.candidate;

import java.util.List;

/**
 * 候选生成输入（纯 Java 对象，无 Spring/DB——数据由调用方注入）。
 *
 * <p>字段与 37 的 drone/nest 表对齐（简化演示版只取用到的子集）。
 *
 * <h3>★ 11-05 增「货物所在巢」三字段（阶段三 · 序号 1）</h3>
 * <p>此前候选生成把机巢锁死为「无人机当前所在巢」——{@code nestMap.get(d.nestId())}，
 * 于是候选数恒等于机数，{@code nestId} 只是机的附属属性。业务上货物可能放在
 * <b>另一个巢</b>（例如 NEST-02 收到货、NEST-01 有机空闲），此时正确候选是
 * 「就近巢的机飞过去取货」，而不是「只有同巢的机才可选」。
 *
 * <p>因此新增：
 * <ul>
 *   <li>{@link #cargoNestId()} —— 货物所在巢；为空时退回按船位取货的旧口径；</li>
 *   <li>{@link #cargoLon()}／{@link #cargoLat()} —— 货点坐标，由调用方按
 *       {@code cargoNestId} 查巢表得到。{@code NaN} 表示<b>货点不可用</b>
 *       （货巢为空，或该巢台账缺经纬度）；</li>
 *   <li>{@link #pickupLon()}／{@link #pickupLat()} —— <b>船位</b>，语义不变，
 *       仍供会面点与 taskDistKm 使用。<b>不再</b>被生成端当作距离基准。</li>
 * </ul>
 *
 * <p><b>为什么要单独给 {@code cargoLon/cargoLat} 而不是让算法层自己查巢</b>：
 * 算法包 {@code algo} 不依赖 storage（包结构设计 §二），查台账属调用方职责；
 * 组装器已经有一份「已过滤可用巢」的列表，坐标在手上，再让算法层反查
 * 只会多一条可能分叉的取值路径。
 *
 * @param pickupLon   订单取货点经度（<b>船位</b>，11-05 起仅用于会面与航程估算）
 * @param pickupLat   订单取货点纬度（<b>船位</b>）
 * @param payloadKg   载荷重量（kg）
 * @param nests       机巢列表
 * @param drones      无人机列表
 * @param cargoNestId 货物所在巢 Id；{@code null}＝未指定，按船位口径（11-05 新增）
 * @param cargoLon    货点经度；{@code NaN}＝货点不可用（11-05 新增）
 * @param cargoLat    货点纬度；{@code NaN}＝货点不可用（11-05 新增）
 */
public record CandidateInput(
        /** 订单取货点经度（船位） */
        double pickupLon,
        /** 订单取货点纬度（船位） */
        double pickupLat,
        /** 载荷重量（kg） */
        double payloadKg,
        /** 机巢列表 */
        List<NestView> nests,
        /** 无人机列表 */
        List<DroneView> drones,
        /** 货物所在巢 Id；{@code null}＝未指定，按船位口径 */
        String cargoNestId,
        /** 货点经度；{@code NaN}＝货点不可用 */
        double cargoLon,
        /** 货点纬度；{@code NaN}＝货点不可用 */
        double cargoLat) {

    public CandidateInput {
        nests = nests == null ? List.of() : List.copyOf(nests);
        drones = drones == null ? List.of() : List.copyOf(drones);
        cargoNestId = (cargoNestId == null || cargoNestId.isBlank()) ? null : cargoNestId.trim();
    }

    /**
     * 兼容构造：未指定货物所在巢（旧口径）。
     *
     * <p>保留是为了让「还没接货巢的调用方与原有用例」不必一夜之间全改，
     * 但<b>生产主链不得再走这一条</b>——{@code CandidateInputAssembler} 必须显式传货巢。
     */
    public CandidateInput(double pickupLon, double pickupLat, double payloadKg,
            List<NestView> nests, List<DroneView> drones) {
        this(pickupLon, pickupLat, payloadKg, nests, drones, null, Double.NaN, Double.NaN);
    }

    /** 货点是否可用（坐标有限且已给货巢）。 */
    public boolean hasCargoPoint() {
        return cargoNestId != null
                && Double.isFinite(cargoLon) && Double.isFinite(cargoLat);
    }

    /**
     * 货巢已指定但坐标取不到（「脏货巢」）。
     *
     * <p><b>为什么必须单独有一个判断</b>：{@link #hasCargoPoint()} 为 {@code false} 有两种
     * 完全不同的成因，必须分开处理，不能都退回船位：
     * <ul>
     *   <li>{@code cargoNestId == null} —— 旧口径调用方<b>没给</b>货巢，退回船位是正确的；</li>
     *   <li>{@code cargoNestId != null} 且坐标非有限 —— 订单<b>给了</b>货巢但台账查不到坐标，
     *       这是数据问题。此时若退回船位，会产出一批「巢 → 船位」的候选，而船在移动、
     *       货在某个巢里，这批候选的距离基准是错的，且现场看不出任何异常
     *       （见卢肄文 11-05 字段表原话：「脏货巢查不到坐标时应不提供该组合，不要退化到
     *       「无人机所在巢」，否则货巢≠机巢的空机转场段会凭空消失」）。</li>
     * </ul>
     * 因此本方法为 {@code true} 时，生成端必须<b>一个组合都不产出</b>。
     */
    public boolean hasUnresolvedCargoNest() {
        return cargoNestId != null && !hasCargoPoint();
    }

    /**
     * 本次距离基准点经度——<b>有货点用货点；没给货巢才退回船位</b>。
     *
     * <p>单点收敛在这里，避免生成端与其它使用方各写一遍三元判断而分叉。
     * <b>注意</b>：{@link #hasUnresolvedCargoNest()} 为 {@code true} 时调用方应先短路返回空，
     * 不要用本方法的返回值当「船位兜底」。
     */
    public double distanceBaseLon() {
        return hasCargoPoint() ? cargoLon : pickupLon;
    }

    /** 本次距离基准点纬度（同 {@link #distanceBaseLon()}）。 */
    public double distanceBaseLat() {
        return hasCargoPoint() ? cargoLat : pickupLat;
    }

    /**
     * 机巢视图。
     *
     * @param nestId        巢 Id
     * @param lon           经度
     * @param lat           纬度
     * @param allowedTypes  可驻留机型（空列表＝未登记＝不限制）
     * @param capacity      容量的<b>剩余</b>可停数（11-05 新增，由组装器算好；
     *                      {@link Integer#MAX_VALUE}＝未登记不限制）
     * @param occupiedCount 该巢当前在巢占用数（11-05 新增，诊断用；负数＝未知）
     */
    public record NestView(
            String nestId,
            double lon,
            double lat,
            /** 可驻留机型（机型名列表） */
            List<String> allowedTypes,
            /** 剩余可停数（{@link Integer#MAX_VALUE}＝不限制） */
            int freeSlots,
            /** 当前在巢占用数（负数＝未知） */
            int occupiedCount) {

        public NestView {
            allowedTypes = allowedTypes == null ? List.of() : List.copyOf(allowedTypes);
        }

        /** 兼容构造：未登记容量（视为不限制）。 */
        public NestView(String nestId, double lon, double lat, List<String> allowedTypes) {
            this(nestId, lon, lat, allowedTypes, Integer.MAX_VALUE, -1);
        }

        /**
         * 该巢是否还能再停一架（容量闸）。
         *
         * <p>{@code freeSlots} 为 {@link Integer#MAX_VALUE}（未登记）时不拦——
         * 本闸的目的是防止「候选列出 5 架飞同一巢、其中 3 架到不了位置」，
         * 若容量根本没登记就一律拦死，等于用数据缺失换停机。未知按不限制处理，
         * 由台账补齐后自动生效。
         */
        public boolean hasFreeSlotFor() {
            return freeSlots == Integer.MAX_VALUE || freeSlots > 0;
        }
    }

    /** 无人机视图 */
    public record DroneView(
            String droneId,
            String nestId,
            String type,
            /** 状态：空闲/占用/故障/充电中（照 23 §7 既定状态词） */
            String status,
            /** 剩余电量（%）；{@code NaN}＝<b>未登记</b>（11-05 起不再用 0 冒充） */
            double battery,
            /** 最大载荷（kg）；{@code NaN}＝<b>未登记</b>（11-05 起不再用 0 冒充） */
            double maxPayloadKg,
            /**
             * 机型抗风上限（m/s）——机型能力包线「抗风等级」。
             *
             * <p>取 {@code drone.wind_limit_ms}；<b>未登记时为 {@code NaN}</b>（不写成 0，
             * 也不写成某个"看起来合理"的默认值——0 会被误判成"完全不能吹风"，
             * 默认值会造出假的机型差异）。判定见 {@code FeasibilityJudge}。
             */
            double windLimitMs,
            /** 机型巡航速度（m/s）——机型能力包线「巡航速度」；未登记时为 {@code NaN} */
            double cruiseSpeedMs) {

        /** 抗风上限是否已登记（可判定） */
        public boolean hasWindLimit() {
            return !Double.isNaN(windLimitMs) && Double.isFinite(windLimitMs) && windLimitMs > 0;
        }

        /** 巡航速度是否已登记（可判定） */
        public boolean hasCruiseSpeed() {
            return !Double.isNaN(cruiseSpeedMs) && Double.isFinite(cruiseSpeedMs) && cruiseSpeedMs > 0;
        }

        /** 电量是否已登记（可判定）——11-05 新增，配合 MED-BUG.12。 */
        public boolean hasBattery() {
            return !Double.isNaN(battery) && Double.isFinite(battery);
        }

        /** 载荷能力是否已登记（可判定）——11-05 新增，配合 MED-BUG.12。 */
        public boolean hasMaxPayload() {
            return !Double.isNaN(maxPayloadKg) && Double.isFinite(maxPayloadKg);
        }
    }
}
