export const WAIT_REASONS = { WAIT_SHIP: '等待船舶进入服务航段', WAIT_CARGO: '等待货物就绪', GATE_OR_DATA: '闸门熔断或数据不足' }
export const ORDER_STATES = ['待受理', '已受理待唤醒', '待调度', '调度中', '已派发', '去程中', '末段交接中', '返航中', '已完成', '已拒绝', '失败', '异常终止']
export const TERMINAL_STATES = new Set(['已完成', '已拒绝', '失败', '异常终止'])
export function normalizeOrders(payload) {
  if (!Array.isArray(payload?.orders)) throw new Error('GET /api/orders 缺少 orders 数组')
  return payload.orders
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
