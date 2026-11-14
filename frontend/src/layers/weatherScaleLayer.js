import L from 'leaflet'
import { toMapLatLng } from '../map/coordinates'
import { windLevel } from '../api/weather'

/**
 * 风场图层（11-14 任务 2-7 · 杨添爻）。
 *
 * <p>把气象仓库里的网格点画到地图上：箭头指向风的去向、数字报风速、颜色按预警／熔断分档。
 * 为什么值得单独一层：后端按位置发多条（U-05 甲案），只在一处显示「当前风速」会掩盖
 * 「不同点位风不同」这件事，而候选判定恰恰是按位置取最不利。
 *
 * <p>只画传入的点，不在图层里造点、不补默认风；没有点时清空图层（不留上一次的残影）。
 */

const finitePoint = point => Number.isFinite(point?.lon) && Number.isFinite(point?.lat) && Number.isFinite(point?.windSpeedMs)

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[character]))

/**
 * 绘制风场网格点。
 *
 * @param {L.LayerGroup} group     目标图层组（调用方负责建组与挂到地图上）
 * @param {Array<object>} points   气象仓库的网格点，字段 lon／lat／windSpeedMs／windDirDeg／gustMs
 * @param {object} thresholds      已 resolve 的阈值（warningMs／criticalMs）
 * @param {object} [options]
 * @param {object} [options.worst] 最不利点，用于加一圈强调（不传则按风速最大值自行判定）
 * @returns {{drawn:number, skipped:number}} 画了几个、跳过几个（非法点不静默吞掉）
 */
export function drawWeatherScale(group, points, thresholds, options = {}) {
  group.clearLayers()
  const rows = (Array.isArray(points) ? points : []).filter(finitePoint)
  const skipped = (Array.isArray(points) ? points.length : 0) - rows.length
  if (!rows.length) return { drawn: 0, skipped }

  const worst = options.worst && finitePoint(options.worst)
    ? options.worst
    : rows.reduce((left, right) => (left.windSpeedMs >= right.windSpeedMs ? left : right))
  const worstKey = `${worst.lon.toFixed(5)},${worst.lat.toFixed(5)}`

  for (const point of rows) {
    const level = windLevel(point.windSpeedMs, thresholds)
    const dir = Number(point.windDirDeg)
    // 气象风向是「来向」，箭头画「去向」，故 +180°
    const arrowDeg = Number.isFinite(dir) ? (dir + 180) % 360 : 180
    const key = `${point.lon.toFixed(5)},${point.lat.toFixed(5)}`
    const marker = L.marker(toMapLatLng(point), {
      zIndexOffset: key === worstKey ? 480 : 300,
      icon: L.divIcon({
        className: `weather-pin ${level}${key === worstKey ? ' worst' : ''}`,
        html: `<span style="transform: rotate(${arrowDeg}deg)">↑</span><b>${point.windSpeedMs.toFixed(1)}</b>`,
        iconSize: [40, 40],
        iconAnchor: [20, 20]
      }),
      title: `风速 ${point.windSpeedMs.toFixed(1)} m/s`
    })
    const gust = Number(point.gustMs)
    const when = Number.isFinite(point.obsTs) ? new Date(point.obsTs).toLocaleTimeString('zh-CN', { hour12: false }) : '时刻未回传'
    marker.bindTooltip(
      `<strong>${point.windSpeedMs.toFixed(1)} m/s</strong>`
      + `${Number.isFinite(dir) ? ` · 来向 ${dir.toFixed(0)}°` : ''}`
      + `${Number.isFinite(gust) ? ` · 阵风 ${gust.toFixed(1)} m/s` : ''}`
      + `<br><small>${escapeHtml(point.lon.toFixed(5))}, ${escapeHtml(point.lat.toFixed(5))} · ${escapeHtml(when)}${key === worstKey ? ' · 最不利点' : ''}</small>`,
      { direction: 'top', className: 'weather-tip' }
    )
    marker.addTo(group)
  }
  return { drawn: rows.length, skipped }
}
