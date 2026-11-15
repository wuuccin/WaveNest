-- =====================================================================
-- 计划航迹点表 plan_track（11-12 序号 2 · 张睿哲）
--
-- 口径：字段与写入点以《1112-功能做实批次｜接口约定》v1.2 §2 为唯一来源，
--   本节为登记副本（37 §5.17 的 develop 副本同源）；改字段须先改约定。
--   只记计划点，实际飞行点写 flight_point（37 §5.11），两表不互相替代。
--
-- 写入点：MissionTrackAssembler 组出 PlanTrackFrame 后，在同一派单事务内调
--   PlanTrackMapper.insertBatch；task_log 只写一条 op_type = 规划航迹 摘要。
--   先落库再推 plan_track WS 帧，落库失败抛异常回滚，禁止先推帧后落库。
--
-- 重规划：换新 route_id，旧 route_id 行保留供复盘；唯一键含 route_id 故不冲突；
--   同一 order_id + route_id 的覆写走 deleteByOrderId 后重插，同事务先删后插。
--
-- 幂等：纯建表、不写 INSERT；CREATE TABLE IF NOT EXISTS，已有卷重复执行无副作用。
--
-- 执行方式：空数据卷由 compose 按 deploy/sql 文件名顺序自动执行；已有卷手工执行
--   （执行后只新建表，不触碰既有订单、任务与日志）。
-- 注意：Windows PowerShell 用 Get-Content 管道灌库会把中文注释打成 ????（控制台编码），
--   已验证结构不受影响但注释损坏，故一律走「先拷进容器再重定向」：
--   docker cp deploy/sql/017_plan_track.sql wavenest-mysql:/tmp/017_plan_track.sql
--   docker exec wavenest-mysql sh -c \
--     "mysql --default-character-set=utf8mb4 -uroot -pwavenest wavenest < /tmp/017_plan_track.sql"
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

CREATE TABLE IF NOT EXISTS `plan_track` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，表内自增',
    `order_id`     VARCHAR(64)     NOT NULL COMMENT '订单号（PlanTrackFrame.orderId）；按订单查回计划航迹的检索键',
    `route_id`     VARCHAR(64)     NOT NULL COMMENT '航迹编号，与 B_FlyableRoute.routeId 同源；重规划换号，旧号保留供复盘',
    `combo_id`     VARCHAR(64)     NULL COMMENT '组合编号（PlanTrackFrame.comboId），未组批写 NULL',
    `segment_kind` VARCHAR(32)     NOT NULL COMMENT '分段口径词：空机转场/去程/末段交接/返程，中文原值不翻译',
    `seq`          INT             NOT NULL COMMENT '段内点序，自 1 起',
    `lon`          DECIMAL(10,7)   NOT NULL COMMENT '经度，十进制度 [-180,180]，CGCS2000/WGS-84，不做 GCJ-02 偏移',
    `lat`          DECIMAL(10,7)   NOT NULL COMMENT '纬度，十进制度 [-90,90]，CGCS2000/WGS-84，不做 GCJ-02 偏移',
    `alt_m`        DECIMAL(8,2)    NULL COMMENT '高度，米，非负有限数',
    `speed_ms`     DECIMAL(8,2)    NULL COMMENT '速度，米每秒，非负有限数',
    `t_ms`         BIGINT          NOT NULL COMMENT '到达该点时刻，Unix 毫秒',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时刻，库侧默认值填充',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_point` (`order_id`, `route_id`, `segment_kind`, `seq`),
    KEY `idx_plan_order` (`order_id`),
    KEY `idx_plan_route` (`route_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计划航迹点表（只记计划点，实际飞行点写 flight_point）';
