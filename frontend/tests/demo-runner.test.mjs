import assert from 'node:assert/strict'
import test from 'node:test'
import { createDemoRunner } from '../src/realtime/demo.js'

function harness(timeoutMs = 100) {
  const listeners = new Set()
  const emit = (type, data) => {
    for (const listener of listeners) listener({ type, data })
  }
  let injections = 0
  let clears = 0
  let gateStatus = 'BROKEN'
  const api = {
    resetShip: async () => ({}),
    createOrder: async () => ({ accepted: true, orderId: 'O1' }),
    injectWeather: async () => { injections += 1; return {} },
    clearWeatherBias: async () => { clears += 1; return {} },
    orders: async () => ({ dispatchGate: { gateStatus } })
  }
  const runner = createDemoRunner({ api, dwellMs: { 1: 0, 3: 0, 5: 0 }, subscribe: listener => {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }, connected: () => true, timeoutMs })
  const start = async () => {
    const run = runner.run()
    await new Promise(resolve => setTimeout(resolve, 0))
    emit('rendezvous', { orderId: 'O1' })
    emit('drone_state', { uavId: 'U1', activeOrderId: 'O1', flightPhase: '执行任务中' })
    emit('order_state', { orderId: 'O1', orderStatus: '去程中' })
    await new Promise(resolve => setTimeout(resolve, 0))
    return { run }
  }
  return { emit, runner, start, setGateStatus: value => { gateStatus = value }, get injections() { return injections }, get clears() { return clears } }
}

test('气象超限事件推动无人机返航演示阶段', async () => {
  const h = harness()
  const { run } = await h.start()
  assert.equal(h.injections, 1)
  h.emit('alarm', { orderId: 'O1', targetId: 'U1', alarmType: '气象超限' })
  h.emit('drone_state', { uavId: 'U1', activeOrderId: 'O1', flightPhase: '返航中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '去程中' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.ok(h.runner.state.step >= 4)
  assert.equal(h.clears, 1)
  h.runner.cancel()
  await run
})

test('风况回落后确认闸门解除，再等待重派交接', async () => {
  const h = harness(100)
  const { run } = await h.start()
  h.emit('alarm', { orderId: 'O1', targetId: 'U1', alarmType: '气象超限' })
  h.emit('drone_state', { uavId: 'U1', activeOrderId: 'O1', flightPhase: '返航中' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.clears, 1)
  h.emit('weather', { windSpeedMs: 5 })
  h.emit('weather', { windSpeedMs: 5 })
  h.emit('weather', { windSpeedMs: 5 })
  h.setGateStatus('NORMAL')
  await new Promise(resolve => setTimeout(resolve, 1100))
  assert.equal(h.runner.state.step, 6)
  h.runner.cancel()
  await run
})

test('订单返航状态不能替代无人机返航状态', async () => {
  const h = harness(30)
  const { run } = await h.start()
  h.emit('alarm', { orderId: 'O1', alarmType: '气象超限' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '返航中' })
  await run
  assert.equal(h.runner.state.step, 3)
  assert.match(h.runner.state.error, /第 3 段.*超时/)
})

test('缺少气象超限事件不能通过演示阶段', async () => {
  const h = harness(30)
  const { run } = await h.start()
  h.emit('drone_state', { uavId: 'U1', activeOrderId: 'O1', flightPhase: '返航中' })
  await run
  assert.equal(h.runner.state.step, 3)
  assert.match(h.runner.state.error, /第 3 段.*超时/)
})

test('回收和投放必须由页面按钮确认，天气回落必须连续三帧', async () => {
  const h = harness(3000)
  const { run } = await h.start()
  h.emit('alarm', { orderId: 'O1', targetId: 'U1', alarmType: '气象超限' })
  h.emit('drone_state', { uavId: 'U1', activeOrderId: 'O1', flightPhase: '返航中' })
  await new Promise(resolve => setTimeout(resolve, 0))
  h.emit('weather', { windSpeedMs: 5 })
  h.emit('weather', { windSpeedMs: 9 })
  h.emit('weather', { windSpeedMs: 5 })
  h.emit('weather', { windSpeedMs: 5 })
  h.setGateStatus('NORMAL')
  await new Promise(resolve => setTimeout(resolve, 1100))
  assert.equal(h.runner.state.step, 5)
  h.emit('weather', { windSpeedMs: 5 })
  await new Promise(resolve => setTimeout(resolve, 1100))
  assert.equal(h.runner.state.step, 6)
  h.emit('order_state', { orderId: 'O1', orderStatus: '待调度' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '已派发' })
  h.emit('drone_state', { uavId: 'U2', activeOrderId: 'O1', flightPhase: '去程中' })
  assert.equal(h.runner.state.step, 6)
  h.runner.confirmRecycle()
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.runner.state.step, 7)
  h.emit('order_state', { orderId: 'O1', orderStatus: '末段交接中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '已完成' })
  assert.equal(h.runner.state.completed, false)
  h.runner.confirmDelivery()
  await run
  assert.equal(h.runner.state.completed, true)
})
