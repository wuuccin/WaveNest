-- =====================================================================
-- 航道中心线台账（11-06 序号 2 · 杨添爻）
--
-- 背景：精算预测的参考线此前只有两条来源——配置串
--   wavenest.m2.prediction.centerline（lon,lat;...）与「本船最近实测轨迹 ＋ 尾端直线延伸」
--   的退化口径（PrecisePredictionEngine 注释「退化 1」）。本批新建台账表，把赣江定稿
--   航段折线灌成台账，参考线改读台账（C-01 缺省做法：折线点表；拓扑边表留后续）。
--
-- 表结构（折线点表，每行一个顶点）：
--   chart_id  台账号（一段一档，演示期固定 GANJIANG）
--   version   折线版本号（同台账号换曲线时递增，读取器取最新版；换版本不改代码）
--   seq       顶点序号（按航道走向排列，从 1 起）
--   s_m       沿程弧长，m（自折线首点起算的累计折线长度）
--   lon/lat   顶点经纬度（CGCS2000/WGS-84，与全库同基准，不做 GCJ-02 偏移）
--
-- s_m 是台账自检列：读取器按顶点序列重建几何后重算弧长，与本列比对（几何一致性
-- 测试的判据），不一致视为台账灌错。读取器构造 WaterwayGeometry 只用顶点序列，
-- 不用本列，避免两套里程算法漂移。
--
-- 定稿依据（2026-10-28 图上放样，106-演示基准值台账「已定」，坐标勿改）：
--   朝阳 115.852/28.646 → 南昌 115.859/28.663 → 八一 115.879/28.692
--   折线全长 5785.5 m；NEST-01（115.848/28.642）垂距 592 m、沿程 0.00 km；
--   NEST-02（115.867/28.698）垂距 1349 m、沿程 5.75 km；
--   船起点（115.8630/28.6680，11-06 清单口径）垂距 46 m、沿程 2.69 km；
--   两巢直线距离 6.50 km。
--
-- 执行方式：空数据卷由 compose 初始化自动执行；
-- 已有卷手工执行（管道方式，防 Windows 重定向编码问题）：
--   Get-Content -Encoding UTF8 -Raw 013_centerline.sql | docker exec -i wavenest-mysql mysql --default-character-set=utf8mb4 -uroot -p<pwd> wavenest
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '航道中心线台账（折线点表，11-06 序号 2）';

-- 赣江定稿航段（朝阳—南昌—八一），版本 1。
-- s_m 为按几何类口径复算的累计折线长（每段米/度用段中点参考纬度、1° 纬度取
-- GeoApprox 的地球半径 6371 km 口径），与读取器重算值逐点可比：
--   0 / 2009.9 / 5778.9 m。
-- 与 106-演示基准值台账「精确值 5785.5 km·m」的 6.6 m（0.11%）差异来自米/度常数口径
-- （106 用 111320 m/°，代码用 111194.9 m/°），不是灌错；台账自检列以代码口径为准。
INSERT IGNORE INTO `centerline_point`
    (`chart_id`, `version`, `seq`, `s_m`, `lon`, `lat`) VALUES
    ('GANJIANG', 1, 1,      0.0, 115.852, 28.646),
    ('GANJIANG', 1, 2,   2009.9, 115.859, 28.663),
    ('GANJIANG', 1, 3,   5778.9, 115.879, 28.692);
