import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates'

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])

function ellipsePoints(lon, lat, along, cross, course) {
  const angle = course * Math.PI / 180
  const points = []
  for (let i = 0; i <= 64; i++) {
    const t = i * Math.PI * 2 / 64
    const forward = along * Math.cos(t), right = cross * Math.sin(t)
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
export function drawShip(map, group, state, debug = false) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  const points = (state.points || []).filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
  if (points.length) {
    L.polyline(points.map(p => { const point = systemMapPoint(p); return [point.lat, point.lon] }), { color: '#087f71', weight: 5, opacity: .95, dashArray: '11 8' }).addTo(group)
    const p = points.at(-1), cov = state.cov
    if (cov && Number.isFinite(cov.sigmaAlong) && Number.isFinite(cov.sigmaCross))
      L.polygon(ellipsePoints(p.lon, p.lat, 2 * cov.sigmaAlong, 2 * cov.sigmaCross, state.courseDeg || 0),
        { color:'#158b7b', weight:1, fillColor:'#48bca8', fillOpacity:.17 }).addTo(group)
  }
  const position = systemMapPoint(state)
  const marker = L.marker([position.lat, position.lon], { icon: L.divIcon({ className: 'ship-pin', html: '<span>船</span><b class="map-marker-label">船舶当前位置</b>', iconSize: [48, 48], iconAnchor: [24, 24] }) })
  if (debug) marker.bindPopup(`<strong>船舶 ${escapeHtml(state.mmsi || state.shipId || '未知')}</strong><br>系统坐标（CGCS2000/WGS-84）<br>${position.lon.toFixed(6)}, ${position.lat.toFixed(6)}`)
  marker.addTo(group)
}
