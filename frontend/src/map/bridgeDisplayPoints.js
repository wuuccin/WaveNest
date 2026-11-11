/**
 * 桥梁示意标的桥面坐标，由用户在卫星影像上逐桥取点标定。
 *
 * 台账 center 继续用于后端限高判据与三桥推导的航道参考线，
 * 本表只决定地图 marker 的落点，避免把业务判据一起搬走。
 */
export const BRIDGE_DISPLAY_POINTS = Object.freeze({
  'BRIDGE-BAYI': Object.freeze({ lon: 115.8759117, lat: 28.6938576 }),
  'BRIDGE-NANCHANG': Object.freeze({ lon: 115.8591239, lat: 28.6637241 }),
  'BRIDGE-CHAOYANG': Object.freeze({ lon: 115.8499020, lat: 28.6469236 })
})

export function bridgeDisplayPoint(feature) {
  const point = BRIDGE_DISPLAY_POINTS[feature?.restrictionId]
  return point && Number.isFinite(point.lon) && Number.isFinite(point.lat) ? point : null
}
