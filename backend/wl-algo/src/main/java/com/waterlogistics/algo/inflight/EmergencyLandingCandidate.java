package com.waterlogistics.algo.inflight;

import com.waterlogistics.contract.rest.GeoPoint;

/**
 * 第三级迫降点候选（L4 迫降点层「台账候选 → 否决筛除 → 可达性」的候选输入），
 * 依据《84》§7.6 第 7 步、§5.1 台账否决、§8.4 退化口径（11-16 任务清单 1-6／2-2）。
 *
 * <p><b>字段由模块层算好自带，本算法层不读台账、不做几何</b>（{@code wl-algo} 零 Spring／零 DB）：
 * <ul>
 *   <li>{@code distanceToCenterlineM} 需中心线台账（SQL 013／014／016）算出后注入；</li>
 *   <li>{@code surface}（水面／陆地）主选由 {@code surface_type} 列给出；SQL 点批来不及时的兜底，
 *       由装配层按 13 点中心线＋江面半宽 250 m 判水陆后注入——<b>选择器对此无感知</b>；</li>
 *   <li>缺 {@code distanceToCenterlineM}／{@code surface}／坐标为 null 的候选一律按「不可算」淘汰，
 *       <b>不默认水面、不取 0</b>（1-6 裁定）。</li>
 * </ul>
 *
 * <p>本批只消费距航道距离＋水陆二值两项否决条件；坡度、地表类型、人群密度、通信条件加列拆独立步，
 * 不塞进 {@code applicableModels}。
 *
 * @param elpId                迫降点标识（非空）
 * @param position             迫降点坐标（非空）
 * @param distanceToCenterlineM 到航道中心线的垂距，<b>米</b>；可空＝模块层未算，按不可算淘汰
 * @param surface              水陆二值「水面」／「陆地」；可空＝未标定，按不可算淘汰
 * @param available            台账状态是否可用（可用／占用／维护之外推导）；false 直接淘汰
 */
public record EmergencyLandingCandidate(
        String elpId,
        GeoPoint position,
        Double distanceToCenterlineM,
        String surface,
        boolean available) {

    /** 水陆二值取值（与 surface_type 列、status 同风格用中文词）。 */
    public static final String SURFACE_WATER = "水面";
    public static final String SURFACE_LAND = "陆地";

    public EmergencyLandingCandidate {
        if (elpId == null || elpId.isBlank()) {
            throw new IllegalArgumentException("迫降点候选须有 elpId，无法定位落点");
        }
        if (position == null) {
            throw new IllegalArgumentException("迫降点候选 " + elpId + " 缺坐标，不得用机位冒充");
        }
        if (distanceToCenterlineM != null
                && (!Double.isFinite(distanceToCenterlineM) || distanceToCenterlineM < 0)) {
            throw new IllegalArgumentException("迫降点候选 " + elpId
                    + " 的距航道垂距须为非负有限值（米），实测=" + distanceToCenterlineM);
        }
    }
}
