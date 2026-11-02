-- =====================================================================
-- WaveNest 预测误差样本表 pred_error_sample
--
-- 任务：11-02 序号一（卢肄文）——阶段二 5「回流校准」。预测与实测的配对残差
-- 此前只活在进程内（NeesCalibrator / PredictionBiasCalibrator 的累加器），
-- 重启即失、无法复核、无法离线复标。本表是这份样本的持久化载体，
-- 也是「跑一批 AIS 后表里有行」这条判据的落点。
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
--   err_course_deg ∈ [-180, 180]，取最短弧（同 PredictionService.shortestArcDeg 口径）
--
-- 为什么是 DOUBLE 而不是 DECIMAL：残差是浮点测量量，不参与金额类等值比较；
-- 且 DOUBLE 与算法层 double 直通，避免 BigDecimal 往返的舍入与装箱开销。
--
-- 执行方式（与 005/006 同一风格：新建幂等文件，不改 001_init.sql）：
--   ① 空数据卷：docker compose down -v && docker compose up -d（挂载自动执行）
--   ② 已有数据卷，手工灌：
--      docker cp deploy/sql/007_pred_error_sample.sql wavenest-mysql:/tmp/
--      docker exec wavenest-mysql mysql -uroot -pwavenest \
--          --default-character-set=utf8mb4 -e "source /tmp/007_pred_error_sample.sql"
--   验：SHOW COLUMNS FROM `pred_error_sample`;
--
-- 为什么用 CREATE TABLE IF NOT EXISTS 而不是 006 那种 PREPARE/EXECUTE 幂等补丁：
-- 006 是给既有表加列（MySQL 无 ADD COLUMN IF NOT EXISTS），只能走 information_schema
-- 判断后动态拼 SQL；本表是全新表，MySQL 原生支持幂等建表，重复执行无副作用。
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
    `sample_ts`      DATETIME(3) NOT NULL COMMENT '样本时刻（实测点时刻，毫秒精度），配对与分桶基准',
    `create_time`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时刻',
    PRIMARY KEY (`id`),
    KEY `idx_pes_mmsi_ts` (`ship_mmsi`, `sample_ts`),
    KEY `idx_pes_version` (`pred_version`),
    KEY `idx_pes_create` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测误差样本表（80 §5.1 NEES 回流校准的持久化载体；M2 唯一写入方）';

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
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('calCalibrateIntervalSec', '60',     '间隔', '每船回流校准的节流间隔（s）；0 或负值按缺省 60 处理'),
('predAlphaAlongMs',        '0.15',   '阈值', '纵向 σ 增长率 α_along 基准（80 附录 B 初值，校准器回写目标）'),
('predAlphaCrossMs',        '0.02',   '阈值', '横向 σ 增长率 α_cross 基准（80 附录 B 初值，校准器回写目标）'),
('predAlongSpeedBiasMs',    '0.0',    '阈值', '沿航道速度偏置（m/s，CALIBRATOR 回写目标，初值 0）');
