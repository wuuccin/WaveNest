import { api } from '../api/client'
import { reactive } from 'vue'
import { normalizeOrders, normalizeFrame } from '../api/adapter'
import { SCENES } from './mock'
import { useShipStore, useDroneStore, useAlertStore, useEventStore, useOrderStore } from '../stores'

export function createRealtime() {
  const ship = useShipStore(), drone = useDroneStore(), alert = useAlertStore(), events = useEventStore(), orders = useOrderStore()
  let ws, sse, poll, retry, scriptTimer, generation = 0, buffered = [], priming = false, mockStep = 0
  const status = reactive({ mode: 'live', connection: '未连接', error: '' })
  const listeners = new Set(), recording = []
  const subscribe = fn => { listeners.add(fn); return () => listeners.delete(fn) }
  const emit = (type, data, replay = false) => {
    if (priming && !replay) { buffered.push({ type, data }); return }
    if (orders.replaying && !replay) return
    data = normalizeFrame(type, data)
    if (type === 'ship_traj') ship.apply(data)
    else if (type === 'drone_state') drone.apply(data)
    else if (type === 'rendezvous') orders.rendezvous = data
    else if (type === 'alarm') alert.apply(data)
    else if (type === 'order_state') { if (!orders.apply(data)) return }
    else return
    if (!replay && status.mode === 'live') { const frame = { type, data, receivedAt: Date.now() }; recording.push(frame); for (const fn of listeners) fn(frame) }
    if (type === 'alarm' || type === 'order_state' || type === 'rendezvous') events.add(type, data)
  }
  const close = () => { generation++; priming = false; buffered = []; ws?.close(); sse?.close(); clearInterval(poll); clearTimeout(retry); clearInterval(scriptTimer); ws = null; sse = null; poll = null; retry = null; scriptTimer = null }
  const snapshot = async () => { const g = generation; const data = await api.orders(); if (g !== generation || orders.replaying) return data; orders.snapshot({ ...data, orders: normalizeOrders(data) }); return data }
  async function prime() {
    const g = generation
    if (!priming) { priming = true; buffered = [] }
    let restored = []
    try {
      const data = await snapshot()
      const orderId = data.orders?.at(-1)?.orderId
      const tasks = orderId ? await api.tasks().catch(() => ({ tasks: [] })) : { tasks: [] }
      const taskId = tasks.tasks?.find(task => task.orderId === orderId)?.taskId
      if (taskId) {
        try {
          const history = await api.replay(taskId)
          const latest = new Map()
          for (const frame of history.frames || []) {
            if (['ship_traj', 'drone_state', 'rendezvous', 'alarm'].includes(frame.type)) latest.set(frame.type, frame)
          }
          restored = [...latest.values()]
        } catch { /* A live source may not expose historical frames. */ }
      }
    } finally {
      if (g !== generation) return
      priming = false
      const saved = buffered; buffered = []
      for (const frame of restored) emit(frame.type, frame.data, true)
      for (const frame of saved) emit(frame.type, frame.data)
    }
  }
  function fallback(g) {
    if (g !== generation) return
    if (import.meta.env.VITE_SSE_URL) {
      try {
        sse = new EventSource(import.meta.env.VITE_SSE_URL)
        sse.onmessage = ev => { if (g !== generation) return; try { const frame = JSON.parse(ev.data); emit(frame.type, frame.data) } catch { status.error = 'SSE 帧格式异常' } }
        sse.onopen = () => { if (g !== generation) return; status.connection = 'SSE 已连接'; prime().catch(e => status.error = e.message) }
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
      ws.onmessage = ev => { if (g !== generation) return; try { const frame = JSON.parse(ev.data); emit(frame.type, frame.data) } catch { status.error = 'WS 帧格式异常' } }
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
  return { status, emit, subscribe, recording, connect, refresh: snapshot, resetMock, nextMock, autoMock, close, get mockStep() { return mockStep } }
}
