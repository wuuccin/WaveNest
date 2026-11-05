-- =====================================================================
-- 订单表增加货物所在巢、等待原因、预计送达时刻三列（11-05 序号 1 数据契约）
--
-- 背景：阶段二起订单需要区分「货物所在巢」（可与执行机当前巢不同），
-- 等待态需要落库具体等待原因供恢复与前端展示，ETA 需要由系统算出并落库
-- （不再只放内存/视图）。
--
--   cargo_nest_id  货物所在巢；NULL＝未指定，按离船最近巢处理（与 OrderSubmitRequest 一致）
--   wait_reason    等待原因（英文编码）：WAIT_SHIP / WAIT_CARGO / GATE_OR_DATA / WAIT_RESOURCE；
--                  仅在「已受理待唤醒」有值，离开等待态由状态流转清空
--   eta_ts         系统算出的预计送达时刻；NULL＝尚未算出
--
-- 执行方式：空数据卷由 compose 初始化自动执行；
-- 已有卷手工执行：docker exec -i <mysql容器> mysql -uroot -p<pwd> wavenest < 010_order_cargo_eta.sql
-- （按 11-05 任务清单 §七.2，新增 SQL 不改 001_init.sql）
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

ALTER TABLE `order_task`
    ADD COLUMN `cargo_nest_id` VARCHAR(64)  DEFAULT NULL COMMENT '货物所在巢；NULL＝未指定（取离船最近巢）' AFTER `ship_lat`,
    ADD COLUMN `wait_reason`   VARCHAR(32)  DEFAULT NULL COMMENT '等待原因编码：WAIT_SHIP/WAIT_CARGO/GATE_OR_DATA/WAIT_RESOURCE；仅已受理待唤醒时有值' AFTER `status`,
    ADD COLUMN `eta_ts`        DATETIME     DEFAULT NULL COMMENT '系统算出的预计送达时刻；NULL＝尚未算出' AFTER `meet_time`;
