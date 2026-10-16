-- =====================================================================
-- WaveNest 系统参数初始化 sys_param
-- 权威依据：37-M6-数据库 §5.14.1「初始建议值（登记口径）」
-- 灌入范围（键名 + 值均有权威出处的，不臆造）：
--   · A 组 68 项（camelCase 键名已登记）
--   · B 组 3 项（sepHorizM / sepVertM / confSampleStepSec）
--   · M6 容量/时长 2 项（nestCapacity / forcedStopSlotSec，2026-10-13 回执定稿）
-- 未灌入（键名未定，报组长后补）：
--   · E 组 26 项：仅中文名目，无 camelCase 键名
--   · C/D/F/G 组参数：83/82/85/86 只列名目、未给取值
--   · A 组 lenOverGroundM：值为「按船型参数化」，无具体数值
-- =====================================================================

USE `wavenest`;

-- ---------------- A 组（船舶状态估计与轨迹预测，80 §5.14.1，共 68 项） ----------------
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('ttlAisMs',            '120000', '窗口', '订单数据新鲜度 TTL'),
('ttlLinkMs',           '60000',  '窗口', '链路新鲜度'),
('suspendRetrySec',     '60',     '窗口', '暂缓重试周期'),
('maxSuspendSec',       '1800',   '窗口', '最长暂缓时长（触顶转人工）'),
('dupWindowSec',        '600',    '窗口', '去重窗口'),
('weightTolKg',         '5',      '阈值', '重量容差'),
('maxPayloadMargin',    '0.05',   '阈值', '载重上界裕度'),
('tHandoverMinSec',     '120',    '窗口', '交接时长下界'),
('vMaxCruiseMs',        '20',     '阈值', '最大巡航速度'),
('wakeMarginSec',       '120',    '窗口', '提前唤醒余量'),
('dupAisWindowSec',     '60',     '窗口', 'AIS 去重窗口'),
('resampleStepSec',     '10',     '窗口', '重采样步长'),
('vMaxCraftMs',         '6.0',    '阈值', '船速上界'),
('vGateMs',             '9.0',    '阈值', '位移速度门限'),
('dcogGateDeg',         '60',     '阈值', '航向突变门限'),
('vTurnMinMs',          '0.5',    '阈值', '转向判定速度下限'),
('epsSogMs',            '2.0',    '阈值', '速度自洽容差'),
('confirmCount',        '3',      '阈值', '可疑点确认数'),
('pendingMax',          '5',      '阈值', '待定点上限'),
('epsPairM',            '30',     '阈值', '点间自洽残差'),
('gapFactor',           '3',      '阈值', '丢包判定倍数'),
('gapMaxSec',           '90',     '窗口', '最大缺失时长'),
('q',                   '0.05',   '阈值', '过程噪声谱密度'),
('sigmaPosM',           '20',     '阈值', 'AIS 位置误差'),
('thetaMaxDeg',         '75',     '阈值', '观测夹角截断'),
('tauESec',             '120',    '阈值', '横向回复时间常数'),
('sigmaEM',             '15',     '阈值', '横向稳态标准差'),
('lcStepM',             '5',      '阈值', '换道步进门限'),
('lcCount',             '3',      '阈值', '换道确认数'),
('lcInflate',           '2.0',    '阈值', '机动期带宽膨胀'),
('alphaAlongMs',        '0.05',   '阈值', '纵向模型误差率'),
('alphaCrossMs',        '0.03',   '阈值', '横向模型误差率'),
('lTolM',               '150',    '阈值', '纵向容忍上限'),
('wTolM',               '40',     '阈值', '横向容忍上限'),
('laneHalfWidthM',      '50',     '阈值', '航道半宽'),
('residentIntervalSec', '10',     '窗口', '常驻刷新周期'),
('horizonResidentSec',  '1800',   '窗口', '常驻时域'),
('horizonSec',          '1800',   '窗口', '精算时域'),
('staleSec',            '30',     '窗口', '快照过旧阈值'),
('minRecalcSec',        '5',      '窗口', '最小重算间隔'),
('ttlAliveSec',         '300',    '窗口', '在航存活时长'),
('psiThrDeg',           '20',     '阈值', 'P1 偏航角阈值（须与 M3 侧纠偏同值）'),
('psiHysDeg',           '5',      '阈值', 'P1 滞后带'),
('tPsiSec',             '120',    '窗口', 'P1 驻留时长'),
('confirmWindow',       '5',      '窗口', '归一化偏差序列窗长'),
('vStopMs',             '0.5',    '阈值', 'P2 停船速度阈值'),
('vHysMs',              '0.2',    '阈值', 'P2 滞后带'),
('tStopSec',            '180',    '窗口', 'P2 驻留时长'),
('rhoTh',               '0.25',   '阈值', '速度缺口比阈值'),
('rotWindowFrames',     '3',      '窗口', 'ROT 派生窗长'),
('sigmaCogDeg',         '8',      '阈值', '航向差归一化尺度'),
('bpInM',               '300',    '阈值', 'P3 分汊邻域（前）'),
('bpOutM',              '300',    '阈值', 'P3 分汊邻域（后）'),
('dpsiMarginDeg',       '10',     '阈值', 'P3 分支角差余量'),
('tBpSec',              '60',     '窗口', 'P3 驻留时长'),
('tRaSec',              '60',     '窗口', 'P4 受限水域驻留'),
('dsWinMarginM',        '100',    '阈值', '投影搜索窗口余量'),
('calWindowN',          '200',    '阈值', '校准窗口样本数'),
('calDepth',            '20',     '阈值', '校准深度'),
('calTimeBucketSec',    '60',     '窗口', '校准时域分桶'),
('calMinSamples',       '30',     '阈值', '层内最少样本'),
('calPersistM',         '5',      '阈值', '校准持续周期数'),
('kBias',               '1.0',    '阈值', '偏置判定系数'),
('kBand',               '1.5',    '阈值', '带宽过窄系数'),
('kTight',              '0.7',    '阈值', '带宽过保守系数'),
('maxAdjustRatio',      '0.10',   '阈值', '单次最大调整比例'),
('maxVBiasMs',          '0.5',    '阈值', '速度偏置上限'),
('mOfN',                '3/5',    '阈值', '前提粗标记的 M/N');

-- ---------------- B 组（会面/航迹，81 §5.14.1，共 3 项） ----------------
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('sepHorizM',           '50',     '间隔', '水平安全间隔'),
('sepVertM',            '15',     '间隔', '垂直安全间隔'),
('confSampleStepSec',   '1',      '窗口', '冲突时间采样步长');

-- ---------------- M6 容量/时长（2026-10-13 回执定稿） ----------------
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('nestCapacity',        '4',      '容量', '机巢容量 C（首版不分机型档；nest 表 capacity 初始化源，nest 表为权威）'),
('forcedStopSlotSec',   '900',    '容量', '迫停标称时长 d（秒；占位时隙标称长度）');

-- ---------------- 10-16 序4 灌库（档D + 候选生成参数） ----------------
-- 档 D：low_battery_trigger 占位（低电量告警触发阈值，百分比；正式取值待标定）
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('low_battery_trigger', '20',     '阈值', '低电量告警触发阈值（百分比；占位值，正式标定后改）'),
-- 候选生成过滤参数（CandidateGenerator 注入；取值为演示默认，待标定）
('minBatteryPercent',   '30',     '阈值', '候选无人机最低电量门槛（%）'),
('maxNestDistanceKm',   '5',      '阈值', '机巢到取货点最大半径（km）');

-- TODO: 档 A 9 键——待吴可欣 37 §5.14.1 补齐清单后灌入（键名/取值未给，不臆造）
-- TODO: 档 B 9 键——待 37 登记后灌入
-- TODO: 档 C（L1/熔断）——暂不灌

-- =====================================================================
-- 以下键名/取值未定，暂不灌入，报组长后补：
--   E 组 26 项（84）：改向累计航向偏量、减速速度缺口比、停车 SOG 阈值等——仅中文名目
--   D 组功能 5/6/7/8 参数（83 §7）：w_time/w_energy/T_hold/batch_window 等——16 号接入时按需灌入
--   C/F/G 组参数（82/85/86）：只列名目未给取值
--   A 组 lenOverGroundM：值为「按船型参数化」，无具体数值
-- =====================================================================
