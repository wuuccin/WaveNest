import assert from 'node:assert/strict'
import test from 'node:test'
import { createDemoRunner } from '../src/realtime/demo.js'

function harness(timeoutMs = 100) {
  const listeners = new Set()
  const sent = []
  const emit = (type, data) => {
    const frame = { type, data }
    sent.push(frame)
    for (const listener of listeners) listener(frame)
  }
  let turns = 0
  const api = {
    resetShip: async () => ({}),
    createOrder: async () => ({ accepted: true, orderId: 'O1' }),
    turnShip: async () => { turns++; emit('ship_traj', { version: 2 }); return {} }
  }
  const runner = createDemoRunner({ api, subscribe: listener => {
    listeners.add(listener)
    return () => listeners.delete(listener)
  }, connected: () => true, timeoutMs })
  return { emit, runner, sent, get turns() { return turns } }
}

test('live one-click runner waits for all six verified stages', async () => {
  const h = harness()
  const run = h.runner.run()
  await new Promise(resolve => setTimeout(resolve, 0))
  h.emit('ship_traj', { version: 1 })
  h.emit('rendezvous', { orderId: 'O1' })
  h.emit('drone_state', { uavId: 'U1' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '去程中' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(h.turns, 1)
  h.emit('order_state', { orderId: 'O1', orderStatus: '返航中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '待调度' })
  h.emit('drone_state', { uavId: 'U2' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '末段交接中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '返航中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '已完成' })
  await run
  assert.equal(h.runner.state.step, 6)
  assert.equal(h.runner.state.error, '')
  assert.equal(h.runner.state.running, false)
})

test('runner does not claim success without a replacement drone', async () => {
  const h = harness(30)
  const run = h.runner.run()
  await new Promise(resolve => setTimeout(resolve, 0))
  h.emit('ship_traj', { version: 1 })
  h.emit('rendezvous', { orderId: 'O1' })
  h.emit('drone_state', { uavId: 'U1' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '去程中' })
  await new Promise(resolve => setTimeout(resolve, 0))
  h.emit('order_state', { orderId: 'O1', orderStatus: '返航中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '待调度' })
  h.emit('drone_state', { uavId: 'U1' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '末段交接中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '返航中' })
  h.emit('order_state', { orderId: 'O1', orderStatus: '已完成' })
  await run
  assert.equal(h.runner.state.step, 6)
  assert.match(h.runner.state.error, /第 6 段.*超时/)
})
