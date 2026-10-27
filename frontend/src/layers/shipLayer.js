import L from 'leaflet'
import { wgs84ToGcj02 } from '../map/coordinates'

function ellipsePoints(lon, lat, along, cross, course) {
  const angle = course * Math.PI / 180
  const points = []
  for (let i = 0; i <= 64; i++) {
    const t = i * Math.PI * 2 / 64
    const forward = along * Math.cos(t), right = cross * Math.sin(t)
    const east = forward * Math.sin(angle) + right * Math.cos(angle)
    const north = forward * Math.cos(angle) - right * Math.sin(angle)
    const point = wgs84ToGcj02({
      lat: lat + north / 111132,
      lon: lon + east / (111320 * Math.cos(lat * Math.PI / 180))
    })
    points.push([point.lat, point.lon])
  }
  return points
}
export function drawShip(map, group, state) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  const points = (state.points || []).filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
  if (points.length) {
    L.polyline(points.map(p => { const point = wgs84ToGcj02(p); return [point.lat, point.lon] }), { color: '#087f71', weight: 5, opacity: .95, dashArray: '11 8' }).addTo(group)
    const p = points.at(-1), cov = state.cov
    if (cov && Number.isFinite(cov.sigmaAlong) && Number.isFinite(cov.sigmaCross))
      L.polygon(ellipsePoints(p.lon, p.lat, 2 * cov.sigmaAlong, 2 * cov.sigmaCross, state.courseDeg || 0),
        { color:'#158b7b', weight:1, fillColor:'#48bca8', fillOpacity:.17 }).addTo(group)
  }
  const position = wgs84ToGcj02(state)
  L.marker([position.lat, position.lon], { icon: L.divIcon({ className: 'ship-pin', html: '<span>船</span><b class="map-marker-label">船舶当前位置</b>', iconSize: [48, 48], iconAnchor: [24, 24] }) }).addTo(group)
}
