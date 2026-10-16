import L from 'leaflet'
const color = { '去程':'#3579b5', '末段交接':'#d3962f', '返程':'#319780' }
export function drawDrone(map, group, state) {
  group.clearLayers()
  if (!state) return
  for (const segment of state.segments || [])
    L.polyline(segment.points.map(p => [p.lat,p.lon]), {color: color[segment.phase] || '#6f8792', weight:4, opacity:.93}).addTo(group)
  L.marker([state.lat,state.lon], {icon: L.divIcon({className:'drone-pin',html:'<span>✦</span>',iconSize:[32,32],iconAnchor:[16,16]})}).addTo(group)
}
