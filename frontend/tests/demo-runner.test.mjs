import assert from 'node:assert/strict'
import test from 'node:test'
import { createDemoRunner } from '../src/realtime/demo.js'

function harness(timeoutMs = 100) {
  const listeners = new Set()
  const emit = (type, data) => {
    for (const listener of listeners) listener({ type, data })
  }
  let injections = 0
  const api = {
    resetShip: async () => ({}),
    createOrder: async () => ({ accepted: true, orderId: 'O1' }),
    injectWeather: async () => { injections += 1; return {} }
  }
  const runner = createDemoRunner({ api, subscribe: listener => {
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
  return { emit, runner, start, get injections() { return injections } }
}

test('气象超限事件推动无人机返航演示阶段', async () => {
  const h = harness()
  const { run } = await h.start()
  assert.equal(h.injections, 1)
  h.emit('alarm', { orderId: 'O1', targetId: 'U1', alarmType: '气象超限' })
  h.emit('drone_state', { uavId: 'U1', activeOrderId: 'O1', flightPhase: '返航中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '去程中' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.runner.state.step, 4)
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
