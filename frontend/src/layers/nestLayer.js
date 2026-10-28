import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates'

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])

function validNest(nest) {
  return Boolean(nest?.nestId) && Number.isFinite(Number(nest.lon)) && Number.isFinite(Number(nest.lat))
}

export function drawNests(group, nests, debug = false) {
  group.clearLayers()
  for (const nest of nests.filter(validNest)) {
    const point = systemMapPoint(nest)
    const marker = L.marker([point.lat, point.lon], {
      icon: L.divIcon({
        className: 'nest-pin',
        html: `<span>⌂</span><b class="nest-marker-label">${escapeHtml(nest.nestId)}</b>`,
        iconSize: [38, 42],
        iconAnchor: [19, 21]
      }),
      title: `机巢 ${nest.nestId}`
    })
    const models = Array.isArray(nest.supportedModels) ? nest.supportedModels.join('、') : ''
    marker.bindPopup(`<strong>机巢 ${escapeHtml(nest.nestId)}</strong><br>容量：${escapeHtml(nest.capacity ?? '未登记')}<br>状态：${escapeHtml(nest.status ?? '未登记')}<br>支持机型：${escapeHtml(models || '未登记')}${debug ? `<br>系统坐标（CGCS2000/WGS-84）<br>${Number(nest.lon).toFixed(6)}, ${Number(nest.lat).toFixed(6)}` : ''}`)
    marker.addTo(group)
  }
}

export function nestMapPoints(nests) {
  return nests.filter(validNest).map(nest => ({ lon: Number(nest.lon), lat: Number(nest.lat) }))
}
