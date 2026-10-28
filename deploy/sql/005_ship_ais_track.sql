-- =====================================================================
-- WaveNest 船舶实测轨迹落库保障 · ship_ais（T2-4 · 杨添爻 · 2026-10-28）
--
-- ★ 执行顺序：001_init.sql → 002_sys_param.sql → 003_seed_drone_nest.sql
--             → 004_seed_airspace.sql（三桥限高）→ 本文件（005）
--
-- ★ 编号更正（2026-10-28）：本文件原拟用 004，但 004 已被 004_seed_airspace.sql
--   （三桥限高，T2-10）占用，按组长口径改为 005。本文件只补表结构、不含 INSERT，
--   与 003/004 的数据灌入无先后依赖，排在最后执行不影响任何现有数据。
--
--
-- 为什么有这个文件（10-28 T2-4）：
--   ship_ais（37 §5.10）的表结构本属 001_init.sql §5.10，实体与 Mapper
--   （ShipAisEntity / ShipAisMapper）也早已就位，但此前全仓没有任何消费者
--   把 data.ais 写进库——M1 发的帧只被 M2 预测链与前端实时出口消费，
--   库里查不到轨迹点，「实测轨迹」落空。本次补齐落库链（M6 消费侧监听器，
--   见 wl-app 的 com.waterlogistics.app.ingest.ShipAisIngestListener），
--   本文件保障表在重灌场景下必定存在。
--
-- 与 001 的关系：
--   001 的 CREATE TABLE 带 IF NOT EXISTS，正常走 001 即可建表；本文件用
--   information_schema 判缺再补建（与 003 补列同模式），专治两类情况——
--   ① 旧库是在 001 加入 ship_ais 之前初始化的；② 重灌流程按序号顺序执行，
--   本文件兜底，谁先谁后都不冲突。可重复执行，无 INSERT，不与 003 的
--   「删卷重建」口径冲突（表结构 DDL 幂等）。
--
-- 归一口径（落库方负责，代码在 ShipAisIngestListener）：
--   · sog：契约 AisMessage 单位是节，本表列注释为 m/s → 落库前 ×0.514444；
--   · lon/lat：WGS84/CGCS2000 十进制度，DECIMAL(10,7)；
--   · ts：Unix 毫秒 → Asia/Shanghai 的 DATETIME。
--   M1 是 ship_ais 唯一写入方（37 §5.10 口径），本监听器即 M1 帧的落库代理。
-- =====================================================================

USE `wavenest`;

-- ---------------------------------------------------------------------
-- 补建 ship_ais（仅当不存在；列定义与 001_init.sql §5.10 一字不差）
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'ship_ais') = 0,
    'CREATE TABLE `ship_ais` (
        `id`        BIGINT        NOT NULL AUTO_INCREMENT COMMENT ''自增主键（同一船多条轨迹记录）'',
        `ship_mmsi` VARCHAR(16)   NOT NULL COMMENT ''船舶标识'',
        `ts`        DATETIME      NOT NULL COMMENT ''时间戳'',
        `lon`       DECIMAL(10,7) NOT NULL COMMENT ''经度（已归一 CGCS2000/WGS84）'',
        `lat`       DECIMAL(10,7) NOT NULL COMMENT ''纬度（已归一）'',
        `sog`       DECIMAL(8,2)  NULL COMMENT ''航速（已归一 m/s）'',
        `cog`       DECIMAL(5,2)  NULL COMMENT ''航向（已归一 度）'',
        PRIMARY KEY (`id`),
        KEY `idx_ais_mmsi_ts` (`ship_mmsi`, `ts`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=''船舶 AIS 轨迹表（37 §5.10；原始 AIS 值，M1 唯一写入方）''',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
