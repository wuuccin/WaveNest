-- =====================================================================
-- 货巢决策归属改造：货巢列注释更正与决策开关登记
--
-- 背景：货物所在巢由下单人手工指定改为系统在下单时刻决策，
--       order_task.cargo_nest_id 的含义随之改变：
--       由「NULL＝未指定（取离船最近巢）」改为「NULL＝系统未选定货巢」。
--       010_order_cargo_eta.sql 是已发布脚本，改它只对新环境生效，
--       已有环境用本脚本更正列注释，避免各环境注释漂移。
--
--   cargoNestDecisionEnabled  货巢决策开关：true＝下单时刻由系统选巢；
--                        false＝关闭选巢，下单后链路行为与改造前完全一致，
--                        作为应急回退手段
--
-- 执行方式：空数据卷由 compose 初始化自动执行；
-- 已有卷手工执行（管道方式，防 Windows 重定向编码问题）：
--   Get-Content -Encoding UTF8 -Raw 015_cargo_nest_decision.sql | docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -p<pwd> wavenest
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

ALTER TABLE `order_task`
    MODIFY COLUMN `cargo_nest_id` VARCHAR(64) DEFAULT NULL
        COMMENT '货物所在巢；NULL＝系统未选定货巢（决策开关关闭或选不出可用巢）' AFTER `ship_lat`;

INSERT IGNORE INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
    ('cargoNestDecisionEnabled', 'true', '开关',
     '货巢决策开关：true＝下单时刻由系统选巢；false＝关闭选巢，下单后链路行为与改造前完全一致');
