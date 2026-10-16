import L from 'leaflet'
export function drawAlerts(map, group, alerts, orders) {
  group.clearLayers()
  const failed = orders.find(o => o.orderStatus === '失败' && o.emergencyLandingPoint)
  if (failed) {
    const p = failed.emergencyLandingPoint
    L.marker([p.lat,p.lon], {icon:L.divIcon({className:'alert-pin',html:'<span>!</span>',iconSize:[30,30],iconAnchor:[15,15]})}).addTo(group)
  }
}
