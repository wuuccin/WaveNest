import L from 'leaflet'
import { systemMapPoint, toMapLatLng } from '../map/coordinates'

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])

const validPoint = point => {
  const converted = systemMapPoint(point)
  return converted && Number.isFinite(converted.lon) && Number.isFinite(converted.lat)
}

export function drawStaticFeatures(group, features) {
  group.clearLayers()
  const bridges = features.filter(feature => feature.kind === 'bridge' && validPoint(feature.center))
  const waterways = features.filter(feature => feature.kind === 'waterway' && Array.isArray(feature.points))

  for (const waterway of waterways) drawWaterway(group, waterway.points.filter(validPoint), waterway.name || '航道')

  // The schema currently has no authoritative waterway-GIS table. Until it is
  // supplied, the bridge-centre records provide a transparent, data-only
  // demonstration reference. No bridge rows means no derived line is drawn.
  if (!waterways.length && bridges.length >= 2) {
    drawWaterway(group, bridges.map(feature => feature.center).sort((a, b) => a.lat - b.lat), '航道参考线（三桥台账推导）', true)
  }

  for (const bridge of bridges) {
    const marker = L.marker(toMapLatLng(bridge.center), {
      zIndexOffset: 450,
      icon: L.divIcon({
        className: 'bridge-pin',
        html: `<span>桥</span><b>${escapeHtml(bridge.name || bridge.restrictionId)}</b>`,
        iconSize: [34, 38], iconAnchor: [17, 19]
      }),
      title: bridge.name || bridge.restrictionId
    })
    marker.bindPopup(`<strong>${escapeHtml(bridge.name || bridge.restrictionId)}</strong><br>类型：${escapeHtml(bridge.type || '桥梁')}<br>净空高：${escapeHtml(bridge.clearanceHeightM ?? '未登记')} m<br>通航孔宽：${escapeHtml(bridge.spanWidthM ?? '未登记')} m<br><small>${escapeHtml(bridge.source || '后端空域台账')}</small>`)
    marker.addTo(group)
  }
}

function drawWaterway(group, points, name, derived = false) {
  if (points.length < 2) return
  const latLngs = points.map(toMapLatLng)
  L.polyline(latLngs, { color: '#58a6b8', weight: 24, opacity: .18, interactive: false }).addTo(group)
  const line = L.polyline(latLngs, { color: '#167b90', weight: 3, opacity: .9, dashArray: '10 8' }).addTo(group)
  line.bindTooltip(name, { sticky: true, className: 'waterway-tooltip' })
  line.featureKind = derived ? 'derived-waterway' : 'waterway'
}

export function drawFleet(group, drones, nests) {
  group.clearLayers()
  const nestById = new Map(nests.filter(validPoint).map(nest => [nest.nestId, nest]))
  const byNest = new Map()
  for (const drone of drones) {
    const nestId = drone.currentNestId || drone.homeNestId
    if (!nestById.has(nestId)) continue
    const list = byNest.get(nestId) || []
    list.push(drone)
    byNest.set(nestId, list)
  }
  for (const [nestId, members] of byNest) {
    const nest = nestById.get(nestId)
    const marker = L.marker(toMapLatLng(nest), {
      zIndexOffset: 520,
      icon: L.divIcon({
        className: 'fleet-pin',
        html: `<span>机队 ${members.length}</span>`,
        iconSize: [68, 24], iconAnchor: [-8, 32]
      }),
      title: `${nestId} 机队`
    })
    const rows = members.map(item => `${escapeHtml(item.droneId)} · ${escapeHtml(item.status || '状态未登记')} · ${escapeHtml(item.battery ?? '—')}%`).join('<br>')
    marker.bindPopup(`<strong>${escapeHtml(nestId)} 机队（${members.length}）</strong><br>${rows}`)
    marker.addTo(group)
  }
}
