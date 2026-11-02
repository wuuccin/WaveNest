/**
 * 赣江南昌段的点位常量——<b>离线样例与界面提示共用一份</b>。
 *
 * <p>与 {@code MapView.vue} 里的 {@code serviceArea}／桥参考点是同一片水域，
 * 但<b>不 import 组件内部变量</b>（组件里的常量不是模块接口，改天重构就断）。
 * 这里只放「样例要落在视野里」这一件事需要的数；真值仍以
 * 任务清单「坐标口径」与后端台账为准：
 * NEST-01 {@code 115.8480/28.6420}、NEST-02 {@code 115.8670/28.6980}、
 * 船起点 {@code 115.8630/28.6680}。
 */
export const NANCHANG_SEGMENT = {
  /** 船起点（任务清单口径）——样例点列以此为中心 */
  midpoint: { lon: 115.863, lat: 28.668 },
  /** 三桥（任务清单口径），样例用来判断点列没跑出水域 */
  bridges: [
    { id: 'BRIDGE-CHAOYANG', lon: 115.852, lat: 28.646 },
    { id: 'BRIDGE-NANCHANG', lon: 115.859, lat: 28.663 },
    { id: 'BRIDGE-BAYI', lon: 115.879, lat: 28.692 }
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
