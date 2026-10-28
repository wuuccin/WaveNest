import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates'
const color = { '去程':'#3579b5', '末段交接':'#d3962f', '返程':'#319780', '去程中':'#3579b5', '末段交接中':'#d3962f', '返航中':'#319780', OUTBOUND:'#3579b5', HANDOVER:'#d3962f', RETURNING:'#319780' }
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])
export function drawDrone(map, group, state, debug = false) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  for (const segment of state.segments || []) {
    const points = (segment.points || []).filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
    if (points.length > 1) L.polyline(points.map(p => { const point = systemMapPoint(p); return [point.lat, point.lon] }), {color: color[segment.phase] || '#6f8792', weight:5, opacity:.93}).addTo(group)
  }
  const position = systemMapPoint(state)
  const marker = L.marker([position.lat,position.lon], {icon: L.divIcon({className:'drone-pin',html:'<span>✈</span><b class="map-marker-label">无人机当前位置</b>',iconSize:[48,48],iconAnchor:[24,24]})})
  if (debug) marker.bindPopup(`<strong>无人机 ${escapeHtml(state.uavId || '未知')}</strong><br>系统坐标（CGCS2000/WGS-84）<br>${position.lon.toFixed(6)}, ${position.lat.toFixed(6)}`)
  marker.addTo(group)
}
