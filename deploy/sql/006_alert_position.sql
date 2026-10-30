-- =====================================================================
-- WaveNest 告警表扩列：位置 ＋ 检测值落库
--
-- 任务：序号三「告警位置落库」——补齐 alert 表 lon/lat/current_value/threshold_value 四列，
-- 使告警触发时的目标位置与检测阈值随 alert 行一并持久化（此前仅随 alert.event 消息携带，
-- 重启后历史告警位置丢失）。
--
-- 设计决策（参考 alert落库_给张睿哲.txt）：
--   · 新增独立幂等文件 006，不改 001_init.sql
--     理由：改 001 需全队 down -v 清库重灌，订单/任务/日志/轨迹全丢；
--           001 纯 INSERT 零 DELETE/TRUNCATE，手工重跑会主键冲突，只能靠清库；
--           演化期每改一次加一个幂等文件，与已合入的 005 同一风格。
--   · 四列与 37 §5.12 现有 8 列并存，不删不改。
--   · 全部可空：失联告警 current_value 恒为 null；无最后已知位置时 lon/lat 为 null。
--
-- 执行方式：不依赖 docker compose up -d，手工灌库：
--   docker cp deploy/sql/006_alert_position.sql wavenest-mysql:/tmp/
--   docker exec wavenest-mysql mysql -uroot -pwavenest \
--       --default-character-set=utf8mb4 -e "source /tmp/006_alert_position.sql"
-- 验：SHOW COLUMNS FROM `alert`;
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

-- ---------------------------------------------------------------------
-- 经度（告警目标最后已知位置）
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'alert' AND COLUMN_NAME = 'lon') = 0,
    'ALTER TABLE `alert` ADD COLUMN `lon` DOUBLE NULL COMMENT ''告警目标位置-经度（失联取最后一次真实上报）'' AFTER `handle_status`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 纬度
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'alert' AND COLUMN_NAME = 'lat') = 0,
    'ALTER TABLE `alert` ADD COLUMN `lat` DOUBLE NULL COMMENT ''告警目标位置-纬度'' AFTER `lon`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 当前检测值（偏航距离 m / 电量 % / 风速 m/s；失联时为 null）
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'alert' AND COLUMN_NAME = 'current_value') = 0,
    'ALTER TABLE `alert` ADD COLUMN `current_value` DOUBLE NULL COMMENT ''当前检测值（偏航距离/电量/风速；失联为 null）'' AFTER `lat`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 阈值（与 current_value 同量纲）
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'alert' AND COLUMN_NAME = 'threshold_value') = 0,
    'ALTER TABLE `alert` ADD COLUMN `threshold_value` DOUBLE NULL COMMENT ''阈值（与 current_value 同量纲）'' AFTER `current_value`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
