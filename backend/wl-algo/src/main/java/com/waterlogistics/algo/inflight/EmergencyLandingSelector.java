package com.waterlogistics.algo.inflight;

import com.waterlogistics.contract.rest.GeoPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * 第三级迫降点选择器（《84》§7.6 第 7 步 L4 迫降点层的「否决筛除」段），11-16 任务清单 1-6／2-2。
 *
 * <p><b>只消费调用方注入的候选点，不读台账、不做几何、零 Spring／零 DB</b>：候选点的距航道垂距、
 * 水陆属性都由模块层算好（见 {@link EmergencyLandingCandidate}），本类只按两项否决条件筛出第一个可用点。
 * {@code landCorridorHalfWidthM} 阈值由调用方从 {@code e10LandingCorridorHalfWidthM} 读出后注入，
 * <b>不在算法层硬编码 500</b>；建议值见 {@link HomecomingParams#SUGGESTED_LANDING_CORRIDOR_HALF_WIDTH_M}。
 *
 * <p><b>否决口径（两项）</b>：① 距航道垂距 &gt; 半宽阈值——落在航道走廊之外，否决；
 * ② 水陆属性须为「水面」——陆地不可迫降，否决。可用状态为 false 者直接淘汰。
 * <b>字段缺失按不可算淘汰并记原因，不臆造</b>：垂距／水陆为 null，或阈值未注入（null），
 * 一律判该候选「不可算」而非默认水面／取 0。坡度、地表、人群、通信加列拆独立步，本批不判。
 *
 * <p><b>无可用点是合法结果</b>：返回空结果并带原因，由 {@link HomecomingReview} 第三级退化为
 * {@code targetId=null + reason}，不臆造迫降点，也不因此推翻第三级落点级别。
 */
public final class EmergencyLandingSelector {

    /** 否决原因：落在航道走廊之外 */
    public static final String REJECT_OUTSIDE_CORRIDOR = "OUTSIDE_CORRIDOR";
    /** 否决原因：水陆属性为陆地 */
    public static final String REJECT_ON_LAND = "ON_LAND";
    /** 否决原因：台账状态不可用 */
    public static final String REJECT_NOT_AVAILABLE = "NOT_AVAILABLE";
    /** 否决原因：字段缺失或阈值未注入，不可算 */
    public static final String REJECT_UNCOMPUTABLE = "UNCOMPUTABLE";

    private EmergencyLandingSelector() {
        // 静态选择器，禁止实例化
    }

    /**
     * 从注入的候选点里筛出第一个可用迫降点。
     *
     * @param candidates           候选点列表（模块层算好自带）；可为 null 视作无候选
     * @param corridorHalfWidthM   距航道半宽阈值，米（{@code e10LandingCorridorHalfWidthM}）；
     *                             可空＝阈值未注入，此时全部候选判不可算
     * @return 选中结果与逐项否决审计；无可用点时 {@code selected=null}
     */
    public static EmergencyLandingSelection select(List<EmergencyLandingCandidate> candidates,
            Double corridorHalfWidthM) {
        List<EmergencyLandingReject> audit = new ArrayList<>();
        if (candidates != null) {
            for (EmergencyLandingCandidate c : candidates) {
                if (c == null) {
                    continue;
                }
                String reject = rejectReason(c, corridorHalfWidthM);
                if (reject == null) {
                    return new EmergencyLandingSelection(c.elpId(), c.position(), List.copyOf(audit));
                }
                audit.add(new EmergencyLandingReject(c.elpId(), reject));
            }
        }
        return new EmergencyLandingSelection(null, null, List.copyOf(audit));
    }

    /** 返回 null 表示通过全部否决条件；否则返回否决原因码。 */
    private static String rejectReason(EmergencyLandingCandidate c, Double corridorHalfWidthM) {
        if (!c.available()) {
            return REJECT_NOT_AVAILABLE;
        }
        // 阈值或候选字段缺失一律判不可算，不臆造 0／水面
        if (corridorHalfWidthM == null || c.distanceToCenterlineM() == null || c.surface() == null) {
            return REJECT_UNCOMPUTABLE;
        }
        if (c.distanceToCenterlineM() > corridorHalfWidthM) {
            return REJECT_OUTSIDE_CORRIDOR;
        }
        if (!EmergencyLandingCandidate.SURFACE_WATER.equals(c.surface())) {
            return REJECT_ON_LAND;
        }
        return null;
    }

    /** 选择器结果记录：选中点（可选）＋逐项否决审计。 */
    public record EmergencyLandingSelection(String elpId, GeoPoint position,
            List<EmergencyLandingReject> audit) {
        public EmergencyLandingSelection {
            audit = audit == null ? List.of() : List.copyOf(audit);
        }

        /** 是否选到了可用迫降点。 */
        public boolean hasSelection() {
            return elpId != null && !elpId.isBlank();
        }
    }

    /** 单个候选的否决审计（可审计，供 reason 汇总结论）。 */
    public record EmergencyLandingReject(String elpId, String reason) {
    }
}
