<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import L from 'leaflet'
import { useShipStore, useDroneStore, useOrderStore, useAlertStore, useNestStore, useFleetStore, useAirspaceStore } from '../stores'
import { drawShip } from '../layers/shipLayer'
import { drawMeeting } from '../layers/meetingLayer'
import { drawDrone } from '../layers/droneTrackLayer'
import { drawAlerts } from '../layers/alertLayer'
import { drawNests } from '../layers/nestLayer'
import { drawFleet, drawStaticFeatures } from '../layers/staticFeatureLayer'
import { systemMapPoint, toMapLatLng } from '../map/coordinates'

const props = defineProps({ comparison: { type: Object, default: null }, branchRoute: { type: Object, default: null }, focusRoute: { type: Boolean, default: false }, droneId: { type: String, default: '' } })
const ship = useShipStore(), drone = useDroneStore(), order = useOrderStore(), alert = useAlertStore(), nest = useNestStore(), fleet = useFleetStore(), airspace = useAirspaceStore()
let map, staticGroup, comparisonGroup, shipGroup, meetingGroup, droneGroup, branchGroup, alertGroup, nestGroup, fleetGroup, debugReferenceGroup
let activeBaseLayer = null, activeBaseType = 'imagery'
const valid = point => point && Number.isFinite(point.lat) && Number.isFinite(point.lon)
const visibleDrone = computed(() => props.droneId ? (drone.byId[props.droneId] || null) : drone.current)
const displayNests = computed(() => nest.items.filter(item => inServiceArea(item)))
const displayAirspace = computed(() => airspace.items.filter(item => item.kind !== 'bridge' || inServiceArea(item.center)))
const tileKey = String(import.meta.env.VITE_TIANDITU_KEY || '').trim()
const tileStatus = ref(tileKey ? 'loading' : 'missing-key')
const tileCaption = computed(() => tileStatus.value === 'loaded'
  ? (activeBaseType === 'imagery' ? '卫星影像' : '矢量底图')
  : '主视图')
const debugEnabled = import.meta.env.DEV && new URLSearchParams(window.location.search).get('debug') === '1'
const pickedPoint = ref(null)
const copyStatus = ref('')
const baseLayers = {}
const serviceArea = {
  southwest: { lon: 115.844, lat: 28.638 },
  northeast: { lon: 115.889, lat: 28.702 }
}
const debugBridgeReferences = [
  { id: 'BRIDGE-CHAOYANG', name: '朝阳大桥库内参考点', lon: 115.852, lat: 28.646 },
  { id: 'BRIDGE-NANCHANG', name: '南昌大桥库内参考点', lon: 115.859, lat: 28.663 },
  { id: 'BRIDGE-BAYI', name: '八一大桥库内参考点', lon: 115.879, lat: 28.692 }
]

function safeMapPoint(point) {
  const systemPoint = systemMapPoint(point)
  return systemPoint && valid(systemPoint) ? systemPoint : null
}

function pickMapPoint(event) {
  if (!debugEnabled) return
  pickedPoint.value = { lon: event.latlng.lng, lat: event.latlng.lat }
  copyStatus.value = ''
}

async function copyPickedPoint() {
  if (!pickedPoint.value) return
  const text = `${pickedPoint.value.lon.toFixed(7)}, ${pickedPoint.value.lat.toFixed(7)}`
  try {
    await navigator.clipboard.writeText(text)
    copyStatus.value = '已复制'
  } catch {
    copyStatus.value = '复制失败，请手动选择'
  }
}

function drawDebugReferences() {
  if (!debugEnabled || !debugReferenceGroup) return
  debugReferenceGroup.clearLayers()
  for (const bridge of debugBridgeReferences) {
    const marker = L.marker(toMapLatLng(bridge), {
      zIndexOffset: 700,
      interactive: false,
      icon: L.divIcon({
        className: 'debug-bridge-pin',
        html: `<span>＋</span><b>${escapeHtml(bridge.name)}</b>`,
        iconSize: [26, 26], iconAnchor: [13, 13]
      })
    })
    marker.addTo(debugReferenceGroup)
  }
}

function inServiceArea(point) {
  return valid(point)
    && point.lon >= serviceArea.southwest.lon && point.lon <= serviceArea.northeast.lon
    && point.lat >= serviceArea.southwest.lat && point.lat <= serviceArea.northeast.lat
}

const mapDataNotice = computed(() => {
  if (nest.error || fleet.error || airspace.error) return `静态台账读取失败：${nest.error || fleet.error || airspace.error}`
  if (nest.loaded && !nest.items.length) return '后端暂未返回有效机巢坐标，因此不显示占位机巢。'
  const outsideNestCount = nest.items.length - displayNests.value.length
  if (outsideNestCount > 0) return `${outsideNestCount} 个机巢坐标超出赣江南昌段视野，未绘制；请核对后端场景坐标。`
  const outsideLivePoint = [ship.current, visibleDrone.value, order.rendezvous].some(point => valid(point) && !inServiceArea(point))
  if (outsideLivePoint) return '有实时位置超出赣江南昌段视野；请核对仿真坐标。视野保持在当前作业区。'
  return ''
})

function createBaseLayer(type, name) {
  const first = L.tileLayer(`https://t{s}.tianditu.gov.cn/DataServer?T=${type}_w&x={x}&y={y}&l={z}&tk=${encodeURIComponent(tileKey)}`, {
    subdomains: '01234567', maxZoom: 18, tileSize: 256, attribution: '© 国家地理信息公共服务平台 · 天地图'
  })
  const annotationType = type === 'img' ? 'cia' : 'cva'
  const annotation = L.tileLayer(`https://t{s}.tianditu.gov.cn/DataServer?T=${annotationType}_w&x={x}&y={y}&l={z}&tk=${encodeURIComponent(tileKey)}`, {
    subdomains: '01234567', maxZoom: 18, tileSize: 256, attribution: '© 国家地理信息公共服务平台 · 天地图'
  })
  for (const layer of [first, annotation]) {
    layer.on('load', () => { if (activeBaseType === (type === 'img' ? 'imagery' : 'vector')) tileStatus.value = 'loaded' })
    layer.on('tileerror', () => { if (activeBaseType === (type === 'img' ? 'imagery' : 'vector')) tileStatus.value = 'tile-error' })
  }
  const group = L.layerGroup([first, annotation])
  group.options.attribution = `${escapeHtml(name)} · © 国家地理信息公共服务平台`
  return group
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character])
}

function setBaseLayer(type) {
  if (!map || !tileKey || !baseLayers[type]) return
  if (activeBaseLayer) map.removeLayer(activeBaseLayer)
  activeBaseType = type
  activeBaseLayer = baseLayers[type].addTo(map)
  tileStatus.value = 'loading'
}

function installBaseLayerControl() {
  const control = L.control({ position: 'topright' })
  control.onAdd = () => {
    const container = L.DomUtil.create('div', 'leaflet-control map-basemap-control')
    const label = L.DomUtil.create('label', '', container)
    label.textContent = '底图 '
    const select = L.DomUtil.create('select', '', label)
    select.setAttribute('aria-label', '地图底图')
    select.innerHTML = '<option value="imagery">卫星影像</option><option value="vector">矢量底图</option>'
    select.value = activeBaseType
    select.disabled = !tileKey
    L.DomEvent.disableClickPropagation(container)
    L.DomEvent.on(select, 'change', () => setBaseLayer(select.value))
    return container
  }
  control.addTo(map)
}
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
      L.polyline(points.map(toMapLatLng), {
        color: '#64748b', weight: 5, dashArray: '10 8', opacity: .9
      }).addTo(comparisonGroup)
      const midpoint = points[Math.floor(points.length / 2)]
      const mapMidpoint = safeMapPoint(midpoint)
      L.marker([mapMidpoint.lat, mapMidpoint.lon], {
        interactive: false,
        icon: L.divIcon({ className: 'map-route-tag map-route-tag-old', html: '变向前预测', iconSize: [92, 26], iconAnchor: [46, 30] })
      }).addTo(comparisonGroup)
    }
  }
  if (valid(previousMeeting)) {
    const oldPoint = safeMapPoint(previousMeeting)
    L.marker([oldPoint.lat, oldPoint.lon], {
      interactive: false,
      icon: L.divIcon({ className: 'previous-meeting-pin', html: '<span>×</span><b>原会面点</b>', iconSize: [48, 48], iconAnchor: [24, 24] })
    }).addTo(comparisonGroup)
    const currentMeeting = order.rendezvous
    if (valid(currentMeeting) && (Math.abs(currentMeeting.lon - previousMeeting.lon) > 0.00001 || Math.abs(currentMeeting.lat - previousMeeting.lat) > 0.00001)) {
      L.polyline([toMapLatLng(previousMeeting), toMapLatLng(currentMeeting)], {
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
  const latLngs = points.map(toMapLatLng)
  L.polyline(latLngs, { color: '#fff', weight: 15, opacity: .95, interactive: false }).addTo(branchGroup)
  L.polyline(latLngs, { color: continuing ? '#176ac3' : '#bb5537', weight: 10, opacity: 1, interactive: false }).addTo(branchGroup)
  const middle = { lat: (points[0].lat + points[1].lat) / 2, lon: (points[0].lon + points[1].lon) / 2 }
  const mapMiddle = safeMapPoint(middle)
  L.marker([mapMiddle.lat, mapMiddle.lon], { interactive: false,
    icon: L.divIcon({ className: `map-branch-label ${continuing ? 'continue' : 'return'}`,
      html: continuing ? '→ 原机续送新会面点' : '→ 原机带货返航', iconSize: [165, 32], iconAnchor: [82, 44] }) }).addTo(branchGroup)
  if (!continuing) L.marker(toMapLatLng(points[1]), { interactive: false,
    icon: L.divIcon({ className: 'map-nest-label', html: '⌂ 机巢 / 货物回收', iconSize: [150, 30], iconAnchor: [75, -8] }) }).addTo(branchGroup)
}

const update = () => {
  if (!map) return
  drawComparison()
  drawShip(map, shipGroup, ship.current, debugEnabled)
  drawMeeting(map, meetingGroup, order.rendezvous)
  drawDrone(map, droneGroup, visibleDrone.value, debugEnabled)
  drawNests(nestGroup, displayNests.value, debugEnabled)
  drawFleet(fleetGroup, fleet.items, displayNests.value)
  drawStaticFeatures(staticGroup, displayAirspace.value)
  drawBranchRoute()
  drawAlerts(map, alertGroup, alert.items, order.orders, visibleDrone.value, ship.current)
  const rawPoints = (props.focusRoute
    ? [ship.current, visibleDrone.value, order.rendezvous, props.comparison?.meeting, ...(props.branchRoute?.points || [])]
    : [ship.current, ...(ship.current?.points || []), visibleDrone.value,
      ...(visibleDrone.value?.segments || []).flatMap(segment => segment.points || []), order.rendezvous,
      props.comparison?.ship, ...(props.comparison?.ship?.points || []), props.comparison?.meeting,
      ...(props.branchRoute?.points || [])]).filter(valid)
}

function fitServiceArea() {
  const corners = [
    safeMapPoint(serviceArea.southwest),
    safeMapPoint(serviceArea.northeast)
  ].filter(Boolean)
  if (!corners.length) return
  map.fitBounds(L.latLngBounds(corners.map(point => [point.lat, point.lon])).pad(.08), {
    padding: [24, 24], maxZoom: 13, animate: false
  })
}

onMounted(() => {
  map = L.map('operations-map', { zoomControl: false, attributionControl: true, minZoom: 4, maxZoom: 18 })
  map.attributionControl.setPrefix(false)
  if (tileKey) {
    map.attributionControl.addAttribution('© 国家地理信息公共服务平台 · 天地图')
    baseLayers.imagery = createBaseLayer('img', '卫星影像')
    baseLayers.vector = createBaseLayer('vec', '矢量底图')
    setBaseLayer('imagery')
  }
  L.control.zoom({ position: 'bottomright' }).addTo(map)
  comparisonGroup = L.layerGroup().addTo(map)
  staticGroup = L.layerGroup().addTo(map)
  shipGroup = L.layerGroup().addTo(map)
  meetingGroup = L.layerGroup().addTo(map)
  branchGroup = L.layerGroup().addTo(map)
  droneGroup = L.layerGroup().addTo(map)
  alertGroup = L.layerGroup().addTo(map)
  nestGroup = L.layerGroup().addTo(map)
  fleetGroup = L.layerGroup().addTo(map)
  debugReferenceGroup = L.layerGroup().addTo(map)
  installBaseLayerControl()
  drawDebugReferences()
  fitServiceArea()
  map.on('resize', () => { map.invalidateSize(); fitServiceArea() })
  if (debugEnabled) map.on('click', pickMapPoint)
  update()
  setTimeout(() => { if (map) { map.invalidateSize(); fitServiceArea(); update() } }, 100)
})
watch([() => ship.current, () => visibleDrone.value, () => order.rendezvous, () => alert.items.length,
  () => order.orders, () => props.comparison, () => props.branchRoute, () => props.droneId, () => nest.items,
  () => fleet.items, () => airspace.items], update, { deep: true })
onUnmounted(() => { if (map && debugEnabled) map.off('click', pickMapPoint); map?.remove(); map = null; activeBaseLayer = null; debugReferenceGroup = null })
</script>

<template>
  <div class="map-view" :class="{ 'is-comparison': comparison }">
    <div v-if="comparison && meetingShiftKm !== null" class="map-change-summary">
      <div><span>变向前预计会面点</span><strong>{{ comparison.meeting.lon.toFixed(3) }}°E，{{ comparison.meeting.lat.toFixed(3) }}°N</strong></div>
      <b>→</b>
      <div><span>更新后的候选会面点</span><strong>{{ order.rendezvous.lon.toFixed(3) }}°E，{{ order.rendezvous.lat.toFixed(3) }}°N</strong></div>
      <em>位置变化约 {{ meetingShiftKm }} km</em>
    </div>
    <div v-if="mapDataNotice" class="map-data-notice" role="status">{{ mapDataNotice }}</div>
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
      <span v-if="displayNests.length"><i class="map-symbol map-symbol-nest">⌂</i> 后端机巢</span>
      <span v-if="fleet.items.length"><i class="map-symbol map-symbol-fleet">机</i> 后端机队</span>
      <span v-if="displayAirspace.some(item => item.kind === 'bridge')"><i class="map-symbol map-symbol-bridge">桥</i> 桥梁净空</span>
      <span v-if="displayAirspace.some(item => item.kind === 'waterway') || displayAirspace.filter(item => item.kind === 'bridge').length >= 2"><i class="map-line map-line-waterway"></i> 航道参考</span>
      <span v-if="branchRoute"><i class="map-line" :class="branchRoute.type === 'continue' ? 'map-line-continue' : 'map-line-abandon'"></i> {{ branchRoute.type === 'continue' ? '原机改道续送' : '原机带货返航' }}</span>
    </div>
    <div class="map-shell">
      <div id="operations-map"></div>
      <div class="map-caption"><span class="live-dot"></span>{{ comparison ? '变向前后对照' : `赣江南昌段 · ${tileCaption}` }}</div>
      <div class="map-scale">系统与底图：CGCS2000 / WGS-84</div>
      <section v-if="debugEnabled" class="map-pick-tool" aria-label="开发取点工具" @click.stop @dblclick.stop @mousedown.stop>
        <strong>开发取点 · 系统坐标</strong>
        <span>点击地图取 CGCS2000 / WGS-84 坐标；点击船、无人机或机巢查看 ID。</span>
        <code v-if="pickedPoint">{{ pickedPoint.lon.toFixed(7) }}, {{ pickedPoint.lat.toFixed(7) }}</code>
        <code v-else>尚未取点</code>
        <button type="button" :disabled="!pickedPoint" @click="copyPickedPoint">复制经纬度</button>
        <small v-if="copyStatus">{{ copyStatus }}</small>
      </section>
      <div v-if="tileStatus === 'missing-key'" class="map-tile-notice" role="status"><strong>卫星底图待配置</strong><span>在 frontend/.env.local 设置 VITE_TIANDITU_KEY；密钥不会提交到仓库。</span><small>本地开发地址需加入天地图密钥白名单。</small></div>
      <div v-else-if="tileStatus === 'tile-error'" class="map-tile-notice error" role="status"><strong>天地图瓦片加载失败</strong><span>请检查密钥、域名白名单和网络后刷新页面。</span></div>
      <div v-else-if="tileStatus === 'loading'" class="map-tile-state" role="status">正在加载天地图底图…</div>
    </div>
  </div>
</template>
