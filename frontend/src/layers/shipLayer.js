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
export function drawShip(map, group, state, actualTrack = [], debug = false) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  const actual = actualTrack.filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
  if (actual.length > 1) {
    L.polyline(actual.map(p => { const point = systemMapPoint(p); return [point.lat, point.lon] }), {
      className: 'ship-actual-track', color: '#164e63', weight: 6, opacity: 1
    }).addTo(group)
  }
  const points = (state.points || []).filter(p => Number.isFinite(p?.lon) && Number.isFinite(p?.lat))
  if (points.length) {
    L.polyline(points.map(p => { const point = systemMapPoint(p); return [point.lat, point.lon] }), {
      className: 'ship-predicted-track', color: '#0f9f8c', weight: 5, opacity: .95, dashArray: '11 8'
    }).addTo(group)
    const checkpointIndexes = [...new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])]
    for (const [sequence, index] of checkpointIndexes.entries()) {
      const p = points[index]
      const uncertainty = Number.isFinite(p.sigmaAlong) && Number.isFinite(p.sigmaCross) ? p : (index === points.length - 1 ? state.cov : null)
      if (!uncertainty || !Number.isFinite(uncertainty.sigmaAlong) || !Number.isFinite(uncertainty.sigmaCross)) continue
      const polygon = L.polygon(ellipsePoints(p.lon, p.lat, 2 * uncertainty.sigmaAlong, 2 * uncertainty.sigmaCross, state.courseDeg || 0), {
        className: `ship-confidence-ellipse confidence-${sequence + 1}`,
        color:'#d18421', weight:2, dashArray:'5 5', fillColor:'#f2bd62', fillOpacity:.08 + sequence * .05
      }).addTo(group)
      const horizon = Number.isFinite(p.t) && Number.isFinite(state.t0) ? Math.max(0, Math.round((p.t - state.t0) / 1000)) : null
      polygon.bindTooltip(`${horizon === null ? '预测点' : `+${horizon} 秒`} · 95% 半轴 ${Math.round(2 * uncertainty.sigmaAlong)} × ${Math.round(2 * uncertainty.sigmaCross)} m`)
    }
  }
  const position = systemMapPoint(state)
  const marker = L.marker([position.lat, position.lon], { icon: L.divIcon({ className: 'ship-pin', html: '<span>船</span><b class="map-marker-label">船舶当前位置</b>', iconSize: [48, 48], iconAnchor: [24, 24] }) })
  if (debug) marker.bindPopup(`<strong>船舶 ${escapeHtml(state.mmsi || state.shipId || '未知')}</strong><br>系统坐标（CGCS2000/WGS-84）<br>${position.lon.toFixed(6)}, ${position.lat.toFixed(6)}`)
  marker.addTo(group)
}
