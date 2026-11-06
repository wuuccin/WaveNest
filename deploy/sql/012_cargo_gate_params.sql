-- =====================================================================
-- 货到巢关与队列延误惩罚参数（11-05 序号 2 队列与资源）
--
-- 背景：序号 2 引入「货到巢关」（派单前锁巢时隙、货未到转等待、锁超时转资源等待）
-- 与候选打分「延误惩罚」一级比较键，两处阈值/权重按约定走 sys_param，不硬编码。
--
--   cargoLockTimeoutSec  巢时隙锁超时秒数：订单持锁等货超过该时长仍未就绪，
--                        释放锁并把 wait_reason 置为 WAIT_RESOURCE
--   delayPenaltyWeight   候选打分延误惩罚权重：>0 时在 distKm 前插入
--                        weight×max(0, eta-deadline) 一级比较键；0＝关闭（退回纯距离序）
--
-- 执行方式：空数据卷由 compose 初始化自动执行；
-- 已有卷手工执行（管道方式，防 Windows 重定向编码问题）：
--   Get-Content -Encoding UTF8 -Raw 012_cargo_gate_params.sql | docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -p<pwd> wavenest
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

INSERT IGNORE INTO `sys_param` (`param_key`, `param_value`) VALUES
    ('cargoLockTimeoutSec', '300'),
    ('delayPenaltyWeight', '1.0');
