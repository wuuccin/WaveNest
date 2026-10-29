const timeoutMs = 8000
async function request(path, options = {}) {
  const controller = new AbortController()
  const signal = options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(path, { ...options, signal, headers: { 'Content-Type': 'application/json', ...options.headers } })
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}))
      const error = new Error(detail.detail || detail.message || `HTTP ${response.status}: ${path}`)
      error.status = response.status
      throw error
    }
    return response.status === 204 ? null : await response.json()
  } finally { clearTimeout(timer) }
}
export const api = {
  orders: () => request('/api/orders'), createOrder: (body, options = {}) => request('/api/orders', { ...options, method: 'POST', body: JSON.stringify(body) }), drones: () => request('/api/drones'), nests: () => request('/api/nests'), airspace: () => request('/api/airspace'),
  replay: taskId => request(`/api/replay/${encodeURIComponent(taskId)}`),
  intervene: body => request('/api/intervene', { method: 'POST', body: JSON.stringify(body) }),
  resetShip: (options = {}) => request('/sim/ship/reset', { ...options, method: 'POST' }),
  turnShip: (delta = 45, options = {}) => request(`/sim/ship/turn?delta=${encodeURIComponent(delta)}`, { ...options, method: 'POST' }),
  tasks: () => request('/api/monitor/tasks'),
  stats: () => request('/api/monitor/statistics')
}
