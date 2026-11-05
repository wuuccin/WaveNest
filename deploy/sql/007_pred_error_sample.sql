-- =====================================================================
-- WaveNest 预测误差样本表 pred_error_sample
--
-- 任务：11-02 序号一（卢肄文）——阶段二 5「回流校准」。预测与实测的配对残差
-- 此前只活在进程内（NeesCalibrator / PredictionBiasCalibrator 的累加器），
-- 重启即失、无法复核、无法离线复标。本表是这份样本的持久化载体，
-- 也是「跑一批 AIS 后表里有行」这条判据的落点。
--
-- 11-03 D2 追加（依据《11-03 任务计划（一）》C6 裁定）：
--   · 加列 `v_along_ms`：偏差校准器按「时域 × 船速」分桶，船速必须随样本落库。
--     此前只在内存里带一下，落库后就丢了，离线复标无法重建分桶。
--   · 唯一键改四元组 `(ship_mmsi, pred_version, tau_sec, sample_ts)`：
--     进程内水位（consumedUpToTs）重启即清零，同一批样本会被重新插入一遍。
--     这次改为「库侧兜底」——重复插入交给唯一键拒绝，应用记「跳过」而不是失败。
--     四元组里必须含 sample_ts：同一版本、同一 τ 在不同时刻会有不同残差
--     （τ 是按秒取整的，1 s 内两条观测会撞在同一个 τ 上）。
--   · 落库失败连续 3 次记 error（阈值是代码常量，不进 sys_param，见
--     PredictionCalibrationService.PERSIST_FAILURE_ALERT_THRESHOLD）。
--
-- 字段口径（与 80-A组算法方案 §5.1 的 NEES 公式一一对应）：
--   ν = (err_along_m, err_cross_m)，Cov = R(ψ)·diag(σ_along², σ_cross²)·R(ψ)ᵀ
--   NEES = (err_along/σ_along)² + (err_cross/σ_cross)²
-- 故表里除清单要求的「MMSI／预测版本／τ／纵向／横向／航向误差／样本时刻」七项外，
-- 另存两条 σ——不存 σ 就得为了算 NEES 回算法层重推当时那一版协方差，
-- 而那一版随新快照覆盖已不可复现（快照不落库，见 PredictionService 类注释）。
--
-- 符号约定（校准器与离线复核都依赖它，改动须同步改 PredictionCalibrationService）：
--   err_along_m  > 0 = 船比预测跑得快（实测在预测位置之前，沿航道方向）
--   err_cross_m  > 0 = 实测点偏航道右侧
--   err_course_deg ∈ [-180, 180]，取最短弧（同 PrecisePredictionEngine.signedArcDeg 口径）
--   v_along_ms   带符号；上行正值、下行负值（与滤波器内 v_s 同口径）
--
-- 为什么是 DOUBLE 而不是 DECIMAL：残差是浮点测量量，不参与金额类等值比较；
-- 且 DOUBLE 与算法层 double 直通，避免 BigDecimal 往返的舍入与装箱开销。
--
-- 执行方式（与 005/006 同一风格：幂等文件，不改 001_init.sql）：
--   ① 空数据卷：docker compose down -v && docker compose up -d（挂载自动执行）
--   ② 已有数据卷，手工灌：
--      docker cp deploy/sql/007_pred_error_sample.sql wavenest-mysql:/tmp/
--      docker exec wavenest-mysql mysql -uroot -pwavenest \
--          --default-character-set=utf8mb4 -e "source /tmp/007_pred_error_sample.sql"
--   验：SHOW COLUMNS FROM `pred_error_sample`; SHOW INDEX FROM `pred_error_sample`;
--
-- 为什么建表用 CREATE TABLE IF NOT EXISTS、补列补键用 006 那套 PREPARE/EXECUTE：
-- 本表在 11-02 已随 develop 合入并可能在别人机器上建好了，只写 CREATE 不会给旧表补列。
-- MySQL 无 ADD COLUMN IF NOT EXISTS，只能查 information_schema 后动态拼 SQL（同 006）。
--
-- ⚠ 唯一键是「破坏性」变更的边界：若库里已有重复四元组，加唯一键会直接失败。
--   故本文件先按四元组去重（保留 id 最小的一条）再加键。去重语句本身幂等，
--   无重复时是空操作。
-- =====================================================================

SET NAMES utf8mb4;
USE `wavenest`;

CREATE TABLE IF NOT EXISTS `pred_error_sample` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `ship_mmsi`      VARCHAR(16) NOT NULL COMMENT '船舶 MMSI（样本归属，看台/复标按此聚合）',
    `pred_version`   BIGINT      NOT NULL COMMENT '产生该预测的快照版本号（PredictionSnapshot.version）',
    `tau_sec`        INT         NOT NULL COMMENT '预测时域 τ，s（预测点时刻 − 预测起始时刻 t0）',
    `err_along_m`    DOUBLE      NOT NULL COMMENT '纵向误差，m；>0 实测在预测位置之前（船跑快了）',
    `err_cross_m`    DOUBLE      NOT NULL COMMENT '横向误差，m；>0 实测点偏航道右侧',
    `err_course_deg` DOUBLE      NULL COMMENT '航向误差，度，取最短弧 [-180,180]；无有效实测航向时为空',
    `sigma_along_m`  DOUBLE      NOT NULL COMMENT '该预测点的纵向 σ∥，m（NEES 分母，标准差非方差）',
    `sigma_cross_m`  DOUBLE      NOT NULL COMMENT '该预测点的横向 σ⊥，m（NEES 分母）',
    `v_along_ms`     DOUBLE      NOT NULL COMMENT '该拍后验纵向速度估计，m/s（带符号；偏差校准器按此分桶）',
    `sample_ts`      DATETIME(3) NOT NULL COMMENT '样本时刻（实测点时刻，毫秒精度），配对与分桶基准',
    `create_time`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时刻',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pes_pair` (`ship_mmsi`, `pred_version`, `tau_sec`, `sample_ts`),
    KEY `idx_pes_mmsi_ts` (`ship_mmsi`, `sample_ts`),
    KEY `idx_pes_version` (`pred_version`),
    KEY `idx_pes_create` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测误差样本表（80 §5.1 NEES 回流校准的持久化载体；M2 唯一写入方）';

-- ---------------------------------------------------------------------
-- 11-02 建过的旧表：补 v_along_ms
-- ---------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'pred_error_sample'
        AND COLUMN_NAME = 'v_along_ms') = 0,
    'ALTER TABLE `pred_error_sample` ADD COLUMN `v_along_ms` DOUBLE NOT NULL DEFAULT 0 COMMENT ''该拍后验纵向速度估计，m/s（带符号；偏差校准器按此分桶）'' AFTER `sigma_cross_m`',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧表补列时给了 DEFAULT 0：既有行的船速无从追溯，用 0 表示「未知」，
-- 比让 ALTER 失败或写 NULL（列上 NOT NULL）更可执行。补完即去掉默认值，
-- 逼应用必须写——不然新代码漏写这一列会静默落 0，分桶统计就被污染了。
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'pred_error_sample'
        AND COLUMN_NAME = 'v_along_ms' AND COLUMN_DEFAULT IS NOT NULL) > 0,
    'ALTER TABLE `pred_error_sample` MODIFY COLUMN `v_along_ms` DOUBLE NOT NULL COMMENT ''该拍后验纵向速度估计，m/s（带符号；偏差校准器按此分桶）''',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 加唯一键前先去重（保留 id 最小的一条）
--
-- 只在唯一键还不存在时做：键加上之后再跑 DELETE 是纯粹的浪费，
-- 而且每次启动都扫一遍全表。判断放在同一段里，保证「去重 → 加键」原子地只发生一次。
-- ---------------------------------------------------------------------
SET @dedupe = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'pred_error_sample'
        AND INDEX_NAME = 'uk_pes_pair') = 0,
    'DELETE p1 FROM `pred_error_sample` p1 JOIN `pred_error_sample` p2 ON p1.ship_mmsi = p2.ship_mmsi AND p1.pred_version = p2.pred_version AND p1.tau_sec = p2.tau_sec AND p1.sample_ts = p2.sample_ts AND p1.id > p2.id',
    'DO 0'));
PREPARE stmt FROM @dedupe; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = 'wavenest' AND TABLE_NAME = 'pred_error_sample'
        AND INDEX_NAME = 'uk_pes_pair') = 0,
    'ALTER TABLE `pred_error_sample` ADD UNIQUE KEY `uk_pes_pair` (`ship_mmsi`, `pred_version`, `tau_sec`, `sample_ts`)',
    'DO 0'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 校准参数键（11-02 阶段二 5）
--
-- 为什么写在本文件而不是 002_sys_param.sql：002 是纯 INSERT，重跑会主键冲突，
-- 只能靠清库重灌（见 006 头部说明）；本文件用 INSERT IGNORE，重复执行无副作用，
-- 可以在已灌过数据的环境里单独跑。
--
-- 只登记「现场可能调」的四个键；NeesCalibrator 的 200 样本 / 0.15 区间宽是
-- 《80》§5.1 的验收门槛（算法常量），不配成参数——配成可改就会出现
-- 「同一批数据跑出两种采信结论」，复标结果无法互相比较。
--
-- 注意：这四个键是「校准器回写目标」，与预测引擎实际读的 alphaAlongMs／alphaCrossMs
-- （002，现 0.10／0.03）是两组键。按《11-03 计划一》C5 裁定，校准器的「当前 α」必须等于
-- 预测引擎实际用的值，否则 suggestedValue = currentAlpha × (1+ratio) 会基于错误基准给出
-- 偏高或偏低的调整建议：纵向 predAlphaAlongMs 取定稿值 0.10，横向 predAlphaCrossMs 取 0.03，
-- 分别与 002 的 alphaAlongMs／alphaCrossMs 及测试显式 Params 三处同值；Java 侧的同一兜底见
-- PredictionBiasCalibrator.Params.defaults()。
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('calCalibrateIntervalSec', '60',     '间隔', '每船回流校准的节流间隔（s）；0 或负值按缺省 60 处理'),
('predAlphaAlongMs',        '0.10',   '阈值', '纵向 σ 增长率 α_along 基准（C5 定稿 0.10，与 002 主链路同值；校准器回写目标）'),
('predAlphaCrossMs',        '0.03',   '阈值', '横向 σ 增长率 α_cross 基准（0.03，与 002 主链路同值；校准器回写目标）'),
('predAlongSpeedBiasMs',    '0.0',    '阈值', '沿航道速度偏置（m/s，CALIBRATOR 回写目标，初值 0）');
