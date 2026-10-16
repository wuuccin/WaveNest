import L from 'leaflet'

function ellipsePoints(lon, lat, along, cross, course) {
  const angle = course * Math.PI / 180
  const points = []
  for (let i = 0; i <= 64; i++) {
    const t = i * Math.PI * 2 / 64
    const forward = along * Math.cos(t), right = cross * Math.sin(t)
    const east = forward * Math.sin(angle) + right * Math.cos(angle)
    const north = forward * Math.cos(angle) - right * Math.sin(angle)
    points.push([lat + north / 111132, lon + east / (111320 * Math.cos(lat * Math.PI / 180))])
  }
  return points
}
export function drawShip(map, group, state) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lon) || !Number.isFinite(state.lat)) return
  const points = state.points || []
  if (points.length) {
    L.polyline(points.map(p => [p.lat, p.lon]), { color: '#087f71', weight: 3, dashArray: '9 9', opacity: .95 }).addTo(group)
    const p = points.at(-1), cov = state.cov
    if (cov && Number.isFinite(cov.sigmaAlong) && Number.isFinite(cov.sigmaCross))
      L.polygon(ellipsePoints(p.lon, p.lat, 2 * cov.sigmaAlong, 2 * cov.sigmaCross, state.courseDeg || 0),
        { color:'#158b7b', weight:1, fillColor:'#48bca8', fillOpacity:.17 }).addTo(group)
  }
  L.marker([state.lat, state.lon], { icon: L.divIcon({ className:'ship-pin', html:'<span>◆</span>', iconSize:[32,32], iconAnchor:[16,16] }) }).addTo(group)
}
