-- 对已有库执行；选择 WaveNest 数据库后运行，可重复执行。
-- 完成后重启后端或调用 SysParamLoader.refresh()，使参数缓存重新加载。
START TRANSACTION;
INSERT INTO sys_param (param_key, param_value, category, description)
VALUES ('lowBatteryTrigger', '20', '阈值', '统一低电量阈值（%；G 组告警及 E 功能10 A5 共用；2026-10-18 确认）')
ON DUPLICATE KEY UPDATE param_value = '20', category = '阈值',
    description = '统一低电量阈值（%；G 组告警及 E 功能10 A5 共用；2026-10-18 确认）';
DELETE FROM sys_param WHERE param_key IN ('e10LowBatteryAbs', 'e10_low_battery_abs');
COMMIT;
