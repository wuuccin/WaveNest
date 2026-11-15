import { reactive } from 'vue'

export const DEMO_STAGES = ['下单与预计会面', '首架无人机去程', '等待气象超限事件与无人机返航状态', '演示落巢回收并重入队', '演示重派并与船舶交接']

// Only real backend frames confirm progress; missing transitions stop the runner.
export function createDemoRunner({ api, subscribe, connected, timeoutMs = 30000 }) {
  const state = reactive({ running: false, step: 0, completed: false, error: '', orderId: null })
  let controller
  function cancel() { controller?.abort(); controller = undefined; state.running = false }
  async function run() {
    if (state.running) return
    if (!connected()) { state.error = '请先连接实时服务'; return }
    const runController = new AbortController()
    controller = runController
    const signal = runController.signal
    Object.assign(state, { running: true, step: 0, completed: false, error: '', orderId: null })
    const seen = { meet: false, outbound: false, firstDrone: null, original: null, weatherExceeded: false, returning: false, requeued: false, replacement: false, handover: false, completed: false }
    const pending = []
    let wake = () => {}
    function observe(frame) {
      const d = frame.data || {}
      if (frame.type === 'order_state' && d.orderId === state.orderId) {
        if (d.orderStatus === '去程中') { seen.outbound = true; seen.original ||= seen.firstDrone }
        if (d.orderStatus === '待调度' && seen.returning) seen.requeued = true
        if (d.orderStatus === '末段交接中' && seen.requeued) seen.handover = true
        if (d.orderStatus === '已完成' && seen.handover) seen.completed = true
      }
      if (frame.type === 'rendezvous' && d.orderId === state.orderId) seen.meet = true
      if (frame.type === 'alarm' && (d.orderId === state.orderId || (seen.original && (d.targetId === seen.original || d.target === seen.original)))
        && (d.alarmType === '气象超限' || d.alertType === '气象超限')) seen.weatherExceeded = true
      if (frame.type === 'drone_state') {
        if (!seen.firstDrone && !seen.requeued && (d.activeOrderId === state.orderId || d.orderId === state.orderId)) seen.firstDrone = d.uavId
        if (!seen.original && seen.outbound && (d.activeOrderId === state.orderId || d.orderId === state.orderId)) seen.original = d.uavId
        if (seen.original && d.uavId === seen.original && ['返航中', '返航'].includes(d.flightPhase || d.phase)) seen.returning = true
        if (seen.requeued && seen.original && d.uavId && d.uavId !== seen.original && (d.activeOrderId === state.orderId || d.orderId === state.orderId)) seen.replacement = true
      }
      wake()
    }
    const unsubscribe = subscribe(frame => {
      if (!state.orderId) pending.push(frame)
      else observe(frame)
    })
    function waitFor(check, stage) {
      if (check()) return Promise.resolve()
      return new Promise((resolve, reject) => {
        const cleanup = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); wake = () => {} }
        const abort = () => { cleanup(); reject(new Error('演示已停止')) }
        const timer = setTimeout(() => { cleanup(); reject(new Error(`第 ${stage} 段等待后端确认超时：${DEMO_STAGES[stage - 1]}`)) }, timeoutMs)
        wake = () => { if (check()) { cleanup(); resolve() } }
        signal.addEventListener('abort', abort, { once: true })
        if (signal.aborted) abort()
      })
    }
    try {
      await api.resetShip({ signal })
      if (signal.aborted) return
      const created = await api.createOrder({ shipMmsi: import.meta.env?.VITE_SIM_SHIP_MMSI || '123456789', goodsWeight: 2, goodsType: '应急药品', priority: 'NORMAL' }, { signal })
      if (signal.aborted) return
      if (!created.accepted || !created.orderId) throw new Error(created.message || '后端未接收演示订单')
      state.orderId = created.orderId
      for (const frame of pending) observe(frame)
      state.step = 1
      await waitFor(() => seen.meet, 1)
      state.step = 2
      await waitFor(() => seen.outbound && !!seen.original, 2)
      state.step = 3
      await api.injectWeather({ windSpeedMs: 16 }, { signal })
      await waitFor(() => seen.weatherExceeded && seen.returning, 3)
      state.step = 4
      await waitFor(() => seen.requeued, 4)
      state.step = 5
      await waitFor(() => seen.replacement && seen.handover && seen.completed, 5)
      state.completed = true
    } catch (error) { if (!signal.aborted) state.error = error.message }
    finally {
      unsubscribe()
      if (controller === runController) { controller = undefined; state.running = false }
    }
  }
  return { state, run, cancel }
}
