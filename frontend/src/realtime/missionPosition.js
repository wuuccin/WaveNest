/**
 * 规划航迹上的「任务位置」插值——让大屏飞机图标沿已画的计划航线移动。
 *
 * <p><b>为什么需要</b>：地图图标画的是无人机遥测帧坐标（`drone.byId`），而演示期遥测由
 * `SimUavTelemetryConnector` 生成，每架机绕自己机巢做 27.8 m 小圈巡航且不区分飞行阶段。
 * 于是订单状态从「去程中」走到「返航中」，图标始终贴在巢上——用户无法从图上判断飞机到哪了。
 *
 * <p><b>为什么能用计划航迹插值</b>：`plan_track` 的每个航点自带绝对到达时刻 `tMs`，
 * 由 `MissionTrackAssembler` 从 `plan.meetTime()` 反推起飞时刻后生成；订单阶段推进
 * `FlightPhaseTimeline.schedule(meetMs, ...)` 也以同一 `meetMs` 为根。两者时基同源，
 * 故用当前墙钟时间在航点列上插值，图标即可随订单阶段同步移动，无需新增契约帧或后端改动。
 *
 * <p><b>口径</b>：这是「按计划推算的任务位置」，不是遥测真值；偏航注入等真实位移仍由
 * 遥测/告警链路体现，本模块不伪造、不覆盖遥测数据，只提供一个可供地图选用的显示位置。
 */
const METERS_PER_DEG_LAT = 111_320.0

/** 经纬度线性插值所需的有限航点：必须有坐标与时刻。 */
function usable(point) {
  return point && Number.isFinite(point.lon) && Number.isFinite(point.lat) && Number.isFinite(point.tMs)
}

/** 由两点求航向（0＝正北，90＝正东），与遥测航向同口径。 */
function headingOf(from, to) {
  const east = (to.lon - from.lon) * Math.cos((from.lat + to.lat) * Math.PI / 360) * METERS_PER_DEG_LAT
  const north = (to.lat - from.lat) * METERS_PER_DEG_LAT
  const deg = Math.atan2(east, north) * 180 / Math.PI
  return deg < 0 ? deg + 360 : deg
}

function pointAt(from, to, ratio, nowMs) {
  return {
    lon: from.lon + (to.lon - from.lon) * ratio,
    lat: from.lat + (to.lat - from.lat) * ratio,
    headingDeg: headingOf(from, to),
    segmentKind: to.segmentKind || from.segmentKind || '',
    tMs: nowMs
  }
}

/**
 * 求当前时刻飞机在规划航迹上的显示位置。
 *
 * @param {object|null} track 归一后的 plan_track（`usePlanTrackStore.ofOrder(orderId)`）
 * @param {number} nowMs 当前墙钟毫秒
 * @returns {object|null} `{lon, lat, headingDeg, segmentKind, tMs}`；无可用航迹返回 null
 */
export function missionPosition(track, nowMs) {
  if (!track || !Array.isArray(track.segments) || !Number.isFinite(nowMs)) return null

  const points = []
  for (const segment of track.segments) {
    for (const point of segment?.points || []) {
      if (!usable(point)) continue
      points.push({ lon: point.lon, lat: point.lat, tMs: point.tMs, segmentKind: segment?.kind || '' })
    }
  }
  if (!points.length) return null
  // 段首在重派/转场时可能重复或交错，按绝对时刻排序后统一插值，不依赖数组顺序
  points.sort((a, b) => a.tMs - b.tMs)
  if (points.length === 1) return pointAt(points[0], points[0], 0, nowMs)
  if (nowMs <= points[0].tMs) return pointAt(points[0], points[1], 0, nowMs)
  const last = points.length - 1
  if (nowMs >= points[last].tMs) return pointAt(points[last - 1], points[last], 1, nowMs)

  for (let i = 0; i < last; i++) {
    const from = points[i], to = points[i + 1]
    if (nowMs < from.tMs || nowMs > to.tMs) continue
    const span = to.tMs - from.tMs
    return pointAt(from, to, span > 0 ? (nowMs - from.tMs) / span : 0, nowMs)
  }
  return null
}
