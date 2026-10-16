import L from 'leaflet'
export function drawAlerts(map, group, alerts, orders, drone, ship) {
  group.clearLayers()
  const targets = new Set(alerts.filter(a => ['警告','严重'].includes(a.level)).map(a => a.targetId))
  for (const entity of [drone,ship]) {
    if (!entity || !targets.has(entity.uavId || entity.mmsi || entity.shipId) || !Number.isFinite(entity.lat) || !Number.isFinite(entity.lon)) continue
    L.marker([entity.lat,entity.lon], {icon:L.divIcon({className:'alert-flash',html:'<span>!</span>',iconSize:[40,40],iconAnchor:[20,20]})}).addTo(group)
  }
  const failed = orders.find(o => o.orderStatus === '失败' && o.emergencyLandingPoint)
  if (failed) {
    const p = failed.emergencyLandingPoint
    L.marker([p.lat,p.lon], {icon:L.divIcon({className:'alert-pin',html:'<span>!</span>',iconSize:[30,30],iconAnchor:[15,15]})}).addTo(group)
  }
}
