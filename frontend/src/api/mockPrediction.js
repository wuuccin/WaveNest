/**
 * 预测看台的离线样例——<b>与真实接口同一套字段名</b>（键名从 {@link FIELDS} 取，不另写一遍）。
 *
 * <h3>为什么 mock 也要走 normalize</h3>
 * <p>任务要求「接口未就绪用同结构 mock」。如果 mock 直接返回<b>已经归一好的</b>视图对象，
 * 那么面板、图层、排序、有效性剩余全部<b>没有经过真实解析路径</b>——
 * 接口一上，坏数据（NaN 的 σ、缺失的 premise、空点列）会从没人走过的那条路冲进来。
 *
 * <p>所以 mock 造的是<b>后端形状的原始 JSON</b>，和 {@code api.prediction.list()} 的返回值
 * 走同一个 {@code normalizeList}。这样联调那天只需要把数据源换掉，解析链一行不改。
 *
 * <h3>三条样例各自负责一个判据</h3>
 * <ul>
 *   <li>{@code 413700001} 正常船——{@code VALID}，点列完整，用来验「点选后地图叠加」；</li>
 *   <li>{@code 413700002} 掉头船——{@code INVALID}，用来验「非 VALID 有醒目标记」；</li>
 *   <li>{@code 413700004} 新入网船——{@code UNKNOWN} 且点列只有 2 个点，
 *       用来验「前提未知也要标」和「点数少的船照常列出」；</li>
 *   <li>{@code 413700003} <b>故意留的坑</b>：{@code premise} 是契约外的乱值 {@code "PENDING"}，
 *       用来验「无法识别的取值不会被悄悄当成 UNKNOWN 放过」；</li>
 *   <li>{@code 413700005} 已过期船——剩余 0 秒，用来验「过期不等于剔除」。</li>
 * </ul>
 * 缺 {@code mmsi} 的一条会被 {@code normalizeSummary} 丢掉——那不是缺陷，
 * 而是「无主的船点了也不知道点的是谁」，不该进列表。
 */

import { FIELDS, PREMISE_VALUES } from './prediction.js'
import { NANCHANG_SEGMENT } from './mockSegment.js'

/** 把视图字段写回后端键名，mock 数据里不出现第二次字面量键名。 */
const row = (fields, points) => ({
  [FIELDS.mmsi]: fields.mmsi,
  [FIELDS.version]: fields.version,
  [FIELDS.t0Ms]: fields.t0Ms,
  [FIELDS.courseDeg]: fields.courseDeg,
  [FIELDS.validitySec]: fields.validitySec,
  [FIELDS.premise]: fields.premise,
  [FIELDS.calibrationFactor]: fields.calibrationFactor ?? null,
  [FIELDS.deviationM]: fields.deviationM ?? null,
  [FIELDS.points]: points.map(point => ({
    [FIELDS.pointT]: point.t,
    [FIELDS.pointLon]: point.lon,
    [FIELDS.pointLat]: point.lat,
    [FIELDS.pointSigmaAlong]: point.sigmaAlong,
    [FIELDS.pointSigmaCross]: point.sigmaCross
  }))
})

/**
 * 沿航向推一段直线点列（只在样例里用；真实预测由后端算）。
 *
 * @param courseDeg 航向角，度（0=正北，90=正东）
 * @param stepSec   点间隔，秒
 * @param speedMs   航速，m/s
 * @param sigma0    起始标准差，米（沿 σ 随时间线性放大，横向固定放大）
 */
function straightTrack(lon, lat, courseDeg, count, stepSec, speedMs, sigma0) {
  const rad = (courseDeg * Math.PI) / 180
  const dEastPerSec = speedMs * Math.sin(rad)
  const dNorthPerSec = speedMs * Math.cos(rad)
  const cosLat = Math.cos((lat * Math.PI) / 180)
  const t0 = 1_767_000_000_000 // 固定基准，样例可复现；真实数据由后端给 t0Ms
  return Array.from({ length: count }, (_, index) => {
    const elapsed = index * stepSec
    return {
      t: t0 + elapsed * 1000,
      lon: lon + (dEastPerSec * elapsed) / (111_320 * cosLat),
      lat: lat + (dNorthPerSec * elapsed) / 111_132,
      sigmaAlong: Math.round(sigma0 + elapsed * 0.35),
      sigmaCross: Math.round(sigma0 * 0.45 + elapsed * 0.08)
    }
  })
}

export function mockPredictionList(nowMs = Date.now()) {
  const start = NANCHANG_SEGMENT.midpoint
  const t0 = nowMs
  const make = (mmsi, courseDeg, premise, count, stepSec, speedMs, sigma0, version, validitySec = 600) =>
    row({ mmsi, version, t0Ms: t0, courseDeg, validitySec, premise },
      straightTrack(start.lon, start.lat, courseDeg, count, stepSec, speedMs, sigma0))

  return {
    [FIELDS.list]: [
      make('413700001', 28, 'VALID', 12, 20, 4.2, 18, 7),
      make('413700002', 205, 'INVALID', 12, 20, 3.6, 22, 4),
      make('413700004', 62, 'UNKNOWN', 2, 20, 2.8, 30, 1),
      // 契约外的取值：验「不认识的 premise 要显眼，不许当 UNKNOWN 吞掉」
      make('413700003', 0, 'PENDING', 6, 20, 3.0, 25, 2),
      // 有效期 600 s，但 t0 往前挪 660 s ⇒ 已经过期（剩余 0 秒）。
      // 特意让「有效期」与「剩余」在视觉上分开，验「过期不等于剔除」，
      // 也验「剩余」不是照抄 validitySec
      expiredEntry(make)
    ]
  }
}

function expiredEntry(make) {
  const item = make('413700005', 15, 'VALID', 8, 20, 3.3, 20, 9, 600)
  item[FIELDS.t0Ms] -= 660_000
  return item
}

export function mockPredictionDetail(mmsi, nowMs = Date.now()) {
  const list = mockPredictionList(nowMs)[FIELDS.list]
  return list.find(item => item[FIELDS.mmsi] === mmsi) || null
}

/** 自检：样例里的 premise 是否都在契约取值内——扩散到别处时能第一时间发现样例写错。 */
export const MOCK_PREMISES = ['VALID', 'INVALID', 'UNKNOWN', 'PENDING']
export const PREMISE_CONTRACT_COVERS_MOCK = MOCK_PREMISES.every(value =>
  value === 'PENDING' || PREMISE_VALUES.includes(value))
