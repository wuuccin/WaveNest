/**
 * 赣江南昌段的点位常量，<b>离线样例与界面提示共用一份</b>。
 *
 * <p>现行口径取影像河心中心线，与 {@code application.yml} 的 AIS path、
 * centerline 以及三桥台账同源。船起点取沿程 2.40 km 处
 * {@code 115.858005/28.662662}。
 * <p>本模块是离线坐标的唯一来源，{@code mock.js} 与 {@code mockPrediction.js}
 * 都从这里导入，避免同一批常量在多处硬编码后再次漂移。
 * <p>本模块不 import 组件内部变量（组件里的常量不是模块接口，改天重构就断）。
 */
export const V3_START = Object.freeze({ lon: 115.858005, lat: 28.662662 })
export const V3_SHIP_POINTS = Object.freeze([
  V3_START,
  Object.freeze({ lon: 115.864577, lat: 28.681114 }),
  Object.freeze({ lon: 115.872180, lat: 28.692014 })
])
export const NANCHANG_SEGMENT = {
  /** 样例点列以此为中心 */
  midpoint: V3_START,
  /** 三桥台账中心，样例用来判断点列没跑出水域 */
  bridges: [
    { id: 'BRIDGE-CHAOYANG', lon: 115.844393, lat: 28.645998 },
    { id: 'BRIDGE-NANCHANG', lon: 115.857937, lat: 28.663776 },
    { id: 'BRIDGE-BAYI', lon: 115.872180, lat: 28.692014 }
  ],
  /** 视野范围，与 MapView 的 serviceArea 同口径 */
  southwest: { lon: 115.844, lat: 28.638 },
  northeast: { lon: 115.889, lat: 28.702 }
}

/** 点是否落在南昌段视野内——样例越界时面板要能提示，而不是画到看不见的地方。 */
export function inSegment(point) {
  if (!point || !Number.isFinite(point.lon) || !Number.isFinite(point.lat)) return false
  return point.lon >= NANCHANG_SEGMENT.southwest.lon
    && point.lon <= NANCHANG_SEGMENT.northeast.lon
    && point.lat >= NANCHANG_SEGMENT.southwest.lat
    && point.lat <= NANCHANG_SEGMENT.northeast.lat
}
