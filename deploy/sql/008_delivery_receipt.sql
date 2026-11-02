-- =====================================================================
-- 投放回执超时参数（阶段一 18）
--
-- 与 tHandoverMinSec 分开登记：后者是「交接时长下界」，决定按时间兜底何时
-- 推进返航；本键只用于「回执超时后判交接失败」的口径，两者可能不同。
--
-- 执行方式与 007 一致：空卷自动执行；已有卷手工 source 本文件即可。
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

INSERT IGNORE INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('deliveryReceiptTimeoutSec', '120', '间隔', '投放回执超时（s）：超时未收到回执按交接失败兜底，独立于 tHandoverMinSec');
