package com.waterlogistics.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.waterlogistics.contract.ws.PlanTrackFrame;
import com.waterlogistics.storage.entity.PlanTrackEntity;
import com.waterlogistics.storage.repository.PlanTrackMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code plan_track} 建表脚本、实体与 Mapper 的三方一致性（11-12 序号 2 · 张睿哲）。
 *
 * <p>判据来源：《1112-功能做实批次｜接口约定》v1.2 §2。字段口径的唯一来源是约定，
 * 因此这里把「DDL 列集合 == 实体属性集合」「唯一键与索引列顺序」「Mapper 三个方法的
 * 签名与取数顺序」全部钉死，任何一侧单独改动都会红。
 *
 * <p>纯离线单测：不连数据库、不依赖派单主链，只读脚本与反射。
 */
class PlanTrackPersistenceContractTest {

    /** 建表脚本相对仓库根的位置。 */
    private static final String SCHEMA_RELATIVE = "deploy/sql/017_plan_track.sql";

    /** 约定 §2.1 的十二个列，顺序即 DDL 声明顺序。 */
    private static final List<String> CONTRACT_COLUMNS = List.of(
            "id", "order_id", "route_id", "combo_id", "segment_kind", "seq",
            "lon", "lat", "alt_m", "speed_ms", "t_ms", "created_at");

    /** 实体属性对应的列：camelCase → snake_case，与 MyBatis-Plus 默认映射一致。 */
    private static final List<String> ENTITY_COLUMNS = List.of(
            "id", "order_id", "route_id", "combo_id", "segment_kind", "seq",
            "lon", "lat", "alt_m", "speed_ms", "t_ms", "created_at");

    private static final Pattern COLUMN_LINE =
            Pattern.compile("^\\s*`([a-z_]+)`\\s+(BIGINT|VARCHAR|INT|DECIMAL|DATETIME)\\b");

    @Test
    @DisplayName("实体表名与主键策略：plan_track + 自增主键")
    void entity_shouldMapToPlanTrackWithAutoId() {
        TableName tableName = PlanTrackEntity.class.getAnnotation(TableName.class);
        assertNotNull(tableName, "PlanTrackEntity 必须标 @TableName，否则 MyBatis-Plus 按类名猜表名");
        assertEquals("plan_track", tableName.value(), "表名必须是约定 §2.1 的 plan_track");

        TableId tableId = null;
        for (Field field : PlanTrackEntity.class.getDeclaredFields()) {
            TableId candidate = field.getAnnotation(TableId.class);
            if (candidate != null) {
                assertEquals("id", field.getName(), "唯一 @TableId 必须落在 id 上");
                tableId = candidate;
            }
        }
        assertNotNull(tableId, "缺少 @TableId，插入后拿不到自增主键");
        assertEquals(IdType.AUTO, tableId.type(), "DDL 为 AUTO_INCREMENT，实体须用 IdType.AUTO");
    }

    @Test
    @DisplayName("实体属性与约定 §2.1 的十二列逐一对齐，类型与单位口径一致")
    void entityFields_shouldMatchContractColumns() {
        List<String> derived = new ArrayList<>();
        for (Field field : PlanTrackEntity.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            derived.add(camelToSnake(field.getName()));
        }
        assertEquals(new LinkedHashSet<>(ENTITY_COLUMNS), new LinkedHashSet<>(derived),
                "实体属性集合与约定 §2.1 的列集合不一致：多列或少列都必须先改约定");

        assertFieldType("id", Long.class);
        assertFieldType("orderId", String.class);
        assertFieldType("routeId", String.class);
        assertFieldType("comboId", String.class);
        assertFieldType("segmentKind", String.class);
        assertFieldType("seq", Integer.class);
        // DECIMAL(10,7)/(8,2) 用 BigDecimal，禁止用 double 承接精度
        assertFieldType("lon", BigDecimal.class);
        assertFieldType("lat", BigDecimal.class);
        assertFieldType("altM", BigDecimal.class);
        assertFieldType("speedMs", BigDecimal.class);
        // t_ms 是 Unix 毫秒长整数
        assertFieldType("tMs", Long.class);
        assertFieldType("createdAt", LocalDateTime.class);
    }

    @Test
    @DisplayName("建表脚本：十二列齐、唯一键与两个索引齐全、纯建表不写 INSERT")
    void schema_shouldDeclareColumnsKeysAndNoSeed() throws IOException {
        String sql = Files.readString(schemaPath(), StandardCharsets.UTF_8);

        List<String> declared = new ArrayList<>();
        for (String line : sql.lines().toList()) {
            Matcher matcher = COLUMN_LINE.matcher(line);
            if (matcher.find()) {
                declared.add(matcher.group(1));
            }
        }
        assertEquals(CONTRACT_COLUMNS, declared,
                "DDL 列与约定 §2.1 不一致（含声明顺序），实际：" + declared);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `plan_track`"),
                "须用 CREATE TABLE IF NOT EXISTS，已有数据卷重复执行不得报错");
        assertTrue(sql.contains("UNIQUE KEY `uk_plan_point` (`order_id`, `route_id`, `segment_kind`, `seq`)"),
                "唯一键必须是 uk_plan_point(order_id, route_id, segment_kind, seq)，列序不得调整");
        assertTrue(sql.contains("KEY `idx_plan_order` (`order_id`)"), "缺 idx_plan_order");
        assertTrue(sql.contains("KEY `idx_plan_route` (`route_id`)"), "缺 idx_plan_route");
        assertTrue(sql.contains("ENGINE = InnoDB"), "存储引擎须为 InnoDB（事务内先删后插依赖它）");
        assertTrue(sql.contains("CHARSET = utf8mb4"), "字符集须为 utf8mb4（segment_kind 存中文原值）");
        assertTrue(sql.contains("COLLATE = utf8mb4_unicode_ci"), "排序规则须为 utf8mb4_unicode_ci");
        assertTrue(sql.contains("DEFAULT CURRENT_TIMESTAMP"), "created_at 须交库侧默认值填充");

        String executable = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + "\n")
                .toUpperCase();
        assertFalse(executable.contains("INSERT"),
                "约定 §2 要求纯建表、不写 INSERT，脚本可执行语句里不得出现 INSERT");
        assertFalse(executable.contains("DROP"),
                "脚本不得含 DROP，避免误删既有数据");
    }

    @Test
    @DisplayName("Mapper 三个方法与约定 §2.2 签名一致，取数按绘制顺序与 seq 升序")
    void mapper_shouldExposeContractMethods() throws Exception {
        Method insert = PlanTrackMapper.class.getMethod("insertBatch", List.class);
        Insert insertSql = insert.getAnnotation(Insert.class);
        assertNotNull(insertSql, "insertBatch 须用 @Insert 批量落点");
        String insertText = String.join("", insertSql.value());
        assertTrue(insertText.contains("INSERT INTO plan_track"), "insertBatch 必须写 plan_track");
        for (String column : List.of("order_id", "route_id", "combo_id", "segment_kind", "seq",
                "lon", "lat", "alt_m", "speed_ms", "t_ms")) {
            assertTrue(insertText.contains(column), "批量插入缺列：" + column);
        }
        assertFalse(insertText.contains("created_at"), "created_at 交给库默认值，不得应用写");

        Method select = PlanTrackMapper.class.getMethod("selectByOrderId", String.class);
        Select selectSql = select.getAnnotation(Select.class);
        assertNotNull(selectSql, "selectByOrderId 须用 @Select");
        String selectText = String.join("", selectSql.value());
        assertTrue(selectText.contains("WHERE order_id = #{orderId}"), "必须按订单号检索");
        String expectedOrder = "FIELD(segment_kind, '"
                + PlanTrackFrame.SEGMENT_TRANSFER + "', '"
                + PlanTrackFrame.SEGMENT_OUTBOUND + "', '"
                + PlanTrackFrame.SEGMENT_TERMINAL + "', '"
                + PlanTrackFrame.SEGMENT_RETURN + "')";
        assertTrue(selectText.contains(expectedOrder),
                "必须按 PlanTrackFrame 的绘制顺序排序，期望含：" + expectedOrder);
        assertTrue(selectText.contains("seq"), "段内须按 seq 升序");

        Method delete = PlanTrackMapper.class.getMethod("deleteByOrderId", String.class);
        Delete deleteSql = delete.getAnnotation(Delete.class);
        assertNotNull(deleteSql, "deleteByOrderId 须用 @Delete");
        assertTrue(String.join("", deleteSql.value()).contains("DELETE FROM plan_track"),
                "deleteByOrderId 必须删 plan_track");
    }

    @Test
    @DisplayName("分段口径词与 PlanTrackFrame 常量逐字一致（中文原值不翻译）")
    void segmentKinds_shouldMatchFrameConstants() {
        Set<String> kinds = new LinkedHashSet<>(Arrays.asList(
                PlanTrackFrame.SEGMENT_TRANSFER,
                PlanTrackFrame.SEGMENT_OUTBOUND,
                PlanTrackFrame.SEGMENT_TERMINAL,
                PlanTrackFrame.SEGMENT_RETURN));
        assertEquals(Set.of("空机转场", "去程", "末段交接", "返程"), kinds,
                "分段口径词与《22-接口契约》§4／《36-M5-可视化》§3 必须逐字一致");
        assertEquals("规划航迹", PlanTrackFrame.OP_TYPE,
                "task_log 摘要操作词固定为「规划航迹」，一条派发一条");
    }

    private static void assertFieldType(String fieldName, Class<?> expected) {
        try {
            Field field = PlanTrackEntity.class.getDeclaredField(fieldName);
            assertEquals(expected, field.getType(), "字段 " + fieldName + " 类型不符");
        } catch (NoSuchFieldException e) {
            throw new AssertionError("实体缺字段：" + fieldName, e);
        }
    }

    /** camelCase → snake_case，与 MyBatis-Plus 默认映射口径一致。 */
    private static String camelToSnake(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (char ch : name.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                out.append('_').append(Character.toLowerCase(ch));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** 定位建表脚本：Gradle 测试工作目录为模块目录，也兼容从仓库根运行。 */
    private static Path schemaPath() {
        for (String candidate : List.of(SCHEMA_RELATIVE, "../../" + SCHEMA_RELATIVE)) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("找不到 " + SCHEMA_RELATIVE
                + "，当前工作目录=" + Path.of("").toAbsolutePath());
    }
}
