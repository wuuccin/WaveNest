import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates'
export function drawMeeting(map, group, state) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lat) || !Number.isFinite(state.lon)) return
  const position = systemMapPoint(state)
  L.marker([position.lat, position.lon], { icon: L.divIcon({ className:`meeting-pin ${state.locked ? 'locked' : ''}`,
    html:'<span class="crosshair">◎</span><b class="map-marker-label">当前会面点</b>', iconSize:[52,52], iconAnchor:[26,26] }) }).addTo(group)
}
