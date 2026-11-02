/**
 * 预测看台的接口契约（阶段二 1 · 预测看台 · 前端）。
 *
 * <h3>为什么单独一个文件</h3>
 * <p>今天的口径是「字段名以张睿哲 PR 描述里的契约为准，<b>不自行改字段名</b>」。
 * 但后端接口还没合入，前端不能因此停摆，也不能先在组件里散着写一堆字段名——
 * 那样等真实契约一到，改动会散落在面板、store、图层、mock 四处。
 *
 * <p>所以字段名<b>只在这个文件里出现一次</b>：
 * <ul>
 *   <li>{@link FIELDS}：后端 JSON 的键名。契约变，只改这里的字符串；</li>
 *   <li>{@link PATHS}：接口路径，同样待登记；</li>
 *   <li>{@link normalizeSummary} / {@link normalizeDetail}：唯一的解析入口，
 *       组件永远只看到归一后的 <b>驼峰视图对象</b>。</li>
 * </ul>
 * 面板、图层、store 一律不直接读后端原始字段。
 *
 * <h3>当前取值来自哪里</h3>
 * <p>后端 {@code PredictionSnapshot} 已合入 {@code develop}，它的 record 组件名即天然 JSON 名：
 * {@code mmsi / version / t0Ms / courseDeg / validitySec / premise / points}；
 * 点对象的 {@code ShipTrajPoint} 为 {@code t / lon / lat / sigmaAlong / sigmaCross}。
 * 因此这里的缺省值<b>不是臆造</b>，而是照现有 Java record 取的（Jackson 按组件名序列化）。
 * 张睿哲若是另起 DTO／改分页包装，只需改 {@link FIELDS} 与 {@link normalizeSummary}。
 *
 * <p><b>契约定案（张睿哲 11-02 群通报，2026-11-02）</b>：
 * <ol>
 *   <li>路径 {@code GET /api/prediction/ships} 与 {@code GET /api/prediction/ships/{mmsi}}；</li>
 *   <li>列表 {@code {ships:[{mmsi,version,premise,courseDeg,pointCount,validityRemainingSec}], total, page, size}}
 *       ——摘要<b>不带 t0Ms/validitySec/点列</b>，点数与剩余都是后端算好直给；</li>
 *   <li>单船 {@code {mmsi, version, t0Ms, courseDeg, validitySec, premise, points[]}}，points 与 WS 帧 ship_traj 同源；</li>
 *   <li>分页 page 从 1 起、size 默认 50 上限 200；单船不存在 → 404，空库列表 → 200+total=0。</li>
 * </ol>
 * 归一化仍兼容：摘要行缺 t0Ms/validitySec 时前端不再自算剩余（后端直给优先），
 * 点列只在单船接口取——列表摘要的点数用 {@code pointCount} 回显，不臆造。
 */

/** 后端 JSON 键名——<b>改这里，不改别处</b>。 */
export const FIELDS = {
  /** 列表页顶层包装键（待张睿哲确认，见文件头「待对齐项 1」） */
  list: 'ships',
  /** 列表页兼容包装键：契约若改用 items 也认 */
  listAlt: ['ships', 'items', 'predictions'],
  mmsi: 'mmsi',
  version: 'version',
  t0Ms: 't0Ms',
  courseDeg: 'courseDeg',
  validitySec: 'validitySec',
  premise: 'premise',
  points: 'points',
  /** 有效期剩余（秒）——张睿哲定的是后端直给，键名 validityRemainingSec */
  remainingSec: 'validityRemainingSec',
  /** 预测点数——列表摘要只有计数、不带点列，用这个键回显 */
  pointCount: 'pointCount',
  /** 精算版附加字段（今天可能为 null，前端只做展示，缺了不报错） */
  calibrationFactor: 'calibrationFactor',
  deviationM: 'deviationM',
  pointT: 't',
  pointLon: 'lon',
  pointLat: 'lat',
  pointSigmaAlong: 'sigmaAlong',
  pointSigmaCross: 'sigmaCross'
}

/** 接口路径——<b>待张睿哲 PR 描述确认</b>；未确认前不得在其他文件写死。 */
export const PATHS = {
  /** 在航船摘要列表（张睿哲 11-02 群通报定的路径，分页 page 从 1 起、size 默认 50 上限 200） */
  list: '/api/prediction/ships',
  /** 单船完整点列 */
  detail: mmsi => `/api/prediction/ships/${encodeURIComponent(mmsi)}`
}

/** {@code premise} 的合法取值（A 组 {@code PremiseStatus.code()}）；其余一律视为异常值。 */
export const PREMISE_VALUES = ['VALID', 'INVALID', 'UNKNOWN']

/** 前提状态的中文说明；未知值原样回显，不吞。 */
export const PREMISE_TEXT = {
  VALID: '前提成立',
  INVALID: '前提失效',
  UNKNOWN: '前提未知'
}

/**
 * 前提状态的展示级别——面板据此上色，<b>判据「premise 非 VALID 有醒目标记」落在这一处</b>。
 *
 * <p>刻意区分 {@code invalid} 与 {@code unknown}：两者都不该显示成正常，
 * 但「前提被证伪」和「还没判过」的处置动作不同（前者要重锚，后者只是样本不够），
 * 合成一个黄色会让值班的人看不出该不该干预。无法识别的取值单列 {@code unexpected}，
 * ——因为那说明<b>契约被改坏了</b>，比 INVALID 更值得报警，不能默默当作 UNKNOWN 放过。
 *
 * @returns {'valid'|'invalid'|'unknown'|'unexpected'}
 */
export function premiseLevel(premise) {
  if (premise === 'VALID') return 'valid'
  if (premise === 'INVALID') return 'invalid'
  if (premise === 'UNKNOWN') return 'unknown'
  return 'unexpected'
}

/** 是否算「醒目标记」——非 VALID 一律要标（判据原文）。 */
export function isPremiseFlagged(premise) {
  return premise !== 'VALID'
}

const num = value => (Number.isFinite(Number(value)) && value !== null && value !== '' ? Number(value) : null)
const str = value => (typeof value === 'string' && value.trim() !== '' ? value.trim() : null)

/**
 * 单点归一：只保留有限且坐标合法的点。
 *
 * <p>为什么在这里就剔：{@code ShipTrajPoint} 的 σ 若为 {@code NaN}，
 * 下游椭圆半径会变成 {@code NaN}，Leaflet 画出来是<b>看不见</b>而不是报错——
 * 静默丢要素比报错更难查。宁可少画一个点，也要让「点数」这个指标诚实。
 */
function normalizePoint(raw) {
  if (!raw || typeof raw !== 'object') return null
  const lon = num(raw[FIELDS.pointLon])
  const lat = num(raw[FIELDS.pointLat])
  if (lon === null || lat === null) return null
  if (lon < -180 || lon > 180 || lat < -90 || lat > 90) return null
  return {
    t: num(raw[FIELDS.pointT]),
    lon,
    lat,
    sigmaAlong: num(raw[FIELDS.pointSigmaAlong]),
    sigmaCross: num(raw[FIELDS.pointSigmaCross])
  }
}

/** 点列归一 + 丢弃无效点，返回新数组（不改原对象）。 */
export function normalizePoints(rawList) {
  if (!Array.isArray(rawList)) return []
  return rawList.map(normalizePoint).filter(Boolean)
}

/**
 * 列表项归一。
 *
 * @param raw 后端返回的单条摘要（或完整快照——两者共用同一套字段名）
 * @param nowMs 当前时刻，用于自算有效期剩余
 * @returns 视图对象；<b>缺 {@code mmsi} 返回 {@code null}</b>（无主的船无法点选，进列表只会添乱）
 */
export function normalizeSummary(raw, nowMs = Date.now()) {
  if (!raw || typeof raw !== 'object') return null
  const mmsi = str(raw[FIELDS.mmsi])
  if (!mmsi) return null
  const version = num(raw[FIELDS.version])
  const t0Ms = num(raw[FIELDS.t0Ms])
  const validitySec = num(raw[FIELDS.validitySec])
  const points = normalizePoints(raw[FIELDS.points])
  const premise = str(raw[FIELDS.premise]) || 'UNKNOWN'
  return {
    mmsi,
    version,
    t0Ms,
    validitySec,
    courseDeg: num(raw[FIELDS.courseDeg]),
    premise,
    premiseLevel: premiseLevel(premise),
    flagged: isPremiseFlagged(premise),
    // 列表摘要不带点列、只带后端算好的 pointCount；单船/带点列时用真实点数
    pointCount: points.length || num(raw[FIELDS.pointCount]) || 0,
    points,
    remainingSec: remainingSeconds(raw, t0Ms, validitySec, nowMs),
    calibrationFactor: num(raw[FIELDS.calibrationFactor]),
    deviationM: num(raw[FIELDS.deviationM])
  }
}

/**
 * 有效期剩余（秒）。
 *
 * <p>优先级：后端直给 → 前端按 {@code t0Ms + validitySec - now} 自算 → {@code null}（无从判断）。
 * 自算结果<b>钳到 0 不为负</b>：过期就是 0 秒，负数和「还剩 -3 秒」一样没用，
 * 但 {@code null}（不知道）与 {@code 0}（确定过期）必须分开——前者不能当成过期把船剔掉。
 */
function remainingSeconds(raw, t0Ms, validitySec, nowMs) {
  const serverValue = num(raw[FIELDS.remainingSec])
  if (serverValue !== null) return Math.max(0, Math.round(serverValue))
  if (t0Ms === null || validitySec === null) return null
  return Math.max(0, Math.round((t0Ms + validitySec * 1000 - nowMs) / 1000))
}

/** 列表页归一：兼容裸数组与 {@code {ships:[...]}}／{@code {items:[...]}} 三种形状。 */
export function normalizeList(payload, nowMs = Date.now()) {
  const rows = Array.isArray(payload)
    ? payload
    : FIELDS.listAlt.map(key => payload?.[key]).find(Array.isArray) || []
  return rows.map(row => normalizeSummary(row, nowMs)).filter(Boolean)
}

/**
 * 单船点列归一。
 *
 * <p>接受两种形状：完整快照对象，或裸点列数组（契约若把点列单独返回也认）。
 * 返回值里的 {@code points} 永远是新数组——面板与图层共享引用时不会互相清空。
 */
export function normalizeDetail(payload, nowMs = Date.now()) {
  if (Array.isArray(payload)) {
    // 裸点列没有 mmsi：主键必须留空由调用方校对（store 会拿它跟 selectedMmsi 比），
    // 这里凭空造一个"有主"的对象，正好会让"只叠加选中船"那道关失效
    const points = normalizePoints(payload)
    return {
      mmsi: null,
      version: null,
      t0Ms: null,
      validitySec: null,
      courseDeg: null,
      premise: 'UNKNOWN',
      premiseLevel: 'unknown',
      flagged: true,
      pointCount: points.length,
      points,
      remainingSec: null,
      calibrationFactor: null,
      deviationM: null
    }
  }
  const summary = normalizeSummary(payload, nowMs)
  if (!summary) return null
  return summary
}

/** 秒数 → 人读文案；{@code null} 表示无从判断，不要显示「0 秒」。 */
export function remainingText(seconds) {
  if (seconds === null) return '—'
  if (seconds <= 0) return '已过期'
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}
