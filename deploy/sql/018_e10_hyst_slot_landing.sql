-- =====================================================================
-- E 功能10 迟滞两值、归巢时隙两值与迫降点走廊半宽（11-16 序号 2-1／2-4）
--
-- 依据：11-16 任务清单 1-2（迟滞比率语义）、1-3（占位时长两键语义）、
--       1-6（迫降点退化口径 500 m）。消费方：dispatch/alert/HomecomingInputAssembler.params()。
--
--   e10HystEnterRatio   迟滞进入线（相对储备裕度倍数）：1.0
--   e10HystExitRatio    迟滞退出线（相对储备裕度倍数）：0.6（0.4 倍死区防乒乓；原 1.2 作废）
--   e10SlotBuffer       归巢时隙近进缓冲（毫秒）：58000＝58 s，保留原值
--                       ——窗口下界 = 到达 − 缓冲
--   e10SlotHold         落位后作业占用时长（毫秒）：300000＝300 s（011 原为 58000，本次改写）
--                       ——窗口上界 = 到达 ＋ 占用 ＋ 缓冲
--   e10LandingCorridorHalfWidthM
--                       迫降点距航道半宽阈值（米）：500（84 §7.4 退化口径）
--
-- 幂等：ON DUPLICATE KEY UPDATE，键已存在时改写取值与说明。
-- 执行方式：空数据卷由 compose 按文件名顺序自动执行（晚于 011，故 e10SlotHold 会被改写）；
-- 已有卷手工执行（管道方式，防 Windows 重定向编码问题）：
--   Get-Content -Encoding UTF8 -Raw 018_e10_hyst_slot_landing.sql |
--     docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -pwavenest wavenest
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('e10HystEnterRatio',          '1.0',    '阈值', 'E 功能10 迟滞进入线（相对储备裕度倍数；84 §4.3；2026-11-16 裁定）'),
('e10HystExitRatio',           '0.6',    '阈值', 'E 功能10 迟滞退出线（相对储备裕度倍数；0.4 倍死区防乒乓；原 1.2 作废）'),
('e10SlotBuffer',              '58000',  '间隔', 'E 功能10 归巢时隙近进缓冲（毫秒；58 s；窗口下界 = 到达 − 缓冲）'),
('e10SlotHold',                '300000', '间隔', 'E 功能10 落位后作业占用时长（毫秒；300 s；窗口上界 = 到达 ＋ 占用 ＋ 缓冲）'),
('e10LandingCorridorHalfWidthM', '500',  '阈值', 'E 功能10 迫降点距航道半宽阈值（米；84 §7.4 退化口径）')
ON DUPLICATE KEY UPDATE
    `param_value` = VALUES(`param_value`),
    `category`    = VALUES(`category`),
    `description` = VALUES(`description`);
