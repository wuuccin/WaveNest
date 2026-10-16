import { api } from '../api/client'
import { reactive } from 'vue'
import { normalizeOrders } from '../api/adapter'
import { SCENES } from './mock'
import { useShipStore, useDroneStore, useAlertStore, useEventStore, useOrderStore } from '../stores'

export function createRealtime() {
  const ship = useShipStore(), drone = useDroneStore(), alert = useAlertStore(), events = useEventStore(), orders = useOrderStore()
  let ws, sse, poll, retry, scriptTimer, generation = 0, buffered = [], priming = false, mockStep = 0
  const status = reactive({ mode: 'mock', connection: '离线演示', error: '' })
  const emit = (type, data) => {
    if (priming) { buffered.push({ type, data }); return }
    if (orders.replaying) return
    if (type === 'ship_traj') ship.apply(data)
    else if (type === 'drone_state') drone.apply(data)
    else if (type === 'rendezvous') orders.rendezvous = data
    else if (type === 'alarm') alert.apply(data)
    else if (type === 'order_state') orders.apply(data)
    else return
    if (type === 'alarm' || type === 'order_state' || type === 'rendezvous') events.add(type, data)
  }
  const close = () => { generation++; ws?.close(); sse?.close(); clearInterval(poll); clearTimeout(retry); clearInterval(scriptTimer); ws = null; sse = null; poll = null; retry = null; scriptTimer = null }
  const snapshot = async () => { const data = await api.orders(); orders.snapshot({ ...data, orders: normalizeOrders(data) }); return data }
  async function prime() {
    if (!priming) { priming = true; buffered = [] }
    let restored = []
    try {
      const data = await snapshot()
      const orderId = data.orders?.[0]?.orderId
      if (orderId) {
        try {
          const history = await api.replay(orderId)
          const latest = new Map()
          for (const frame of history.frames || []) {
            if (['ship_traj', 'drone_state', 'rendezvous', 'alarm'].includes(frame.type)) latest.set(frame.type, frame)
          }
          restored = [...latest.values()]
        } catch { /* A live source may not expose historical frames. */ }
      }
    } finally {
      priming = false
      const saved = buffered; buffered = []
      for (const frame of [...restored, ...saved]) emit(frame.type, frame.data)
    }
  }
  function fallback(g) {
    if (g !== generation) return
    if (import.meta.env.VITE_SSE_URL) {
      try {
        sse = new EventSource(import.meta.env.VITE_SSE_URL)
        sse.onmessage = ev => { try { const frame = JSON.parse(ev.data); emit(frame.type, frame.data) } catch { status.error = 'SSE 帧格式异常' } }
        sse.onopen = () => { status.connection = 'SSE 已连接'; prime().catch(e => status.error = e.message) }
        sse.onerror = () => { sse?.close(); sse = null; startPolling(g) }
        return
      } catch { /* REST remains available. */ }
    }
    startPolling(g)
  }
  function startPolling(g) {
    if (g !== generation) return
    status.connection = 'REST 轮询中'
    clearInterval(poll)
    const tick = () => snapshot().catch(e => status.error = e.message)
    tick(); poll = setInterval(tick, 5000)
    retry = setTimeout(() => { if (g === generation) connect() }, 15000)
  }
  function connect() {
    close(); status.mode = 'live'; status.connection = '连接中'; status.error = ''
    const g = generation
    priming = true; buffered = []
    try {
      ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`)
      ws.onmessage = ev => { try { const frame = JSON.parse(ev.data); emit(frame.type, frame.data) } catch { status.error = 'WS 帧格式异常' } }
      ws.onopen = async () => { if (g !== generation) return; status.connection = 'WebSocket 已连接'; try { await prime() } catch (e) { status.error = e.message } }
      ws.onclose = () => fallback(g)
      ws.onerror = () => { status.error = 'WebSocket 连接失败'; ws?.close() }
    } catch { fallback(g) }
  }
  function resetMock() {
    close(); status.mode = 'mock'; status.connection = '离线演示'; status.error = ''; mockStep = 0
    orders.reset(); alert.clear(); events.clear(); ship.apply(null); drone.apply(null)
    SCENES[0].frames.forEach(([type, data]) => emit(type, data))
  }
  function nextMock() {
    if (mockStep >= SCENES.length - 1) return mockStep
    mockStep++
    SCENES[mockStep].frames.forEach(([type, data]) => emit(type, data))
    return mockStep
  }
  function autoMock(onStep) {
    resetMock(); onStep?.(0)
    scriptTimer = setInterval(() => {
      const step = nextMock(); onStep?.(step)
      if (step === SCENES.length - 1) { clearInterval(scriptTimer); scriptTimer = null }
    }, 2200)
  }
  return { status, emit, connect, resetMock, nextMock, autoMock, close, get mockStep() { return mockStep } }
}
