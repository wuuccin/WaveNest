import L from 'leaflet'
export function drawMeeting(map, group, state) {
  group.clearLayers()
  if (!state || !Number.isFinite(state.lat) || !Number.isFinite(state.lon)) return
  L.marker([state.lat, state.lon], { icon: L.divIcon({ className:`meeting-pin ${state.locked ? 'locked' : ''}`,
    html:'<span class="crosshair">⊕</span>', iconSize:[42,42], iconAnchor:[21,21] }) }).addTo(group)
}
