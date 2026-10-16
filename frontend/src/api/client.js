const timeoutMs = 8000
async function request(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(path, { ...options, signal: controller.signal, headers: { 'Content-Type': 'application/json', ...options.headers } })
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${path}`)
    return await response.json()
  } finally { clearTimeout(timer) }
}
export const api = {
  orders: () => request('/api/orders'), createOrder: body => request('/api/orders', { method: 'POST', body: JSON.stringify(body) }), drones: () => request('/api/drones'), nests: () => request('/api/nests'),
  replay: taskId => request(`/api/replay/${encodeURIComponent(taskId)}`),
  intervene: body => request('/api/intervene', { method: 'POST', body: JSON.stringify(body) }),
  demoNext: () => request('/api/demo/next', { method: 'POST' }), demoReset: () => request('/api/demo/reset', { method: 'POST' }),
  stats: () => request('/api/monitor/statistics')
}
