import { reactive } from 'vue'

export const DEMO_STAGES = ['下单与会面预测', '无人机起飞', '船舶变向', '原机返航', '货物回收与重入队', '重新派机并完成交接']

// Only real backend frames confirm progress; missing transitions stop the runner.
export function createDemoRunner({ api, subscribe, connected, timeoutMs = 30000 }) {
  const state = reactive({ running: false, step: 0, error: '', orderId: null })
  let controller
  function cancel() { controller?.abort(); controller = undefined; state.running = false }
  async function run() {
    if (state.running) return
    if (!connected()) { state.error = '请先连接实时服务'; return }
    const runController = new AbortController()
    controller = runController
    const signal = runController.signal
    Object.assign(state, { running: true, step: 0, error: '', orderId: null })
    const seen = { meet: false, outbound: false, shipVersion: null, turned: false, firstDrone: null, original: null, returning: false, requeued: false, replacement: false, handover: false, finalReturn: false, completed: false }
    const pending = []
    let wake = () => {}
    function observe(frame) {
      const d = frame.data
      if (frame.type === 'order_state' && d.orderId === state.orderId) {
        if (d.orderStatus === '去程中') { seen.outbound = true; seen.original ||= seen.firstDrone }
        if (d.orderStatus === '返航中' && seen.turned) seen.returning = true
        if (d.orderStatus === '待调度' && seen.returning) seen.requeued = true
        if (d.orderStatus === '末段交接中' && seen.requeued) seen.handover = true
        if (d.orderStatus === '返航中' && seen.handover) seen.finalReturn = true
        if (d.orderStatus === '已完成' && seen.handover && seen.finalReturn) seen.completed = true
      }
      if (frame.type === 'rendezvous' && d.orderId === state.orderId) seen.meet = true
      if (frame.type === 'ship_traj' && Number.isFinite(d.version)) {
        if (seen.shipVersion == null || !seen.outbound) seen.shipVersion = d.version
        else if (d.version > seen.shipVersion) seen.turned = true
      }
      if (frame.type === 'drone_state') {
        if (!seen.firstDrone && !seen.requeued) seen.firstDrone = d.uavId
        if (!seen.original && seen.outbound) seen.original = d.uavId
        if (seen.requeued && seen.original && d.uavId && d.uavId !== seen.original && (!d.orderId || d.orderId === state.orderId)) seen.replacement = true
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
      const checks = [() => seen.meet, () => seen.outbound && !!seen.original, () => seen.turned,
        () => seen.returning, () => seen.requeued, () => seen.replacement && seen.handover && seen.finalReturn && seen.completed]
      for (let i = 0; i < checks.length; i++) {
        state.step = i + 1
        if (i === 2) await api.turnShip(45, { signal })
        await waitFor(checks[i], i + 1)
      }
    } catch (error) { if (!signal.aborted) state.error = error.message }
    finally {
      unsubscribe()
      if (controller === runController) { controller = undefined; state.running = false }
    }
  }
  return { state, run, cancel }
}
