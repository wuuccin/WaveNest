-- =====================================================================
-- 应急迫降点台账增加 surface_type 列（11-16 序号 2-4；1-6 已裁定退化口径）
--
-- 背景：功能10 第三级迫降点否决在台账字段缺失时退化为「距航道距离 ＋ 水面/陆地二值」，
--       水陆二值主选取台账列（天地图影像目视标定，仓库不存取点记录）；
--       兜底按 13 点中心线＋江面半宽 250 m 纯几何判、零加列。
-- 取值：水面／陆地（中文词，与 status 的可用/占用/维护同风格）。
--       不把水陆塞进 applicable_models。
--
-- ⚠ 改表 SQL 须组长点批后方可执行；点批前本脚本只在库外评审，不灌入。
--
-- 执行方式：空数据卷由 compose 按文件名顺序自动执行；已有卷在点批后手工执行：
--   Get-Content -Encoding UTF8 -Raw 019_emergency_landing_surface_type.sql |
--     docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -pwavenest wavenest
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

ALTER TABLE `emergency_landing_point`
    ADD COLUMN `surface_type` VARCHAR(16) DEFAULT NULL
        COMMENT '水面/陆地（与 status 同风格中文词；天地图影像目视标定）' AFTER `status`;
