<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import L from 'leaflet'
import { useShipStore, useDroneStore, useOrderStore, useAlertStore } from '../stores'
import { drawShip } from '../layers/shipLayer'
import { drawMeeting } from '../layers/meetingLayer'
import { drawDrone } from '../layers/droneTrackLayer'
import { drawAlerts } from '../layers/alertLayer'
const ship = useShipStore(), drone = useDroneStore(), order = useOrderStore(), alert = useAlertStore()
let map, shipGroup, meetingGroup, droneGroup, alertGroup
const update = () => {
  if (!map) return
  drawShip(map, shipGroup, ship.current)
  drawMeeting(map, meetingGroup, order.rendezvous)
  drawDrone(map, droneGroup, drone.current)
  drawAlerts(map, alertGroup, alert.items, order.orders, drone.current, ship.current)
  const focus = drone.current || ship.current
  if (focus && Number.isFinite(focus.lat) && Number.isFinite(focus.lon) && !map.getBounds().contains([focus.lat,focus.lon])) map.panTo([focus.lat,focus.lon])
}
onMounted(() => {
  map = L.map('operations-map', {zoomControl:false,attributionControl:false,minZoom:8,maxZoom:15}).setView([29.72,115.92],11)
  L.control.zoom({position:'bottomright'}).addTo(map)
  // Local vectors keep the scenario visible without network tile access.
  L.polygon([[29.57,115.64],[29.57,116.18],[29.89,116.18],[29.89,115.64]],{stroke:false,fillColor:'#e9efec',fillOpacity:1}).addTo(map)
  const river = [[29.62,115.62],[29.65,115.70],[29.67,115.79],[29.70,115.84],[29.72,115.91],[29.74,115.98],[29.76,116.04],[29.78,116.14]]
  L.polyline(river,{color:'#b9d7da',weight:82,opacity:1}).addTo(map)
  L.polyline(river,{color:'#cde8e8',weight:64,opacity:1}).addTo(map)
  L.polyline(river,{color:'#8dbbc0',weight:2,opacity:.8,dashArray:'12 14'}).addTo(map)
  L.polyline([[29.58,115.68],[29.65,115.75],[29.70,115.80],[29.76,115.90],[29.84,116.02]],{color:'#d2dcda',weight:11,opacity:.95}).addTo(map)
  L.polyline([[29.84,115.72],[29.80,115.82],[29.77,115.89],[29.72,115.98],[29.68,116.11]],{color:'#d2dcda',weight:8,opacity:.95}).addTo(map)
  L.marker([29.68,115.86],{icon:L.divIcon({className:'nest-pin',html:'<span>⌂</span><small>01 号机巢</small>',iconSize:[100,34],iconAnchor:[16,17]})}).addTo(map)
  L.marker([29.76,116.03],{icon:L.divIcon({className:'place-pin',html:'彭泽作业区',iconSize:[90,20]})}).addTo(map)
  L.marker([29.62,115.72],{icon:L.divIcon({className:'place-pin',html:'沿江补给站',iconSize:[90,20]})}).addTo(map)
  shipGroup=L.layerGroup().addTo(map);meetingGroup=L.layerGroup().addTo(map);droneGroup=L.layerGroup().addTo(map);alertGroup=L.layerGroup().addTo(map)
  update(); setTimeout(()=>map?.invalidateSize(),100)
})
watch([()=>ship.current,()=>drone.current,()=>order.rendezvous,()=>alert.items.length,()=>order.orders],update,{deep:true})
onUnmounted(()=>{map?.remove();map=null})
</script>
<template><div class="map-shell"><div id="operations-map"></div><div class="map-caption"><span class="live-dot"></span>沿江作业区 <span class="map-divider">/</span> 本地航道示意</div><div class="map-scale">WGS-84 · 2D 态势</div></div></template>
