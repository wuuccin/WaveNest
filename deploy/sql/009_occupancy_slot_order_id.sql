-- =====================================================================
-- 占位时隙台账增加 order_id 列（M2 占位释放）
--
-- 背景：第二级「迫停他巢」通过 tryReserve 在 occupancy_slot 登记一段有效占位，
-- 原表无 order_id／drone_id，订单到达「已完成」终态时无法定位该订单对应的 slot
-- 并释放，导致时隙泄漏、容量统计持续偏高。
--
-- 本迁移给 occupancy_slot 加 order_id 列：tryReserve 时写入关联订单，
-- 终态（已完成／失败）按 order_id 查到有效占位并 release。
-- 非迫停占用的历史行 order_id 为 NULL，不影响容量统计与既有查询。
--
-- 执行方式与 008 一致：空卷自动执行；已有卷手工 source 本文件即可。
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

ALTER TABLE `occupancy_slot`
    ADD COLUMN `order_id` VARCHAR(64) DEFAULT NULL COMMENT '关联订单（迫停占位用于终态释放）' AFTER `object_id`;
