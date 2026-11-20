import { PATHS } from './prediction.js'

const timeoutMs = 8000
async function request(path, options = {}) {
  const controller = new AbortController()
  const signal = options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(path, { ...options, signal, headers: { 'Content-Type': 'application/json', ...options.headers } })
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}))
      // 气象注入端点的 400 体是 {action, error}（见 WeatherInjectController），
      // 不取 error 字段会把「阵风不得低于风速」这类可读原因降级成裸 HTTP 400
      const error = new Error(detail.detail || detail.message || detail.error || `HTTP ${response.status}: ${path}`)
      error.status = response.status
      throw error
    }
    return response.status === 204 ? null : await response.json()
  } finally { clearTimeout(timer) }
}
export const api = {
  orders: () => request('/api/orders'), createOrder: (body, options = {}) => request('/api/orders', { ...options, method: 'POST', body: JSON.stringify(body) }), drones: () => request('/api/drones'), nests: () => request('/api/nests'), airspace: () => request('/api/airspace'),
  cargoTypes: (options = {}) => request('/api/cargo-types', { ...options }),
  // 货物到巢登记：必须带货物实际到达的巢编号，后端与订单货巢比对一致后才放行
  cargoReady: (orderId, cargoNestId) => request(`/api/orders/${encodeURIComponent(orderId)}/cargo-ready`, { method: 'POST', body: JSON.stringify({ cargoNestId }) }),
  releaseResource: orderId => request(`/api/orders/${encodeURIComponent(orderId)}/resource-release`, { method: 'POST' }),
  candidateHistory: orderId => request(`/api/orders/${encodeURIComponent(orderId)}/candidates`),
  confirmRecycle: ({ orderId, goodsId, uavId, nestId }) => request(`/sim/recycle?${new URLSearchParams({ orderId, goodsId, uavId, nestId })}`, { method: 'POST' }),
  confirmDelivery: ({ orderId, uavId }) => request(`/sim/delivery?${new URLSearchParams({ taskId: orderId, orderId, uavId })}`, { method: 'POST' }),
  replay: taskId => request(`/api/replay/${encodeURIComponent(taskId)}`),
  intervene: body => request('/api/intervene', { method: 'POST', body: JSON.stringify(body) }),
  resetShip: (options = {}) => request('/sim/ship/reset', { ...options, method: 'POST' }),
  turnShip: (delta = 45, options = {}) => request(`/sim/ship/turn?delta=${encodeURIComponent(delta)}`, { ...options, method: 'POST' }),
  injectWeather: ({ windSpeedMs, windDirDeg = 135, gustMs, persist = true }, options = {}) => {
    const params = new URLSearchParams({ wind: String(windSpeedMs), dir: String(windDirDeg), persist: String(persist) })
    if (gustMs != null) params.set('gust', String(gustMs))
    return request(`/sim/weather/inject?${params}`, { ...options, method: 'POST' })
  },
  // 当前海况（注入偏置、注入计数、覆盖网格点）：标尺与注入面板回显都读它，不各自猜状态
  weatherStatus: (options = {}) => request('/sim/weather', options),
  // 复位：清除注入偏置，自产风回到基准海况（演示结束后必做，否则下一场不可复现）
  clearWeatherBias: (options = {}) => request('/sim/weather/bias', { ...options, method: 'DELETE' }),
  resetWeather: (options = {}) => request('/sim/weather/bias', { ...options, method: 'DELETE' }),
  uavInjectionStatus: () => request('/sim/uav'),
  injectUavOfftrack: ({ uavId, deviationM, bearingDeg }) => request(`/sim/uav/offtrack?${new URLSearchParams({ uavId, deviationM: String(deviationM), bearingDeg: String(bearingDeg) })}`, { method: 'POST' }),
  injectUavBattery: ({ uavId, percent }) => request(`/sim/uav/battery?${new URLSearchParams({ uavId, percent: String(percent) })}`, { method: 'POST' }),
  clearUavInjections: (uavId) => request(`/sim/uav/injections${uavId ? `?${new URLSearchParams({ uavId })}` : ''}`, { method: 'DELETE' }),
  tasks: () => request('/api/monitor/tasks'),
  records: taskId => request(`/api/monitor/tasks/${encodeURIComponent(taskId)}/records`),
  stats: () => request('/api/monitor/statistics'),
  // 预测看台（阶段二 1）：路径统一收敛到 api/prediction.js 的 PATHS，
  // 这里只做薄封装——契约一改只动那张表，不在这里留第二份字面量。
  predictionList: (signal) => request(PATHS.list, { ...(signal ? { signal } : {}) }),
  predictionDetail: (mmsi, signal) => request(PATHS.detail(mmsi), { ...(signal ? { signal } : {}) })
}
