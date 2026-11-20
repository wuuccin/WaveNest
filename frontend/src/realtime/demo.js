import { reactive } from 'vue'
import { DEMO_DEFAULT_CARGO_TYPE } from '../api/cargoTypes.js'

export const DEMO_STAGES = ['下单与预计会面', '首架无人机去程', '等待气象超限事件与无人机返航状态', '清除气象注入偏置', '等待风况回落与熔断解除', '等待订单重派', '等待新机交接完成']

// Only real backend frames confirm progress; missing transitions stop the runner.
// 第 1 段等的是「船员把货物送到机巢、投放闸门放行」，这一段的长短由人来定，不能用通用超时卡住，
// 因此单独给 cargoWaitMs（贴合实际按 25 分钟计），其余阶段仍按 timeoutMs 判超时。
export function createDemoRunner({ api, subscribe, connected, timeoutMs = 30000, cargoWaitMs = 25 * 60 * 1000, dwellMs = { 1: 2500, 3: 3000, 5: 2500 } }) {
  const state = reactive({ running: false, step: 0, completed: false, error: '', orderId: null, originalDroneId: null, replacementDroneId: null, recycleConfirmed: false, deliveryConfirmed: false })
  let controller
  let wake = () => {}
  function cancel() { controller?.abort(); controller = undefined; state.running = false }
  async function run() {
    if (state.running) return
    if (!connected()) { state.error = '请先连接实时服务'; return }
    const runController = new AbortController()
    controller = runController
    const signal = runController.signal
    Object.assign(state, { running: true, step: 0, completed: false, error: '', orderId: null, originalDroneId: null, replacementDroneId: null, recycleConfirmed: false, deliveryConfirmed: false })
    const seen = { meet: false, outbound: false, firstDrone: null, original: null, weatherExceeded: false, returning: false, requeued: false, recoveredFrames: 0, weatherRecovered: false, gateWasBroken: false, gateCleared: false, replacement: false, handover: false, completed: false }
    const pending = []
    function observe(frame) {
      const d = frame.data || {}
      if (frame.type === 'order_state' && d.orderId === state.orderId) {
        if (d.orderStatus === '去程中') { seen.outbound = true; seen.original ||= seen.firstDrone }
        if (d.orderStatus === '待调度' && seen.returning) seen.requeued = true
        if (seen.requeued && ['已派发', '去程中'].includes(d.orderStatus)) seen.redispatched = true
        if (d.orderStatus === '末段交接中' && seen.requeued) seen.handover = true
        if (d.orderStatus === '已完成' && seen.handover) seen.completed = true
      }
      if (frame.type === 'rendezvous' && d.orderId === state.orderId) seen.meet = true
      if (frame.type === 'weather' && seen.returning && Number.isFinite(Number(d.windSpeedMs))) {
        seen.recoveredFrames = Number(d.windSpeedMs) < 8 ? seen.recoveredFrames + 1 : 0
        seen.weatherRecovered = seen.recoveredFrames >= 3
      }
      if (frame.type === 'alarm' && (d.orderId === state.orderId || (seen.original && (d.targetId === seen.original || d.target === seen.original)))
        && (d.alarmType === '气象超限' || d.alertType === '气象超限')) seen.weatherExceeded = true
      if (frame.type === 'drone_state') {
        if (!seen.firstDrone && !seen.requeued && (d.activeOrderId === state.orderId || d.orderId === state.orderId)) seen.firstDrone = d.uavId
        if (!seen.original && seen.outbound && (d.activeOrderId === state.orderId || d.orderId === state.orderId)) seen.original = d.uavId
        if (seen.original && d.uavId === seen.original && ['返航中', '返航'].includes(d.flightPhase || d.phase)) { seen.returning = true; state.originalDroneId = seen.original }
        if (seen.requeued && seen.original && d.uavId && d.uavId !== seen.original && (d.activeOrderId === state.orderId || d.orderId === state.orderId)) { seen.replacement = true; state.replacementDroneId = d.uavId }
      }
      wake()
    }
    const unsubscribe = subscribe(frame => {
      if (!state.orderId) pending.push(frame)
      else observe(frame)
    })
    function waitFor(check, stage, minimumOverride, maxWaitMs = timeoutMs) {
      const started = Date.now()
      const minimum = minimumOverride ?? dwellMs[stage] ?? 0
      return new Promise((resolve, reject) => {
        const cleanup = () => { clearTimeout(timer); clearTimeout(dwellTimer); signal.removeEventListener('abort', abort); wake = () => {} }
        const abort = () => { cleanup(); reject(new Error('演示已停止')) }
        const timer = maxWaitMs == null ? null : setTimeout(() => { cleanup(); reject(new Error(`第 ${stage} 段等待后端确认超时：${DEMO_STAGES[stage - 1]}`)) }, maxWaitMs)
        const dwellTimer = setTimeout(() => wake(), minimum)
        wake = () => { if (check() && Date.now() - started >= minimum) { cleanup(); resolve() } }
        signal.addEventListener('abort', abort, { once: true })
        if (signal.aborted) abort()
        else wake()
      })
    }
    try {
      await api.resetShip({ signal })
      if (signal.aborted) return
      const created = await api.createOrder({ shipMmsi: import.meta.env?.VITE_SIM_SHIP_MMSI || '123456789', goodsWeight: 2, goodsType: DEMO_DEFAULT_CARGO_TYPE, priority: 'NORMAL' }, { signal })
      if (signal.aborted) return
      if (!created.accepted || !created.orderId) throw new Error(created.message || '后端未接收演示订单')
      state.orderId = created.orderId
      for (const frame of pending) observe(frame)
      state.step = 1
      await waitFor(() => seen.meet, 1, undefined, cargoWaitMs)
      state.step = 2
      await waitFor(() => seen.outbound && !!seen.original, 2)
      state.step = 3
      await waitFor(() => true, 3)
      await api.injectWeather({ windSpeedMs: 16 }, { signal })
      await waitFor(() => seen.weatherExceeded && seen.returning, 3, 0)
      state.step = 4
      const broken = await api.orders()
      seen.gateWasBroken = ['熔断中', 'BROKEN'].includes(broken.dispatchGate?.gateStatus)
      if (!seen.gateWasBroken) throw new Error('未观察到派单闸门熔断，不能证明熔断解除')
      await api.clearWeatherBias({ signal })
      state.step = 5
      seen.recoveredFrames = 0
      seen.weatherRecovered = false
      const recoveryStarted = Date.now()
      const deadline = recoveryStarted + 25000
      let fallbackInjected = false
      while (Date.now() < deadline && !signal.aborted) {
        const snapshot = await api.orders()
        if (seen.gateWasBroken && ['正常', 'NORMAL'].includes(snapshot.dispatchGate?.gateStatus)) seen.gateCleared = true
        if (seen.weatherRecovered && seen.gateCleared && Date.now() - recoveryStarted >= (dwellMs[5] || 0)) break
        if (!fallbackInjected && Date.now() - recoveryStarted >= 15000 && !seen.weatherRecovered) {
          await api.injectWeather({ windSpeedMs: 4 }, { signal })
          fallbackInjected = true
        }
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
      if (signal.aborted) return
      if (!seen.weatherRecovered || !seen.gateCleared) throw new Error('风况连续三帧回落或闸门解除在 25 秒内未获后端确认')
      state.step = 6
      await waitFor(() => state.recycleConfirmed, 6, 0, null)
      await waitFor(() => seen.requeued && seen.redispatched && seen.replacement, 6)
      state.step = 7
      await waitFor(() => state.deliveryConfirmed, 7, 0, null)
      await waitFor(() => seen.handover && seen.completed, 7)
      state.completed = true
    } catch (error) { if (!signal.aborted) state.error = error.message }
    finally {
      unsubscribe()
      if (controller === runController) { controller = undefined; state.running = false }
    }
  }
  return {
    state, run, cancel,
    confirmRecycle() { if (state.running && state.step === 6) { state.recycleConfirmed = true; wake() } },
    confirmDelivery() { if (state.running && state.step === 7) { state.deliveryConfirmed = true; wake() } }
  }
}
