-- =====================================================================
-- WaveNest 演示实例数据：空域限制 airspace_restriction（三座桥梁限高）
--
-- ★ 执行前必读（字符集，勿删）：本文件含 UTF-8 中文，必须先 SET NAMES utf8mb4
--   再灌，否则中文按 latin1 解读变乱码（与 003 同坑）。
--
-- ★ 执行顺序：001_init.sql → 002_sys_param.sql → 003_seed_drone_nest.sql → 本文件
--
-- 权威依据：
--   · 表结构：37-M6-数据库 §5.9（airspace_restriction，scope 为自由文本 TEXT）
--   · 桥梁坐标：73-赣江场景说明 §3（南昌赣江段：朝阳大桥→南昌大桥→八一大桥）
--
-- scope 格式约定（2026-10-27 T1-5 定稿，已群里通报——后续谁都按这个写）：
--   scope 存一行紧凑 JSON（不美化、不换行），桥梁限高固定写法：
--     {"kind":"bridge","center":{"lon":<经度>,"lat":<纬度>},
--      "clearanceHeightM":<净空高 米>,"spanWidthM":<通航孔宽 米>}
--   字段说明：
--     kind             空间对象种类，桥梁限高固定 "bridge"；将来其它种类
--                      （禁飞圆、多边形）另起 kind 值并在此文件登记
--     center           桥梁中心点 WGS-84 经纬度（十进制度）
--     clearanceHeightM 净空高度（米），无人机过桥下限高判据
--     spanWidthM       通航孔宽度（米）
--   解析口径：标准 JSON 解析器直读；key 全集如上，缺 key 视为格式错误。
--
-- 取值说明：桥梁中心坐标取自 73 §3 航段标定；净空高与通航孔宽为**演示标定值**
--   （非官方通航数据），答辩被问时答「演示标定，以海事公告落地后为准」。
--
-- 可重复执行：INSERT ... ON DUPLICATE KEY UPDATE，重跑只刷新不报错。
-- =====================================================================

SET NAMES utf8mb4;

USE `wavenest`;

-- ---------------------------------------------------------------------
-- 三座桥梁限高（航段下游→上游：朝阳大桥 → 南昌大桥 → 八一大桥）
--   type='限高'（列注释四值之一：禁飞区/限飞区/限高/航道管制）
--   effective_start/end 置 NULL＝长期有效；status 取默认 '生效'
-- ---------------------------------------------------------------------
INSERT INTO `airspace_restriction`
    (`restriction_id`, `type`, `scope`, `effective_start`, `effective_end`, `source`, `status`) VALUES
('BRIDGE-CHAOYANG', '限高',
 '{"kind":"bridge","center":{"lon":115.852,"lat":28.646},"clearanceHeightM":12.0,"spanWidthM":80.0}',
 NULL, NULL, '演示标定：73-赣江场景说明 §3（朝阳大桥）', '生效'),
('BRIDGE-NANCHANG', '限高',
 '{"kind":"bridge","center":{"lon":115.859,"lat":28.663},"clearanceHeightM":10.0,"spanWidthM":60.0}',
 NULL, NULL, '演示标定：73-赣江场景说明 §3（南昌大桥）', '生效'),
('BRIDGE-BAYI',     '限高',
 '{"kind":"bridge","center":{"lon":115.879,"lat":28.692},"clearanceHeightM":14.0,"spanWidthM":100.0}',
 NULL, NULL, '演示标定：73-赣江场景说明 §3（八一大桥）', '生效')
ON DUPLICATE KEY UPDATE
    `type` = VALUES(`type`), `scope` = VALUES(`scope`),
    `effective_start` = VALUES(`effective_start`), `effective_end` = VALUES(`effective_end`),
    `source` = VALUES(`source`), `status` = VALUES(`status`);
