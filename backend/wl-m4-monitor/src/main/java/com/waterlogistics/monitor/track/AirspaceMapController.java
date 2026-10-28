package com.waterlogistics.monitor.track;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.storage.entity.AirspaceRestrictionEntity;
import com.waterlogistics.storage.repository.AirspaceRestrictionMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** T2-10 read-only projection of active airspace records for the operations map. */
@RestController
public final class AirspaceMapController {

    private static final Logger log = LoggerFactory.getLogger(AirspaceMapController.class);
    private static final Pattern BRIDGE_SCOPE = Pattern.compile(
            "\\{\\s*\"kind\"\\s*:\\s*\"bridge\"\\s*,\\s*\"center\"\\s*:\\s*\\{\\s*"
                    + "\"lon\"\\s*:\\s*([-+0-9.eE]+)\\s*,\\s*\"lat\"\\s*:\\s*([-+0-9.eE]+)\\s*}\\s*,\\s*"
                    + "\"clearanceHeightM\"\\s*:\\s*([-+0-9.eE]+)\\s*,\\s*"
                    + "\"spanWidthM\"\\s*:\\s*([-+0-9.eE]+)\\s*}");
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "BRIDGE-CHAOYANG", "朝阳大桥",
            "BRIDGE-NANCHANG", "南昌大桥",
            "BRIDGE-BAYI", "八一大桥");

    private final AirspaceRestrictionMapper mapper;

    public AirspaceMapController(AirspaceRestrictionMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/api/airspace")
    public List<MapFeature> activeFeatures() {
        List<AirspaceRestrictionEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<AirspaceRestrictionEntity>()
                        .eq(AirspaceRestrictionEntity::getStatus, "生效")
                        .orderByAsc(AirspaceRestrictionEntity::getRestrictionId));
        List<MapFeature> result = new ArrayList<>();
        if (rows == null) return result;
        for (AirspaceRestrictionEntity row : rows) {
            MapFeature feature = bridgeFeature(row);
            if (feature != null) result.add(feature);
        }
        return result;
    }

    static MapFeature bridgeFeature(AirspaceRestrictionEntity row) {
        if (row == null || row.getScope() == null) return null;
        Matcher match = BRIDGE_SCOPE.matcher(row.getScope());
        if (!match.matches()) {
            log.warn("忽略无法投影到地图的空域台账：restrictionId={}", row.getRestrictionId());
            return null;
        }
        try {
            Point center = new Point(decimal(match.group(1)), decimal(match.group(2)));
            return new MapFeature(row.getRestrictionId(), DISPLAY_NAMES.getOrDefault(
                    row.getRestrictionId(), row.getRestrictionId()), "bridge", row.getType(), center,
                    decimal(match.group(3)), decimal(match.group(4)), row.getSource());
        } catch (NumberFormatException error) {
            log.warn("忽略坐标或净空数值非法的空域台账：restrictionId={}", row.getRestrictionId());
            return null;
        }
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    public record Point(BigDecimal lon, BigDecimal lat) {
    }

    public record MapFeature(String restrictionId, String name, String kind, String type, Point center,
                             BigDecimal clearanceHeightM, BigDecimal spanWidthM, String source) {
    }
}
