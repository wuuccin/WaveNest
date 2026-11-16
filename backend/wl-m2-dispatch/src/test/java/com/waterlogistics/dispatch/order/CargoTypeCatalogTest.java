package com.waterlogistics.dispatch.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waterlogistics.contract.order.CargoType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 配送品类有效集合解析的守门测试。 */
class CargoTypeCatalogTest {

    @Test
    @DisplayName("默认全集按契约声明顺序返回四项中文品类")
    void defaults_shouldFollowContractOrder() {
        assertEquals(List.of("医疗物资", "应急药品", "食品", "生活物资"),
                CargoTypeCatalog.defaults());
    }

    @Test
    @DisplayName("未配置时回落到契约默认全集")
    void resolve_withNull_shouldFallbackToDefaults() {
        assertEquals(CargoType.defaultValues(),
                List.copyOf(CargoTypeCatalog.resolve(null)));
    }

    @Test
    @DisplayName("合法显式子集按配置顺序保留，不补回全集其余项")
    void resolve_withSubset_shouldKeepConfiguredOrder() {
        Set<String> effective = CargoTypeCatalog.resolve("食品, 应急药品");

        assertEquals(List.of("食品", "应急药品"), List.copyOf(effective));
        assertEquals("食品、应急药品", CargoTypeCatalog.displayText(effective));
    }

    @Test
    @DisplayName("未知取值在启动期失败，不静默过滤")
    void resolve_withUnknownValue_shouldFailFast() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CargoTypeCatalog.resolve("食品,备件"));

        assertTrue(error.getMessage().contains("备件"));
    }

    @Test
    @DisplayName("显式空集合在启动期失败，不静默过滤")
    void resolve_withExplicitEmpty_shouldFailFast() {
        assertThrows(IllegalArgumentException.class, () -> CargoTypeCatalog.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> CargoTypeCatalog.resolve("  ,  "));
    }

    @Test
    @DisplayName("有效集合不可被外部修改")
    void resolve_shouldReturnUnmodifiableSet() {
        Set<String> effective = CargoTypeCatalog.resolve("食品");

        assertThrows(UnsupportedOperationException.class, () -> effective.add("医疗物资"));
    }
}
