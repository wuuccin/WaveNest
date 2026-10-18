<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import L from 'leaflet'
import { useShipStore, useDroneStore, useOrderStore, useAlertStore } from '../stores'
import { drawShip } from '../layers/shipLayer'
import { drawMeeting } from '../layers/meetingLayer'
import { drawDrone } from '../layers/droneTrackLayer'
import { drawAlerts } from '../layers/alertLayer'

const ship = useShipStore(), drone = useDroneStore(), order = useOrderStore(), alert = useAlertStore()
let map, shipGroup, meetingGroup, droneGroup, alertGroup, lastBoundsKey = ''
const valid = point => point && Number.isFinite(point.lat) && Number.isFinite(point.lon)
const update = () => {
  if (!map) return
  drawShip(map, shipGroup, ship.current)
  drawMeeting(map, meetingGroup, order.rendezvous)
  drawDrone(map, droneGroup, drone.current)
  drawAlerts(map, alertGroup, alert.items, order.orders, drone.current, ship.current)
  const points = [ship.current, ...(ship.current?.points || []), drone.current,
    ...(drone.current?.segments || []).flatMap(segment => segment.points || []), order.rendezvous].filter(valid)
  if (!points.length) return
  const bounds = L.latLngBounds(points.map(point => [point.lat, point.lon]))
  const key = `${bounds.getSouthWest().lat.toFixed(5)},${bounds.getSouthWest().lng.toFixed(5)}:${bounds.getNorthEast().lat.toFixed(5)},${bounds.getNorthEast().lng.toFixed(5)}`
  if (key !== lastBoundsKey) {
    lastBoundsKey = key
    if (points.length === 1 || bounds.getNorthEast().equals(bounds.getSouthWest())) map.setView(bounds.getCenter(), 11)
    else {
      const padding = Math.min(70, Math.max(24, map.getSize().x * .1))
      map.fitBounds(bounds.pad(.18), { padding: [padding, padding], maxZoom: 13, animate: false })
    }
  }
}

onMounted(() => {
  map = L.map('operations-map', { zoomControl: false, attributionControl: false, minZoom: 4, maxZoom: 15 }).setView([29.72, 115.92], 11)
  L.control.zoom({ position: 'bottomright' }).addTo(map)
  shipGroup = L.layerGroup().addTo(map)
  meetingGroup = L.layerGroup().addTo(map)
  droneGroup = L.layerGroup().addTo(map)
  alertGroup = L.layerGroup().addTo(map)
  update()
  setTimeout(() => { if (map) { map.invalidateSize(); lastBoundsKey = ''; update() } }, 100)
})
watch([() => ship.current, () => drone.current, () => order.rendezvous, () => alert.items.length,
  () => order.orders], update, { deep: true })
onUnmounted(() => { map?.remove(); map = null })
</script>

<template>
  <div class="map-view">
    <div class="map-guide" aria-label="地图图例">
      <span><i class="map-symbol map-symbol-ship">船</i> 船舶当前位置</span>
      <span><i class="map-symbol map-symbol-drone">✈</i> 无人机当前位置</span>
      <span><i class="map-symbol map-symbol-meeting">◎</i> 当前会面点</span>
      <span><i class="map-line map-line-ship"></i> 当前预测航迹</span>
      <span><i class="map-line map-line-drone"></i> 无人机去程</span>
      <span><i class="map-line map-line-handover"></i> 末段交接</span>
      <span><i class="map-line map-line-return"></i> 无人机返航</span>
    </div>
    <div class="map-shell">
      <div id="operations-map"></div>
      <div class="map-caption"><span class="live-dot"></span>位置与路线示意</div>
      <div class="map-scale">WGS-84 坐标</div>
    </div>
  </div>
</template>
