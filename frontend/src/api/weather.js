/**
 * 气象注入与风况标尺的接口封装 / 纯函数（11-14 任务 2-7 · 杨添爻）。
 *
 * <p><b>三条线从哪来（评审会问的唯一问题）</b>：
 * <ul>
 *   <li>当前风速：{@code data.weather} 帧，经 {@code stores} 的气象仓库聚合（按位置取最不利风况，
 *       与 M2 侧「取最不利」口径一致），组件不自己算、不自己造数；</li>
 *   <li>机型抗风上限：{@code GET /api/drones} 的 {@code windLimitMs}（{@code FleetController.DroneRow}），
 *       按当前任务机号取，取不到时回落「全机队最保守」；{@code null} 表示机型能力未登记，
 *       不造默认值（B1 口径），标尺上该线不画；</li>
 *   <li>全域熔断阈值：{@code sys_param} 的 {@code weatherWindWarningThreshold} = 8.0 与
 *       {@code weatherWindCriticalThreshold} = 14.0（{@code deploy/sql/002_sys_param.sql:155-156}）。
 *       后端当前只把这些值喂给 M4 检测器，没有对外的参数查询端点，所以这里以契约常量兜底，
 *       并允许调用方用 {@code thresholds} 覆盖——<b>数值只此一处</b>，组件模板里不出现字面量。</li>
 * </ul>
 *
 * <p><b>为什么标尺画 0–16 m/s</b>：跨度覆盖「基准 5 m/s → 预警 8 → 机型上限 10／12 → 熔断 14」，
 * 留 2 m/s 余量让 14 以上的指针仍可见；上限本身可由 {@code maxMs} 覆盖，不写死在模板里。
 */

/** 全域阈值默认值：与 `002_sys_param.sql:155-156` 同值，键名即出处。 */
export const WEATHER_THRESHOLDS = Object.freeze({
  warningMs: 8.0,
  criticalMs: 14.0,
  source: '002_sys_param.sql:155-156（weatherWindWarningThreshold / weatherWindCriticalThreshold）'
})

/** 标尺默认满量程（m/s）。 */
export const SCALE_MAX_MS = 16

/**
 * 严格数值转换：`null`／`undefined`／空串／布尔一律视为「没有数」，返回 `NaN`。
 *
 * <p>不能直接用 `Number()`——`Number(null)` 是 0，会让「没有气象帧」在标尺上显示成 0 m/s 的
 * 正常风速，把「缺数据」粉饰成「风平浪静」，这是本批最容易犯的错。
 */
export function toNumber(value) {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean') return Number.NaN
  return Number(value)
}

/**
 * 归一阈值并对非法组合报错。
 *
 * <p>要求 {@code 0 < warningMs < criticalMs}：两者写反或相等会让「超限」判定失去意义，
 * 与其在界面上画一条重叠的线，不如在建模型时就抛出来——这是契约量，不是可选装饰。
 */
export function resolveThresholds(overrides = {}) {
  const warningMs = toNumber(overrides.warningMs ?? WEATHER_THRESHOLDS.warningMs)
  const criticalMs = toNumber(overrides.criticalMs ?? WEATHER_THRESHOLDS.criticalMs)
  if (!Number.isFinite(warningMs) || !Number.isFinite(criticalMs) || !(warningMs > 0) || !(criticalMs > warningMs)) {
    throw new Error(`气象阈值非法：警告 ${warningMs}、严重 ${criticalMs}（要求 0 < 警告 < 严重）`)
  }
  return { warningMs, criticalMs, source: overrides.source ?? WEATHER_THRESHOLDS.source }
}

/**
 * 风速分级：`normal`／`warning`／`critical`；无数据或非法值返回 `unknown`。
 *
 * <p>阈值为闭区间下界（{@code >=}），与 M4 检测器「达到即报」的口径一致。
 */
export function windLevel(windMs, thresholds) {
  const value = toNumber(windMs)
  if (!Number.isFinite(value) || value < 0) return 'unknown'
  if (value >= thresholds.criticalMs) return 'critical'
  if (value >= thresholds.warningMs) return 'warning'
  return 'normal'
}

/** 把风速换算成标尺上的百分比位置（0–100，越界截断）；非法值返回 `null`（不画线）。 */
export function scalePercent(valueMs, maxMs = SCALE_MAX_MS) {
  const value = toNumber(valueMs)
  const max = toNumber(maxMs)
  if (!Number.isFinite(value) || !Number.isFinite(max) || !(max > 0) || value < 0) return null
  return Math.max(0, Math.min(100, (value / max) * 100))
}

/**
 * 从机队台账里挑出「机型抗风上限」这一条线要用的值。
 *
 * <p>给了 {@code droneId} 就取该机（当前任务机）；给不出或该机未登记，
 * 回落全机队最小值并标注「最保守」——演示时宁可提前报超限，也不拿最宽松的机型粉饰。
 *
 * @returns {{valueMs:number, droneId:string, model:string, scope:string}|null} 无可用数据时 `null`
 */
export function pickDroneWindLimit(items, droneId) {
  const rows = (Array.isArray(items) ? items : [])
    .map(item => ({ item, limit: toNumber(item?.windLimitMs) }))
    .filter(row => Number.isFinite(row.limit) && row.limit > 0)
  if (!rows.length) return null
  if (droneId) {
    const hit = rows.find(row => row.item.droneId === droneId)
    if (hit) return { valueMs: hit.limit, droneId: hit.item.droneId, model: hit.item.model ?? '', scope: '当前任务机型' }
  }
  const lowest = rows.reduce((left, right) => (left.limit <= right.limit ? left : right))
  return { valueMs: lowest.limit, droneId: lowest.item.droneId, model: lowest.item.model ?? '', scope: '全机队最保守' }
}

/**
 * 生成标尺模型——组件只负责渲染，判定与换算全在这里，便于单测。
 *
 * @param {object} input
 * @param {number} input.windMs      当前风速（最不利点），无数据传 `null`
 * @param {number} [input.gustMs]    当前阵风
 * @param {number} [input.windLimitMs] 机型抗风上限
 * @param {object} input.thresholds  已 resolve 的阈值
 * @param {number} [input.maxMs]     满量程
 */
export function buildScaleModel({ windMs, gustMs, windLimitMs, thresholds, maxMs = SCALE_MAX_MS }) {
  const wind = toNumber(windMs)
  const gust = toNumber(gustMs)
  const limit = toNumber(windLimitMs)
  const hasWind = Number.isFinite(wind) && wind >= 0
  const level = windLevel(wind, thresholds)
  return {
    maxMs,
    hasWind,
    level,
    wind: { valueMs: hasWind ? wind : null, percent: hasWind ? scalePercent(wind, maxMs) : null },
    gust: { valueMs: Number.isFinite(gust) && gust >= 0 ? gust : null, percent: Number.isFinite(gust) && gust >= 0 ? scalePercent(gust, maxMs) : null },
    droneLimit: {
      valueMs: Number.isFinite(limit) && limit > 0 ? limit : null,
      percent: Number.isFinite(limit) && limit > 0 ? scalePercent(limit, maxMs) : null
    },
    warning: { valueMs: thresholds.warningMs, percent: scalePercent(thresholds.warningMs, maxMs) },
    critical: { valueMs: thresholds.criticalMs, percent: scalePercent(thresholds.criticalMs, maxMs) },
    // 两档「超限」分开给：超机型上限是「这架机不能飞」，超熔断阈值是「全域停派」，处置不同
    overDroneLimit: hasWind && Number.isFinite(limit) && limit > 0 ? wind > limit : null,
    overWarning: hasWind ? wind >= thresholds.warningMs : false,
    overCritical: hasWind ? wind >= thresholds.criticalMs : false
  }
}

/**
 * 注入一片风：默认九点各一条（后端 {@code SimWeatherConnector} 的发布粒度纪律）。
 * 这里只做参数形状归一，真正的请求走 {@code api/client.js} 的 {@code injectWeather}。
 */
export function normalizeInjectInput({ windSpeedMs, windDirDeg = 135, gustMs = null, persist = true } = {}) {
  const wind = toNumber(windSpeedMs)
  if (!Number.isFinite(wind) || wind < 0) throw new Error('风速须为非负数（m/s）')
  const dir = toNumber(windDirDeg)
  if (!Number.isFinite(dir)) throw new Error('风向须为数值（度）')
  const gust = gustMs === null || gustMs === '' || gustMs === undefined ? null : toNumber(gustMs)
  if (gust !== null) {
    if (!Number.isFinite(gust) || gust < 0) throw new Error('阵风须为非负数（m/s）')
    // 后端 SimWeatherInjectTest 的口径：阵风不得低于风速
    if (gust < wind) throw new Error('阵风不得低于风速')
  }
  return { windSpeedMs: wind, windDirDeg: ((dir % 360) + 360) % 360, gustMs: gust, persist: Boolean(persist) }
}
