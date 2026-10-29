import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates'
const color = { '去程':'#3579b5', '末段交接':'#d3962f', '返程':'#319780', '去程中':'#3579b5', '末段交接中':'#d3962f', '返航中':'#319780', OUTBOUND:'#3579b5', HANDOVER:'#d3962f', RETURNING:'#319780' }
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])
function headingOf(state) {
  const direct = Number(state.headingDeg ?? state.heading ?? state.courseDeg ?? state.yaw)
  if (Number.isFinite(direct)) return direct
  const points = (state.segments || []).flatMap(segment => segment.points || []).filter(point => Number.isFinite(point?.lon) && Number.isFinite(point?.lat))
  const from = points.at(-2), to = points.at(-1)
  if (!from || !to) return 0
  const east = (to.lon - from.lon) * Math.cos((from.lat + to.lat) * Math.PI / 360)
  const north = to.lat - from.lat
  return Math.atan2(east, north) * 180 / Math.PI
}
export function drawDrone(map, group, state, orderId = '', debug = false) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  for (const segment of state.segments || []) {
    const points = (segment.points || []).filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
    if (points.length > 1) L.polyline(points.map(p => { const point = systemMapPoint(p); return [point.lat, point.lon] }), {color: color[segment.phase] || '#6f8792', weight:5, opacity:.93}).addTo(group)
  }
  const position = systemMapPoint(state)
  const heading = headingOf(state)
  const activeOrderId = state.activeOrderId || orderId
  const label = activeOrderId ? `执行中 · ${escapeHtml(activeOrderId)}` : '无人机当前位置'
  const marker = L.marker([position.lat,position.lon], {icon: L.divIcon({className:'drone-pin',html:`<span><i style="transform:rotate(${heading.toFixed(1)}deg)">✈</i></span><b class="map-marker-label">${label}</b>`,iconSize:[48,48],iconAnchor:[24,24]})})
  if (debug) marker.bindPopup(`<strong>无人机 ${escapeHtml(state.uavId || '未知')}</strong><br>系统坐标（CGCS2000/WGS-84）<br>${position.lon.toFixed(6)}, ${position.lat.toFixed(6)}`)
  marker.addTo(group)
}
