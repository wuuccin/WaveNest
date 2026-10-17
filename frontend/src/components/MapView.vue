<script setup>
import { computed, onMounted, onUnmounted, watch } from 'vue'
import L from 'leaflet'
import { useShipStore, useDroneStore, useOrderStore, useAlertStore } from '../stores'
import { drawShip } from '../layers/shipLayer'
import { drawMeeting } from '../layers/meetingLayer'
import { drawDrone } from '../layers/droneTrackLayer'
import { drawAlerts } from '../layers/alertLayer'

const props = defineProps({ comparison: { type: Object, default: null }, branchRoute: { type: Object, default: null }, focusRoute: { type: Boolean, default: false } })
const ship = useShipStore(), drone = useDroneStore(), order = useOrderStore(), alert = useAlertStore()
let map, comparisonGroup, shipGroup, meetingGroup, droneGroup, branchGroup, alertGroup, lastBoundsKey = ''
const valid = point => point && Number.isFinite(point.lat) && Number.isFinite(point.lon)
const meetingShiftKm = computed(() => {
  const before = props.comparison?.meeting
  const after = order.rendezvous
  if (!valid(before) || !valid(after)) return null
  const meanLat = (before.lat + after.lat) * Math.PI / 360
  const eastKm = (after.lon - before.lon) * 111.32 * Math.cos(meanLat)
  const northKm = (after.lat - before.lat) * 111.13
  return Math.hypot(eastKm, northKm).toFixed(1)
})

function drawComparison() {
  comparisonGroup.clearLayers()
  const previousShip = props.comparison?.ship
  const previousMeeting = props.comparison?.meeting
  if (previousShip?.points?.length) {
    const points = previousShip.points.filter(valid)
    if (points.length > 1) {
      L.polyline(points.map(point => [point.lat, point.lon]), {
        color: '#64748b', weight: 5, dashArray: '10 8', opacity: .9
      }).addTo(comparisonGroup)
      const midpoint = points[Math.floor(points.length / 2)]
      L.marker([midpoint.lat, midpoint.lon], {
        interactive: false,
        icon: L.divIcon({ className: 'map-route-tag map-route-tag-old', html: '变向前预测', iconSize: [92, 26], iconAnchor: [46, 30] })
      }).addTo(comparisonGroup)
    }
  }
  if (valid(previousMeeting)) {
    L.marker([previousMeeting.lat, previousMeeting.lon], {
      interactive: false,
      icon: L.divIcon({ className: 'previous-meeting-pin', html: '<span>×</span><b>原会面点</b>', iconSize: [48, 48], iconAnchor: [24, 24] })
    }).addTo(comparisonGroup)
    const currentMeeting = order.rendezvous
    if (valid(currentMeeting) && (Math.abs(currentMeeting.lon - previousMeeting.lon) > 0.00001 || Math.abs(currentMeeting.lat - previousMeeting.lat) > 0.00001)) {
      L.polyline([[previousMeeting.lat, previousMeeting.lon], [currentMeeting.lat, currentMeeting.lon]], {
        color: '#d18421', weight: 4, dashArray: '6 8', opacity: .95
      }).addTo(comparisonGroup)
    }
  }
}

function drawBranchRoute() {
  branchGroup.clearLayers()
  const points = props.branchRoute?.points?.filter(valid)
  if (points?.length !== 2) return
  const continuing = props.branchRoute.type === 'continue'
  const latLngs = points.map(point => [point.lat, point.lon])
  L.polyline(latLngs, { color: '#fff', weight: 15, opacity: .95, interactive: false }).addTo(branchGroup)
  L.polyline(latLngs, { color: continuing ? '#176ac3' : '#bb5537', weight: 10, opacity: 1, interactive: false }).addTo(branchGroup)
  const middle = { lat: (points[0].lat + points[1].lat) / 2, lon: (points[0].lon + points[1].lon) / 2 }
  L.marker([middle.lat, middle.lon], { interactive: false,
    icon: L.divIcon({ className: `map-branch-label ${continuing ? 'continue' : 'return'}`,
      html: continuing ? '→ 原机续送新会面点' : '→ 原机带货返航', iconSize: [165, 32], iconAnchor: [82, 44] }) }).addTo(branchGroup)
  if (!continuing) L.marker([points[1].lat, points[1].lon], { interactive: false,
    icon: L.divIcon({ className: 'map-nest-label', html: '⌂ 机巢 / 货物回收', iconSize: [150, 30], iconAnchor: [75, -8] }) }).addTo(branchGroup)
}

const update = () => {
  if (!map) return
  drawComparison()
  drawShip(map, shipGroup, ship.current)
  drawMeeting(map, meetingGroup, order.rendezvous)
  drawDrone(map, droneGroup, drone.current)
  drawBranchRoute()
  drawAlerts(map, alertGroup, alert.items, order.orders, drone.current, ship.current)
  const points = (props.focusRoute
    ? [ship.current, drone.current, order.rendezvous, props.comparison?.meeting, ...(props.branchRoute?.points || [])]
    : [ship.current, ...(ship.current?.points || []), drone.current,
      ...(drone.current?.segments || []).flatMap(segment => segment.points || []), order.rendezvous,
      props.comparison?.ship, ...(props.comparison?.ship?.points || []), props.comparison?.meeting,
      ...(props.branchRoute?.points || [])]).filter(valid)
  if (!points.length) return
  const bounds = L.latLngBounds(points.map(point => [point.lat, point.lon]))
  const key = `${bounds.getSouthWest().lat.toFixed(5)},${bounds.getSouthWest().lng.toFixed(5)}:${bounds.getNorthEast().lat.toFixed(5)},${bounds.getNorthEast().lng.toFixed(5)}`
  if (key !== lastBoundsKey) {
    lastBoundsKey = key
    if (points.length === 1 || bounds.getNorthEast().equals(bounds.getSouthWest())) map.setView(bounds.getCenter(), 11)
    else {
      const padding = props.focusRoute ? 32 : Math.min(70, Math.max(24, map.getSize().x * .1))
      map.fitBounds(bounds.pad(props.focusRoute ? .05 : .18), { padding: [padding, padding], maxZoom: props.focusRoute ? 15 : 13, animate: false })
    }
  }
}

onMounted(() => {
  map = L.map('operations-map', { zoomControl: false, attributionControl: false, minZoom: 4, maxZoom: 15 }).setView([29.72, 115.92], 11)
  L.control.zoom({ position: 'bottomright' }).addTo(map)
  comparisonGroup = L.layerGroup().addTo(map)
  shipGroup = L.layerGroup().addTo(map)
  meetingGroup = L.layerGroup().addTo(map)
  branchGroup = L.layerGroup().addTo(map)
  droneGroup = L.layerGroup().addTo(map)
  alertGroup = L.layerGroup().addTo(map)
  update()
  setTimeout(() => { if (map) { map.invalidateSize(); lastBoundsKey = ''; update() } }, 100)
})
watch([() => ship.current, () => drone.current, () => order.rendezvous, () => alert.items.length,
  () => order.orders, () => props.comparison, () => props.branchRoute], update, { deep: true })
onUnmounted(() => { map?.remove(); map = null })
</script>

<template>
  <div class="map-view" :class="{ 'is-comparison': comparison }">
    <div v-if="comparison && meetingShiftKm !== null" class="map-change-summary">
      <div><span>变向前预计会面点</span><strong>{{ comparison.meeting.lon.toFixed(3) }}°E，{{ comparison.meeting.lat.toFixed(3) }}°N</strong></div>
      <b>→</b>
      <div><span>更新后的候选会面点</span><strong>{{ order.rendezvous.lon.toFixed(3) }}°E，{{ order.rendezvous.lat.toFixed(3) }}°N</strong></div>
      <em>位置变化约 {{ meetingShiftKm }} km</em>
    </div>
    <div class="map-guide" aria-label="地图图例">
      <span><i class="map-symbol map-symbol-ship">船</i> 船舶当前位置</span>
      <span><i class="map-symbol map-symbol-drone">✈</i> 无人机当前位置</span>
      <span><i class="map-symbol map-symbol-meeting">◎</i> 当前会面点</span>
      <span><i class="map-line map-line-ship"></i> 当前预测航迹</span>
      <span><i class="map-line map-line-drone"></i> 无人机去程</span>
      <span><i class="map-line map-line-handover"></i> 末段交接</span>
      <span><i class="map-line map-line-return"></i> 无人机返航</span>
      <span v-if="comparison"><i class="map-line map-line-before"></i> 变向前预测航迹</span>
      <span v-if="comparison"><i class="map-symbol map-symbol-before">×</i> 原会面点</span>
      <span v-if="comparison"><i class="map-line map-line-change"></i> 会面点变化</span>
      <span v-if="branchRoute"><i class="map-line" :class="branchRoute.type === 'continue' ? 'map-line-continue' : 'map-line-abandon'"></i> {{ branchRoute.type === 'continue' ? '原机改道续送' : '原机带货返航' }}</span>
    </div>
    <div class="map-shell">
      <div id="operations-map"></div>
      <div class="map-caption"><span class="live-dot"></span>{{ comparison ? '变向前后对照' : '位置与路线示意' }}</div>
      <div class="map-scale">WGS-84 坐标</div>
    </div>
  </div>
</template>
