-- =====================================================================
-- 候选快照表 candidate_snapshot（11-19 序号 3-1 · 张睿哲）
--
-- 口径：字段按《11-19 任务清单》§3 张睿哲·二（裁定 7）的九项——
--   订单号、机型、机巢、机号、距离、会面时刻、落选原因、择优键值、快照版本。
--   一行 = 一次派单决策里的一个候选组合；中选者的 reject_reason 写「中选」，
--   落选者写判定端逐条原因（与 wl-algo FeasibilityJudge 文案同源，不另造词）。
--
-- 写入点：DispatchOrchestrator 第 6 步（会面时刻定案后、航迹落库前）调用
--   CandidateSnapshotService.record，同一派单事务内批量落行；本表<b>不写 task_log</b>
--   （候选留痕不得与任务日志混流，task_log 里不会出现候选行）。
--   快照写入失败只记 WARN、不阻断派单（留痕是旁路证据，不是派单前置条件）。
--
-- 会面时刻口径：仅中选候选有值（定案会面时刻，含错峰时移后的值）；
--   落选候选未进入会面求解，该列为 NULL——不拿「转场 ETA」冒充会面时刻。
--
-- 择优键值口径：与 FeasibleCandidatePicker 的两级择优键同式——
--   delay = delayPenaltyWeight × max(0, eta − deadline)，先比 delay 再比 distKm；
--   rank_key 落成 "delay=…;dist=…" 便于排障时肉眼核对排序。
--
-- 幂等：纯建表、不写 INSERT；CREATE TABLE IF NOT EXISTS，已有数据卷重复执行无副作用。
--
-- 执行方式：空数据卷由 compose 按 deploy/sql 文件名顺序自动执行；已有卷手工执行。
--   本脚本属「改表 SQL 须点批」范围，合入前请组长确认后再对已有卷执行。
--   手工执行一律「先拷进容器再重定向」（PowerShell 管道灌库会把中文注释打成 ????）：
--   docker cp deploy/sql/020_candidate_snapshot.sql wavenest-mysql:/tmp/020_candidate_snapshot.sql
--   docker exec wavenest-mysql sh -c \
--     "mysql --default-character-set=utf8mb4 -uroot -pwavenest wavenest < /tmp/020_candidate_snapshot.sql"
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

CREATE TABLE IF NOT EXISTS `candidate_snapshot` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，表内自增；同时作为同一订单内的候选生成序',
    `order_id`         VARCHAR(64)     NOT NULL COMMENT '订单号，按订单回查候选留痕的检索键',
    `model`            VARCHAR(64)     NULL COMMENT '机型（CandidateCombo.type），组合缺失时为 NULL',
    `nest_id`          VARCHAR(64)     NULL COMMENT '机巢（组合出发巢）',
    `drone_id`         VARCHAR(64)     NULL COMMENT '机号（执行机实例）',
    `dist_km`          DECIMAL(10,3)   NULL COMMENT '机巢到取货点距离，km，粗估直线；上游为 NaN 时不落库（写 NULL）',
    `meet_time`        DATETIME(3)     NULL COMMENT '会面时刻：仅中选候选有值（定案会面时刻，含错峰时移）；落选候选为 NULL',
    `reject_reason`    VARCHAR(512)    NULL COMMENT '落选原因（判定端中文原文，多条以「；」连接）；中选者写「中选」',
    `rank_key`         VARCHAR(128)    NULL COMMENT '择优键值 "delay=…;dist=…"，与 FeasibleCandidatePicker 两级键同式',
    `snapshot_version` VARCHAR(64)     NULL COMMENT '快照版本（RendezvousCoordinator.MeetPlan.snapshotVersion，一致性锚）',
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '写入时刻，库侧默认值填充，应用不写',
    PRIMARY KEY (`id`),
    KEY `idx_candidate_order` (`order_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '候选快照表（一次派单决策的逐候选取舍留痕，不写 task_log）';
