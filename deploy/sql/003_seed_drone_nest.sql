-- =====================================================================
-- WaveNest 演示实例数据：机巢 nest ＋ 无人机 drone
--
-- ★ 执行前必读（字符集，勿删）：
--   本文件含 UTF-8 中文，必须先 SET NAMES utf8mb4 再灌，否则客户端按 latin1
--   解读，中文变乱码。乱码的后果不是「显示不好看」，而是**状态词失配**：
--   drone.status 存成乱码后，候选链按 `status='空闲'` 查不到任何无人机，
--   表现为「订单无可飞候选：候选 0 个全部被淘汰」，而数据表面看着齐全。
--   同理 nest.status 存成乱码会过滤掉全部机巢。
--
-- ★ 执行顺序：001_init.sql → 002_sys_param.sql → 本文件（003 依赖 001 的表结构）
--
-- 权威依据：
--   · 表结构与列语义：37-M6-数据库 §5.1（nest）／§5.2（drone）
--   · 机型能力包线（抗风上限、巡航速度）：62-C组算法方案 功能 3 输入
--     「机型能力包线：巡航/最大速度、最大续航、最大载荷、抗风等级」
--   · 状态词：23 §7（无人机）／§10（机巢）
--   · 航段与两端坐标：73-赣江场景说明 §3（南昌赣江段，朝阳大桥→八一大桥，
--     全程约 5.78km）
--
-- 为何要有这份数据（10-20 B1）：
--   `feasibility` 判据 windclass_match 是「机型抗风等级 ≥ 任务区最不利风级」。
--   在此之前 drone 表没有抗风列，判定端只能吃调用方传的一个统一上限，
--   于是「同一风速下 A 型淘汰、B 型保留」在数据上不可能发生。补列之后
--   还需要「不同机型抗风不同」的实例行，否则列恒为同一个值，判据仍是摆设。
--   故此处按两个抗风档位灌机型，令风速落在两档之间时能观察到分叉。
--
-- 坐标口径（2026-10-28 图上放样定稿，勿回退）：
--   两座机巢落在**南昌赣江演示航段两端**，坐标为彭紫情 10-28 序号 2
--   图上放样定稿值（天地图影像取点，CGCS2000/WGS-84 基准）：
--     · NEST-01 南端（朝阳端）：115.8480000, 28.6420000——朝阳大桥南侧；
--     · NEST-02 北端（八一端）：115.8670000, 28.6980000——八一大桥北侧。
--   船起点定稿 115.8630000, 28.6680000（杨添爻 T2-7 在仿真器侧生效）；
--   船起点距两巢约 3.2/3.4 km，均在候选距离阈值（maxNestDistanceKm=5km）内。
--   M1 仿真器默认值已随 T1-4 切到南昌段，无人机数量与本机队规模
--   对齐（4 架），编号口径同为 UAV-%02d。
--
-- 取值说明（抗风上限为演示标定值，不是厂商手册值）：
--   · M30T 类多旋翼：抗风上限取 10.0 m/s（≈5 级风上界，与 FeasibilityJudge
--     原统一上限 10.0 同档，保证「未登记回落」与「已登记」两口径在本档不打架）；
--   · M400 类重载：抗风上限取 12.0 m/s（重载机型风载裕度更大）；
--   · M3T 类轻型：抗风上限取 10.0 m/s（轻型机归入低抗风档）。

-- 字符集声明：必须在任何 INSERT 之前（勿删、勿移到文件末尾）
SET NAMES utf8mb4;
--   · 两档之间留 2 m/s 窗口：10～12 m/s 区间的风就能分出「A 淘汰、B 保留」。
--   巡航速度：M30T 12.0 m/s（与 sys_param.droneCruiseSpeedMs 兜底同值）、
--   M400 / M3T 15.0 m/s——令「机型速度不同」在会面链上也能体现。
--   载重三档：M3T 2 kg ／ M30T 6 kg ／ M400 20 kg，令「载重不足淘汰」可演示。
--   ！这些是演示标定值，答辩若被问「数值哪来的」，答「演示标定，非厂商手册值，
--     机型档案落地后以档案为准」，不要当成实测数据。
--
-- 可重复执行：全部用 INSERT ... ON DUPLICATE KEY UPDATE，重跑只刷新不报错；
--   另对 10-27 前的两架旧编号幽灵机（DRN-M400-01/-02，无遥测源）做一次定点
--   DELETE，旧数据卷上手工重跑本文件也能清掉它们。
-- =====================================================================

USE `wavenest`;

-- ---------------------------------------------------------------------
-- 0) 先升列（旧库补列）
--    001_init.sql 的 CREATE TABLE 带 IF NOT EXISTS，对已存在的旧 drone 表不会补列；
--    而下面的 INSERT 直接引用新列，故补列必须排在最前，否则旧库执行会 1054。
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'drone' AND COLUMN_NAME = 'wind_limit_ms') = 0,
    'ALTER TABLE `drone` ADD COLUMN `wind_limit_ms` DECIMAL(5,2) NULL COMMENT ''机型抗风上限（m/s）'' AFTER `max_endurance`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'drone' AND COLUMN_NAME = 'cruise_speed_ms') = 0,
    'ALTER TABLE `drone` ADD COLUMN `cruise_speed_ms` DECIMAL(5,2) NULL COMMENT ''机型巡航速度（m/s）'' AFTER `wind_limit_ms`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 机巢（2 座）：南昌赣江演示航段两端，各支持本巢停靠机型
--   NEST-01 南端（朝阳端）：支持 M30T＋M400（本巢停 UAV-01 M30T、UAV-02 M400）
--   NEST-02 北端（八一端）：支持 M30T＋M3T （本巢停 UAV-03 M30T、UAV-04 M3T）
--   ★ supported_models 必须与下方兵配同步，否则台账出现
--     「只支持某机型却停着另一机型」的矛盾（T1-5 边界要求）。
-- ---------------------------------------------------------------------
INSERT INTO `nest` (`nest_id`, `lon`, `lat`, `capacity`, `supported_models`, `owner`, `status`) VALUES
('NEST-01', 115.8480000, 28.6420000, 4, 'M30T,M400', '演示运营方', '可用'),
('NEST-02', 115.8670000, 28.6980000, 4, 'M30T,M3T',  '演示运营方', '可用')
ON DUPLICATE KEY UPDATE
    `lon` = VALUES(`lon`), `lat` = VALUES(`lat`),
    `capacity` = VALUES(`capacity`), `supported_models` = VALUES(`supported_models`),
    `owner` = VALUES(`owner`), `status` = VALUES(`status`);

-- ---------------------------------------------------------------------
-- 无人机（4 架，2026-10-27 T1-5 重配：2 机巢 × 各 2 架，全部「空闲」可用）
--   UAV-01 NEST-01 M30T：载重 6 kg  抗风 10.0 巡航 12.0
--   UAV-02 NEST-01 M400：载重 20 kg 抗风 12.0 巡航 15.0（高抗风档＋重载档）
--   UAV-03 NEST-02 M30T：载重 6 kg  抗风 10.0 巡航 12.0
--   UAV-04 NEST-02 M3T ：载重 2 kg  抗风 10.0 巡航 15.0（轻载档）
--
--   编号口径：UAV-%02d，与 M1 仿真器 SimUavTelemetryConnector.uavId()
--   完全一致——机队里每一架都有遥测源，不再存在「派单落在无遥测机型上
--   导致回放 404」的幽灵机，故旧版「M400 置维护不参与派单」的规避
--   安排随 T1-5 一并取消，四架全部「空闲」进入候选链。
-- ---------------------------------------------------------------------
-- 清理 10-27 前旧编号的幽灵机（无遥测源；定点删除，可重复执行）
DELETE FROM `drone` WHERE `drone_id` IN ('DRN-M400-01', 'DRN-M400-02');

INSERT INTO `drone`
    (`drone_id`, `home_nest_id`, `current_nest_id`, `position_type`, `model`,
     `max_payload`, `max_endurance`, `wind_limit_ms`, `cruise_speed_ms`,
     `battery`, `health_status`, `status`) VALUES
('UAV-01', 'NEST-01', 'NEST-01', '原巢', 'M30T',  6.00, 2400, 10.00, 12.00, 100.00, '健康', '空闲'),
('UAV-02', 'NEST-01', 'NEST-01', '原巢', 'M400', 20.00, 3000, 12.00, 15.00,  98.00, '健康', '空闲'),
('UAV-03', 'NEST-02', 'NEST-02', '原巢', 'M30T',  6.00, 2400, 10.00, 12.00,  95.00, '健康', '空闲'),
('UAV-04', 'NEST-02', 'NEST-02', '原巢', 'M3T',   2.00, 2700, 10.00, 15.00,  92.00, '健康', '空闲')
ON DUPLICATE KEY UPDATE
    `home_nest_id` = VALUES(`home_nest_id`), `current_nest_id` = VALUES(`current_nest_id`),
    `position_type` = VALUES(`position_type`), `model` = VALUES(`model`),
    `max_payload` = VALUES(`max_payload`), `max_endurance` = VALUES(`max_endurance`),
    `wind_limit_ms` = VALUES(`wind_limit_ms`), `cruise_speed_ms` = VALUES(`cruise_speed_ms`),
    `battery` = VALUES(`battery`), `health_status` = VALUES(`health_status`),
    `status` = VALUES(`status`);
