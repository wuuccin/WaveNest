-- =====================================================================
-- WaveNest 系统参数初始化 sys_param
-- 权威依据：37-M6-数据库 §5.14.1「初始建议值（登记口径）」
-- 灌入范围（键名 + 值均有权威出处的，不臆造）：
--   · A 组 68 项（camelCase 键名已登记）
--   · B 组 3 项（sepHorizM / sepVertM / confSampleStepSec）
--   · M6 容量/时长 2 项（nestCapacity / forcedStopSlotSec，2026-10-13 回执定稿）
--   · E 组 26 项（2026-10-16 补齐 camelCase 键名，见 37 §5.14.1；其中与 A 组同名的 4 项
--     rhoTh / vStopMs / mOfN / rotWindowFrames 已在 A 组段内，不重复灌）
-- 未灌入（取值未定，报组长后补）：
--   · C/D/F/G 组参数：83/82/85/86 只列名目、未给取值
--   · E 组 3 项待定：lenOverGroundM（按船型参数化）／e10HystExitRatio（换算口径待
--     拍板，见 53 §2 第 18 项）／e10RobustWindOffsetMs 与 e10AlphaFailProb 对应的
--     ②鲁棒裕度档、①机会约束档尚无上游风况分布可依，只灌 ③ 固定系数档
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

-- ---------------- E 组（重规划与返航兜底，84 §5.14.1，26 项；4 项与 A 组同名不重复灌） ----------------
-- 说明：与 A 组同名的 rhoTh / vStopMs / mOfN / rotWindowFrames 已在上面 A 组段内灌入，此处不重复。
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('thetaThDeg',           '20',     '阈值', 'E 改向累计航向偏量 Θ_th（84 §2.9；6b 判据阈值）'),
('chiSqGate',            '5.99',   '阈值', 'E 卡方门限（2 维 95%；84 §2.9；须加标定因子 c 后判）'),
('sigmaScaleK',          '2.0',    '阈值', 'E σ 缩放系数 k（84 §2.9；正态约 95% 覆盖）'),
('aciCoverage',          '0.90',   '阈值', 'E ACI 覆盖率目标（84 §2.9）'),
('cusumK',               '0.5',    '阈值', 'E CUSUM 参考裕量 k＝0.5σ（84 §2.9）'),
('cusumH',               '4.0',    '阈值', 'E CUSUM 门限 h＝4σ（84 §2.9）'),
('hystEnterRatio',       '1.0',    '阈值', 'E 迟滞进入双门限（判据线倍数；84 §2.9）'),
('hystExitRatio',        '0.6',    '阈值', 'E 迟滞退出双门限（判据线倍数；84 §2.9；防乒乓）'),
('ewmaLambda',           '0.2',    '阈值', 'E EWMA 平滑系数 λ（84 §2.9）'),
('deadEnterRatio',       '1.0',    '阈值', 'E 死区进入阈值（判据线倍数；84 §3.8）'),
('deadExitRatio',        '0.6',    '阈值', 'E 死区退出阈值（判据线倍数；84 §3.8）'),
('tierWarnRatio',        '1.5',    '阈值', 'E 分层带警戒边界（判据线倍数；84 §3.8）'),
('tierExceedRatio',      '2.5',    '阈值', 'E 分层带超限边界（判据线倍数；84 §3.8）'),
('minTriggerIntervalSec','60',     '窗口', 'E 最小触发间隔（秒；84 §3.8；周期变则同步变）'),
('cooldownSec',          '120',    '窗口', 'E 冷却期时长（秒；84 §3.8；重调度后冷却）'),
('socMarginPct',         '20',     '阈值', 'E 电量安全裕度（百分点；84 §3.8；影响范围判定）'),
('timeMarginRatio',      '0.20',   '阈值', 'E 时间裕度（占预计返程耗时比例；84 §3.8）'),
('maxRelaxCount',        '2',      '容量', 'E 松弛上限条数（84 §3.8；最大放开约束数）'),
('e10ReserveRatio',      '0.20',   '阈值', 'E 功能10 储备裕度 E_reserve（84 §4.3；比值，注入前 ×100 换算百分点）'),
('e10MarginK',           '1.2',    '阈值', 'E 功能10 固定裕度系数 k（84 §2.3 ③；仅无风况快照时启用）'),
('e10SlopeMaxDeg',       '15',     '阈值', 'E 功能10 坡度否决阈值（度；84 §4.3；L4 未接，暂无消费方）');

-- ---------------- 未灌入的 E 组 3 项（取值未定，不臆造） ----------------
--   lenOverGroundM      ：停车位移阈值（一船长），值为「按船型参数化」，无具体米数
--   e10HystExitRatio    ：第一级边界迟滞退出裕度；1.2 若直读会使退出比进入更保守，与迟滞语义
--                        矛盾，换算口径待拍板（53 §2 第 18 项）
--   e10RobustWindOffsetMs / e10AlphaFailProb
--                       ：分别对应 84 §2.3 ② 鲁棒裕度档与 ① 机会约束档，须上游给风况
--                        分布／范围表达才可判档；首版只实现 ③ 固定系数档（e10MarginK）

-- ---------------- 10-16 序4 灌库（档D + 候选生成参数；张睿哲） ----------------
-- 档 D：全项目低电量阈值（2026-10-18 口径修正：统一 lowBatteryTrigger=20）
-- 候选生成过滤参数（CandidateGenerator 注入；取值为演示默认，待标定）
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('lowBatteryTrigger',   '20',     '阈值', '统一低电量阈值（%；G 组告警及 E 功能10 A5 共用；2026-10-18 确认）'),
('minBatteryPercent',   '30',     '阈值', '候选无人机最低电量门槛（%）'),
('maxNestDistanceKm',   '5',      '阈值', '机巢到取货点最大半径（km）');

-- ---------------- G 组检测阈值（16 键，来源 SysParamBridge／86；值=代码现有默认，2026-10-18 灌） ----------------
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('offtrackWarningThreshold',     '30.0', '阈值', '偏航预警阈值（米）'),
('offtrackCriticalThreshold',    '50.0', '阈值', '偏航严重阈值（米）'),
('offtrackDebounceCount',        '3',    '阈值', '偏航触发去抖点数'),
('offtrackClearCount',           '5',    '阈值', '偏航解除去抖点数'),
('offtrackDowngradeCount',       '3',    '阈值', '偏航严重→预警降级去抖'),
('lowBatteryClear',              '30.0', '阈值', '低电量清除阈值（%）'),
('lowBatterySevere',             '10.0', '阈值', '低电量严重阈值（%）'),
('lowBatteryDowngradeDebounce',  '3',    '阈值', '低电量降级去抖点数'),
('heartbeatLostWindowSec',       '30',   '窗口', '失联判定窗口（秒）'),
('heartbeatLossWindowSec',       '120',  '窗口', '灭失判定窗口（秒）'),
('heartbeatCheckIntervalSec',    '5',    '窗口', '检测轮询间隔（秒）'),
('weatherWindWarningThreshold',  '8.0',  '阈值', '风速预警阈值（m/s）'),
('weatherWindCriticalThreshold', '12.0', '阈值', '风速严重阈值（m/s）'),
('weatherWindSurgeThreshold',    '0.5',  '阈值', '风速突变阈值（(m/s)/s）'),
('weatherSurgeWindowSec',        '10',   '窗口', '突变检测窗口（秒）'),
('weatherClearDebounce',         '5',    '阈值', '清除防抖采样点数');
-- 注：2026-10-18 已决——lowBatteryTrigger(20) 为全项目唯一低电量键，G 组告警与 E 组功能 10
--     A5 兜底共用此键；原 E 组 e10LowBatteryAbs(15) 已删除，不再使用。

-- ---------------- PR #6 会面与风况（7 键，来源 DroneContextAdapter／RendezvousCoordinator；值=代码现有默认，2026-10-18 灌） ----------------
INSERT INTO `sys_param` (`param_key`, `param_value`, `category`, `description`) VALUES
('droneCruiseSpeedMs',           '12.0', '阈值', '巡航速度兜底（m/s，机型档案落地后作废）'),
('rendezvousHorizonSec',         '1800', '窗口', '会面求解时域（秒）'),
('rendezvousWindowMarginSec',    '120',  '窗口', '会面时间窗余量（秒）'),
('rendezvousEpsilonSec',         '1.0',  '阈值', '不动点迭代收敛阈值（秒）'),
('rendezvousMaxIter',            '50',   '阈值', '迭代最大次数'),
('windSpeedMs',                  '0.0',  '阈值', '代表风速兜底（无快照时）'),
('windDirDeg',                   '0.0',  '阈值', '代表风向兜底（度）');

-- TODO: 档 A 9 键——吴可欣已于 2026-10-16 补齐 `37` §5.14.1 的 E 组键名（见本文件 E 组段），
--       但**尚未按「档 A」形态灌入**，待 37 登记后灌
-- TODO: 档 B 9 键——待 37 登记后灌入
-- TODO: 档 C（L1/熔断）——暂不灌

-- ✅ 低电量阈值单一来源已决（2026-10-18，吴可欣裁决）：
--   全项目低电量阈值统一为 `lowBatteryTrigger`(20)，G 组告警与 E 组功能 10 A5 兜底共用此一键，
--   依据《84》§2.2／§4.3「低电量阈值 G-11 为全项目单一来源（首版静态、不按机型）」
--   与《86》引用口径「E 组 e10_low_battery_abs 引同一值」。
--   原 E 组段 `e10LowBatteryAbs`(15) 已于本次删除，不再使用；E 侧
--   `HomecomingParams.batteryAbsoluteFloorPct` 改由本键注入（见该类 javadoc）。
--   键名已为 camelCase，与 G 组 17 键风格一致。
-- =====================================================================
--   D 组功能 5/6/7/8 参数（83 §7）：w_time/w_energy/T_hold/batch_window 等——16 号接入时按需灌入
--   C/F/G 组参数（82/85/86）：只列名目未给取值
--   A 组 lenOverGroundM：值为「按船型参数化」，无具体数值
-- =====================================================================
