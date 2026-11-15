-- =====================================================================
-- 航道中心线台账 v3：2026-11-09 影像河心重取 13 点
--
-- 背景：v2（014）的 13 点是从《73》三个示意锚点线性加密出来的，锚点经度落在
--   东岸（朝阳 115.852/28.646、南昌 115.859/28.663、八一 115.879/28.692），
--   仿真船沿旧线跑到弯道会压岸。2026-11-09 在天地图 img_w 影像上按河心重取
--   13 点（WGS-84），全长 6003.3510 m；v2 保留不改写，读取器取最新版。
--
-- s_m 口径：按 WaterwayGeometry.segmentLengthM 复算累计弧长，
--   METERS_PER_DEG_LAT = π × 6371000 / 180 = 111194.92664455873，
--   每段 latRef 取两端纬度均值，dx = Δlon × 常数 × cos(latRef)，dy = Δlat × 常数。
--   顶点与 application.yml 的 prediction.centerline、admission.segment-vertices
--   的 1/6/13 点同源；004 的三桥中心取这里的 1/6/13 点。
--
-- 幂等：唯一键 uk_chart_ver_seq(chart_id, version, seq)，
--   INSERT ... ON DUPLICATE KEY UPDATE 只对 version = 3 行生效；
--   重复执行只更新这 13 行，不产生第二组行，也不触碰 version = 1/2。
--
-- 执行方式：空数据卷由 compose 初始化自动执行；已有卷手工执行：
--   docker compose -f deploy/docker-compose.yml exec -T mysql mysql -uroot -pwavenest wavenest < deploy/sql/016_centerline_v3.sql
-- Windows PowerShell：
--   Get-Content -Encoding UTF8 -Raw deploy/sql/016_centerline_v3.sql | docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -pwavenest wavenest
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

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
    ('GANJIANG', 3,  1,       0.0000, 115.844393, 28.645998),
    ('GANJIANG', 3,  2,     511.3912, 115.847602, 28.649634),
    ('GANJIANG', 3,  3,    1015.2520, 115.850273, 28.653512),
    ('GANJIANG', 3,  4,    1539.1750, 115.854263, 28.656665),
    ('GANJIANG', 3,  5,    2019.6943, 115.858212, 28.659247),
    ('GANJIANG', 3,  6,    2524.0104, 115.857937, 28.663776),
    ('GANJIANG', 3,  7,    3052.8931, 115.858769, 28.668476),
    ('GANJIANG', 3,  8,    3515.4177, 115.860851, 28.672213),
    ('GANJIANG', 3,  9,    4059.0016, 115.862310, 28.676931),
    ('GANJIANG', 3, 10,    4574.0294, 115.864577, 28.681114),
    ('GANJIANG', 3, 11,    5044.8372, 115.866785, 28.684879),
    ('GANJIANG', 3, 12,    5571.6111, 115.869281, 28.689080),
    ('GANJIANG', 3, 13,    6003.3510, 115.872180, 28.692014)
ON DUPLICATE KEY UPDATE
    `s_m` = VALUES(`s_m`),
    `lon` = VALUES(`lon`),
    `lat` = VALUES(`lat`);
