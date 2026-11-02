/**
 * Validate a system coordinate without changing its CRS.
 *
 * WaveNest stores CGCS2000/WGS-84 coordinates and Tianditu's `*_w` tiles use
 * the same geographic datum. Applying the consumer-map GCJ-02 offset here
 * would shift every operational feature by roughly 600 metres in Nanchang.
 */
export function systemMapPoint(point) {
  const lon = Number(point?.lon)
  const lat = Number(point?.lat)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null
  return { ...point, lon, lat }
}

export function toMapLatLng(point) {
  const systemPoint = systemMapPoint(point)
  return systemPoint ? [systemPoint.lat, systemPoint.lon] : null
}
