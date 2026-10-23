export const WAIT_REASONS = { WAIT_SHIP: '等待船舶进入服务航段', WAIT_CARGO: '等待货物就绪', GATE_OR_DATA: '闸门熔断或数据不足' }
export const ORDER_STATES = ['待受理', '已受理待唤醒', '待调度', '调度中', '已派发', '去程中', '末段交接中', '返航中', '已完成', '已拒绝', '失败', '异常终止']
export const TERMINAL_STATES = new Set(['已完成', '已拒绝', '失败', '异常终止'])
const enumValues = { PENDING_ACCEPT:'待受理', ACCEPTED_WAIT_WAKE:'已受理待唤醒', WAIT_DISPATCH:'待调度', DISPATCHING:'调度中', DISPATCHED:'已派发', OUTBOUND:'去程中', HANDOVER:'末段交接中', RETURNING:'返航中', FINISHED:'已完成', REJECTED:'已拒绝', FAILED:'失败', ABORTED:'异常终止', NORMAL:'正常', BROKEN:'熔断中', MANUAL:'人工冻结', AUTO:'自动熔断', LOST_LINK:'失联', LOW_BATTERY:'低电量', DEVIATION:'偏航', WEATHER_EXCEED:'气象超限', LINK_ERROR:'链路异常', INFO:'提示', WARN:'警告', CRITICAL:'严重', WARNING:'警告', SEVERE:'严重' }
export const finitePoint = point => Boolean(point) && Number.isFinite(point.lon) && Number.isFinite(point.lat)
export const displayEnum = value => enumValues[value] || value
export function normalizeOrder(data) { return { ...data, orderStatus: displayEnum(data.orderStatus) } }
export function normalizeFrame(type, data) {
  if (!data || typeof data !== 'object') throw new Error('实时帧缺少 data')
  if (type === 'order_state') return normalizeOrder(data)
  if (type === 'rendezvous') return { ...data, lon: data.meetLon ?? data.lon, lat: data.meetLat ?? data.lat }
  if (type === 'drone_state') return { ...data,
    segments: (data.segments || []).map(segment => ({ ...segment, points: (segment.points || []).filter(finitePoint) })).filter(segment => segment.points.length),
    flightPhase: data.phase ?? data.flightPhase, ts: data.ts ?? data.eventTs }
  if (type === 'ship_traj') {
    const points = (data.points || []).filter(finitePoint), first = points[0], last = points.at(-1)
    return { ...data, points, lon: Number.isFinite(data.lon) ? data.lon : first?.lon, lat: Number.isFinite(data.lat) ? data.lat : first?.lat,
      cov: last && Number.isFinite(last.sigmaAlong) && Number.isFinite(last.sigmaCross)
        ? { sigmaAlong: last.sigmaAlong, sigmaCross: last.sigmaCross } : data.cov }
  }
  if (type === 'alarm') return { ...data, level: displayEnum(data.alertLevel ?? data.level), alarmType: displayEnum(data.alertType ?? data.alarmType), targetId: data.target ?? data.targetId }
  return data
}
export function isRenderableFrame(type, data) {
  if (['drone_state', 'ship_traj', 'rendezvous'].includes(type)) return finitePoint(data)
  return true
}
export function normalizeOrders(payload) {
  if (!Array.isArray(payload?.orders)) throw new Error('GET /api/orders 缺少 orders 数组')
  return payload.orders.map(normalizeOrder)
}
export function waitText(order) {
  if (!Object.hasOwn(order, 'waitReason') || order.waitReason === '') return '等待原因字段异常'
  if (order.waitReason === null) return '等待原因暂不可用'
  return WAIT_REASONS[order.waitReason] || '等待原因字段异常'
}
export function wakeText(order) {
  if (order.wakeEtaTs == null) return '唤醒时刻待更新'
  if (order.wakeEtaTs < Date.now()) return '等待调度更新'
  return new Date(order.wakeEtaTs).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
