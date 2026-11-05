package com.waterlogistics.collector.web;

import com.waterlogistics.contract.rest.ApiPath;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础数据台账端点单测（10-21 C4）。
 *
 * <p>映射逻辑是纯静态函数，直接构造实体即可测，不碰数据库。
 * 端点级「非空且与种子一致」的验收由真库联调承担（判据原文），这里守三件事：
 * <ol>
 *   <li>B1 两列原样透传（null 不被造值）；</li>
 *   <li>{@code supportedModels} 拆分与容错；</li>
 *   <li>路径必须取 {@link ApiPath} 常量，不许写字面量。</li>
 * </ol>
 */
class FleetControllerTest {

    // ===== 映射：机队行 =====

    @Test
    @DisplayName("机队行全字段映射，B1 两列原样透传（与 003 种子口径一致）")
    void droneRowMapsAllFields() {
        DroneEntity d = new DroneEntity();
        d.setDroneId("UAV-01");
        d.setHomeNestId("NEST-01");
        d.setCurrentNestId("NEST-01");
        d.setPositionType("原巢");
        d.setModel("M30T");
        d.setMaxPayload(new BigDecimal("6.00"));
        d.setMaxEndurance(2400);
        d.setWindLimitMs(new BigDecimal("10.00"));
        d.setCruiseSpeedMs(new BigDecimal("12.00"));
        d.setBattery(new BigDecimal("100.00"));
        d.setHealthStatus("健康");
        d.setStatus("空闲");

        FleetController.DroneRow row = FleetController.toRow(d);

        assertEquals("UAV-01", row.droneId());
        assertEquals("NEST-01", row.homeNestId());
        assertEquals("原巢", row.positionType());
        assertEquals("M30T", row.model());
        assertEquals(new BigDecimal("6.00"), row.maxPayload());
        assertEquals(2400, row.maxEndurance());
        assertEquals(new BigDecimal("10.00"), row.windLimitMs(),
                "抗风上限应与 003 种子的 M30T 档一致");
        assertEquals(new BigDecimal("12.00"), row.cruiseSpeedMs(),
                "巡航速度应与 003 种子的 M30T 档一致");
        assertEquals(new BigDecimal("100.00"), row.battery());
        assertEquals("空闲", row.status());
    }

    @Test
    @DisplayName("M400 档位：抗风 12.00 / 巡航 15.00（与 003 种子一致）")
    void m400TierValuesMapThrough() {
        DroneEntity d = new DroneEntity();
        d.setDroneId("DRN-M400-01");
        d.setModel("M400");
        d.setWindLimitMs(new BigDecimal("12.00"));
        d.setCruiseSpeedMs(new BigDecimal("15.00"));

        FleetController.DroneRow row = FleetController.toRow(d);
        assertEquals(new BigDecimal("12.00"), row.windLimitMs());
        assertEquals(new BigDecimal("15.00"), row.cruiseSpeedMs());
    }

    @Test
    @DisplayName("未登记能力：null 原样透传，不造默认值（B1 口径：候选端按回落处理）")
    void unregisteredCapabilityStaysNull() {
        DroneEntity d = new DroneEntity();
        d.setDroneId("X-01");
        // windLimitMs / cruiseSpeedMs 不设值

        FleetController.DroneRow row = FleetController.toRow(d);
        assertNull(row.windLimitMs(), "未登记必须保持 null——造默认值等于凭空造机型差异");
        assertNull(row.cruiseSpeedMs());
    }

    // ===== 映射：机巧行 =====

    @Test
    @DisplayName("机巧行：supportedModels 拆分并去空白")
    void nestRowSplitsModels() {
        NestEntity n = new NestEntity();
        n.setNestId("NEST-01");
        n.setLon(new BigDecimal("114.3050000"));
        n.setLat(new BigDecimal("30.6050000"));
        n.setCapacity(4);
        n.setSupportedModels("M30T, M400"); // 带空格，应被 trim
        n.setOwner("演示运营方");
        n.setStatus("可用");

        FleetController.NestRow row = FleetController.toRow(n);
        assertEquals(List.of("M30T", "M400"), row.supportedModels(),
                "逗号拆分后应去掉两侧空白");
        assertEquals(new BigDecimal("114.3050000"), row.lon());
        assertEquals(4, row.capacity());
    }

    @Test
    @DisplayName("supportedModels 为 null / 空串 / 纯逗号时给空数组")
    void nestRowToleratesBlankModels() {
        NestEntity n = new NestEntity();
        n.setNestId("N1");
        n.setSupportedModels(null);
        assertEquals(List.of(), FleetController.toRow(n).supportedModels());

        n.setSupportedModels("");
        assertEquals(List.of(), FleetController.toRow(n).supportedModels());

        n.setSupportedModels(" , , ");
        assertEquals(List.of(), FleetController.toRow(n).supportedModels(),
                "纯空白片段应被滤成空数组");
    }

    // ===== 路径纪律 =====

    @Test
    @DisplayName("路径取 ApiPath 常量：不写字面量（契约纪律）")
    void pathsComeFromContractConstants() throws Exception {
        Method drones = FleetController.class.getMethod("drones");
        Method nests = FleetController.class.getMethod("nests");

        GetMapping gd = drones.getAnnotation(GetMapping.class);
        GetMapping gn = nests.getAnnotation(GetMapping.class);

        assertArrayEquals(new String[]{ApiPath.DRONES}, gd.value(),
                "/api/drones 必须来自 ApiPath.DRONES");
        assertArrayEquals(new String[]{ApiPath.NESTS}, gn.value(),
                "/api/nests 必须来自 ApiPath.NESTS");
        // @GetMapping 本身即固定 GET，无需再断言 method
        assertTrue(gd.value()[0].startsWith("/api/"), "契约口径：前缀统一 /api/");
        assertTrue(gn.value()[0].startsWith("/api/"));
    }
}
