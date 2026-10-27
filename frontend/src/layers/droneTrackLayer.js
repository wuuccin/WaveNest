import L from 'leaflet'
import { wgs84ToGcj02 } from '../map/coordinates'
const color = { '去程':'#3579b5', '末段交接':'#d3962f', '返程':'#319780', '去程中':'#3579b5', '末段交接中':'#d3962f', '返航中':'#319780', OUTBOUND:'#3579b5', HANDOVER:'#d3962f', RETURNING:'#319780' }
export function drawDrone(map, group, state) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  for (const segment of state.segments || []) {
    const points = (segment.points || []).filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
    if (points.length > 1) L.polyline(points.map(p => { const point = wgs84ToGcj02(p); return [point.lat, point.lon] }), {color: color[segment.phase] || '#6f8792', weight:5, opacity:.93}).addTo(group)
  }
  const position = wgs84ToGcj02(state)
  L.marker([position.lat,position.lon], {icon: L.divIcon({className:'drone-pin',html:'<span>✈</span><b class="map-marker-label">无人机当前位置</b>',iconSize:[48,48],iconAnchor:[24,24]})}).addTo(group)
}
