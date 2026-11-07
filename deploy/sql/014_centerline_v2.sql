-- =====================================================================
-- 航道中心线台账 v2：13 点同源折线（11-07 弥补计划 D-07／D-12／D-19）
--
-- 背景：013_centerline.sql 灌的是 3 个锚点、全长 5778.9 m；而
--   wavenest.m2.prediction.centerline 配置串是同三锚点按 ≤500 m 加密的 13 点。
-- 台账优先，故灌库前后生产参考线对象会改变，验证对象与运行对象错位（P-08）。
-- 本脚本单写一组 version = 2 的 13 行，读取器取最新版，运行对象与
-- 报告 §8.3.1 的 13 点口径一致。013 的 version = 1 旧行不改写。
--
-- s_m 口径：按 WaterwayGeometry.segmentLengthM 复算累计弧长，
--   METERS_PER_DEG_LAT = π × 6371000 / 180 = 111194.92664455873，
--   每段 latRef 取两端纬度均值，dx = Δlon × 常数 × cos(latRef)，dy = Δlat × 常数。
-- 顶点坐标逐点取自 application.yml 的 centerline 配置串，未做任何放样调整。
-- 中心线三个锚点坐标按 D-04 保持定稿值不改。
--
-- 幂等：唯一键 uk_chart_ver_seq(chart_id, version, seq)，用
--   INSERT ... ON DUPLICATE KEY UPDATE 且只对 version = 2 行生效；
-- 重复执行只更新这 13 行的 s_m/lon/lat，不产生第二组行，也不触碰 version = 1。
--
-- 执行方式：空数据卷由 compose 初始化自动执行；已有卷手工执行（与
--   CenterlineLedger.HYDRATE_COMMAND 同一条命令，可直接复制）：
--   docker compose -f deploy/docker-compose.yml exec -T mysql mysql -uroot -pwavenest wavenest < deploy/sql/014_centerline_v2.sql
-- Windows PowerShell 亦可走管道（防重定向编码问题）：
--   Get-Content -Encoding UTF8 -Raw deploy/sql/014_centerline_v2.sql | docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -pwavenest wavenest
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

-- 013 未执行过（表不存在）时先建表，保证单独灌 014 也能成功。
CREATE TABLE IF NOT EXISTS `centerline_point` (
    `id`      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `chart_id`   VARCHAR(32)  NOT NULL COMMENT '台账号：一段一档',
    `version`    INT          NOT NULL DEFAULT 1 COMMENT '折线版本号，换曲线递增',
    `seq`        INT          NOT NULL COMMENT '顶点序号，按航道走向从 1 起',
    `s_m`        DOUBLE       NOT NULL COMMENT '沿程弧长 m，自首点起算（自检列，读取器重算比对）',
    `lon`        DOUBLE       NOT NULL COMMENT '经度，度，CGCS2000/WGS-84',
    `lat`        DOUBLE       NOT NULL COMMENT '纬度，度，CGCS2000/WGS-84',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chart_ver_seq` (`chart_id`, `version`, `seq`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '航道中心线台账（折线点表）';

INSERT INTO `centerline_point`
    (`chart_id`, `version`, `seq`, `s_m`, `lon`, `lat`) VALUES
    ('GANJIANG', 2,  1,       0.0000, 115.852000, 28.646000),
    ('GANJIANG', 2,  2,     499.4026, 115.853739, 28.650224),
    ('GANJIANG', 2,  3,     998.8360, 115.855479, 28.654448),
    ('GANJIANG', 2,  4,    1498.3386, 115.857218, 28.658673),
    ('GANJIANG', 2,  5,    2009.9314, 115.859000, 28.663000),
    ('GANJIANG', 2,  6,    2497.2093, 115.861586, 28.666749),
    ('GANJIANG', 2,  7,    2996.7528, 115.864236, 28.670593),
    ('GANJIANG', 2,  8,    3496.2468, 115.866887, 28.674436),
    ('GANJIANG', 2,  9,    3995.6853, 115.869537, 28.678279),
    ('GANJIANG', 2, 10,    4495.2646, 115.872188, 28.682123),
    ('GANJIANG', 2, 11,    4994.6933, 115.874838, 28.685966),
    ('GANJIANG', 2, 12,    5494.1676, 115.877489, 28.689809),
    ('GANJIANG', 2, 13,    5778.9088, 115.879000, 28.692000)
ON DUPLICATE KEY UPDATE
    `s_m` = VALUES(`s_m`),
    `lon` = VALUES(`lon`),
    `lat` = VALUES(`lat`);
