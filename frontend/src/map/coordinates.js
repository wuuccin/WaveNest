const PI = Math.PI
const AXIS = 6378245.0
const ECCENTRICITY_SQUARED = 0.00669342162296594323

function outsideChina(lon, lat) {
  return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
}

function transformLatitude(x, y) {
  let value = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  value += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3
  value += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3
  value += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3
  return value
}

function transformLongitude(x, y) {
  let value = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  value += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3
  value += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3
  value += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3
  return value
}

/** Convert an internal WGS-84 point for rendering on China's GCJ-02 basemap. */
export function wgs84ToGcj02(point) {
  const lon = Number(point?.lon)
  const lat = Number(point?.lat)
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null
  if (outsideChina(lon, lat)) return { ...point, lon, lat }

  let dLat = transformLatitude(lon - 105, lat - 35)
  let dLon = transformLongitude(lon - 105, lat - 35)
  const radLat = lat / 180 * PI
  let magic = Math.sin(radLat)
  magic = 1 - ECCENTRICITY_SQUARED * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = dLat * 180 / ((AXIS * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic) * PI)
  dLon = dLon * 180 / (AXIS / sqrtMagic * Math.cos(radLat) * PI)
  return { ...point, lon: lon + dLon, lat: lat + dLat }
}

export function toMapLatLng(point) {
  const converted = wgs84ToGcj02(point)
  return converted ? [converted.lat, converted.lon] : null
}
