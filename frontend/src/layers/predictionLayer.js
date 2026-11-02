import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates.js'
import { premiseLevel } from '../api/prediction.js'

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])

/**
 * 置信椭圆顶点——与 {@code shipLayer.js} 同一套几何，但<b>参数不同</b>：
 * 那里传的是「半轴 = 2σ」，这里同样乘 2（契约 {@code semiMajorM() = 2·sigmaAlong}）。
 *
 * <p>为什么不复用 {@code shipLayer} 里的那个函数：它是模块私有的，导出来就变成
 * 「两个模块共用一个未命名契约」。真要合并，应提到 {@code map/} 下作为公共实现——
 * 那属于改别人文件的接口，今天不做，已写进 PR 的待办。
 */
function ellipsePoints(lon, lat, along, cross, course) {
  const angle = course * Math.PI / 180
  const points = []
  for (let i = 0; i <= 64; i++) {
    const t = i * Math.PI * 2 / 64
    const forward = along * Math.cos(t)
    const right = cross * Math.sin(t)
    const east = forward * Math.sin(angle) + right * Math.cos(angle)
    const north = forward * Math.cos(angle) - right * Math.sin(angle)
    const point = systemMapPoint({
      lat: lat + north / 111132,
      lon: lon + east / (111320 * Math.cos(lat * Math.PI / 180))
    })
    points.push([point.lat, point.lon])
  }
  return points
}

/** 前提状态 → 配色。{@code unexpected} 刻意用最刺眼的红色：契约被改坏比前提失效更严重。 */
const STYLE = {
  valid: { line: '#0f9f8c', fill: '#f2bd62', marker: '#0f9f8c', label: '前提成立' },
  invalid: { line: '#bd4f55', fill: '#bd4f55', marker: '#bd4f55', label: '前提失效' },
  unknown: { line: '#c98736', fill: '#c98736', marker: '#c98736', label: '前提未知' },
  unexpected: { line: '#7c2d3a', fill: '#7c2d3a', marker: '#7c2d3a', label: '前提取值异常' }
}

/**
 * 在 {@code group} 上画<b>一条船的预测点列</b>，并清掉该组上一轮的内容。
 *
 * <p>画三样（顺序即图层叠放顺序）：
 * <ol>
 *   <li>预测折线——虚线，与 {@code shipLayer} 的实时预测同风格，避免两条线混淆；</li>
 *   <li>起点、终点各一个置信椭圆（中间点不画：一屏十几个椭圆会把航道糊住，
 *       要看中段σ可悬停折线）；</li>
 *   <li>终点标记，带 MMSI 与前提状态——<b>地图上也能看出前提非 VALID</b>，
 *       不必回头看面板。</li>
 * </ol>
 *
 * @param {object} group  Leaflet 图层组（由 MapView 创建并持有）
 * @param {object|null} detail 归一后的点列对象（见 api/prediction.js）
 * @param {{fit?: boolean}} options {@code fit=true} 时把视野移到该点列（点选瞬间用一次）
 * @returns {boolean} 是否真的画了东西（面板据此判断叠加是否生效）
 */
export function drawPredictionTrack(group, detail, options = {}) {
  if (!group) return false
  group.clearLayers()
  const points = (detail?.points || []).filter(point =>
    Number.isFinite(point?.lon) && Number.isFinite(point?.lat))
  if (!points.length) return false

  const level = premiseLevel(detail.premise)
  const style = STYLE[level] || STYLE.unexpected
  const course = Number.isFinite(detail.courseDeg) ? detail.courseDeg : 0
  const latLngs = points.map(point => {
    const mapped = systemMapPoint(point)
    return [mapped.lat, mapped.lon]
  })

  if (latLngs.length > 1) {
    // 白色描边垫底：底图是卫片时，纯色虚线压在深色江面上会看不清
    L.polyline(latLngs, { color: '#ffffff', weight: 9, opacity: .8, interactive: false }).addTo(group)
    L.polyline(latLngs, {
      className: `prediction-track prediction-track-${level}`,
      color: style.line,
      weight: 5,
      opacity: .95,
      dashArray: level === 'valid' ? '11 8' : '4 6'
    }).addTo(group)
  }

  for (const [sequence, index] of [0, points.length - 1].entries()) {
    const point = points[index]
    if (!Number.isFinite(point.sigmaAlong) || !Number.isFinite(point.sigmaCross)) continue
    const polygon = L.polygon(
      ellipsePoints(point.lon, point.lat, 2 * point.sigmaAlong, 2 * point.sigmaCross, course), {
        className: `prediction-ellipse prediction-ellipse-${sequence === 0 ? 'start' : 'end'}`,
        color: style.fill, weight: 2, dashArray: '5 5',
        fillColor: style.fill, fillOpacity: sequence === 0 ? .06 : .14
      }).addTo(group)
    const horizon = Number.isFinite(point.t) && Number.isFinite(detail.t0Ms)
      ? Math.max(0, Math.round((point.t - detail.t0Ms) / 1000))
      : null
    polygon.bindTooltip(
      `${sequence === 0 ? '起点' : '终点'}${horizon === null ? '' : ` · +${horizon} 秒`}`
      + ` · 95% 半轴 ${Math.round(2 * point.sigmaAlong)} × ${Math.round(2 * point.sigmaCross)} m`)
  }

  const last = latLngs.at(-1)
  const label = `${escapeHtml(detail.mmsi)} · ${escapeHtml(style.label)}`
  L.marker(last, {
    interactive: true,
    // 非 VALID 的标记抬高层级：同一水域多条预测叠在一起时，异常船必须压在最上面
    zIndexOffset: level === 'valid' ? 400 : 900,
    icon: L.divIcon({
      className: `prediction-end-pin prediction-end-pin-${level}`,
      html: `<span>预测终点</span><b>${label}</b>`,
      iconSize: [168, 34],
      iconAnchor: [84, 40]
    })
  }).addTo(group)

  if (options.fit) fitToPoints(group, latLngs)
  return true
}

/**
 * 视野移到点列上。
 *
 * <p>为什么不直接 {@code map.fitBounds}：这里拿不到 map 实例（只拿得到 group），
 * 用 {@code group.getBounds()} 反取，避免把 map 传进来多一条耦合。
 * 单点（只有 1 个预测点）时 {@code getBounds} 是退化的，直接不抢视野——
 * 为了一个点把整段航道缩没，比不动更糟。
 */
function fitToPoints(group, latLngs) {
  if (latLngs.length < 2) return
  const map = group?._map
  if (!map) return
  const bounds = L.latLngBounds(latLngs).pad(.35)
  if (!bounds.isValid()) return
  map.fitBounds(bounds, { padding: [30, 30], maxZoom: 15, animate: true })
}

/** 清空叠加（取消选中、切换数据源时调用）。 */
export function clearPredictionTrack(group) {
  group?.clearLayers()
}
