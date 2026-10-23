import assert from 'node:assert/strict'
import { buildReplaySteps, droneIdForOrder, normalizeReplayTasks } from '../src/replay/model.js'

const tasks = normalizeReplayTasks({ tasks: [
  { orderId: 'ORD-02', droneId: 'UAV-02', statusLabel: '已派发' },
  { orderId: 'ORD-01', droneId: null },
  { taskId: 'legacy-task', droneId: 'UAV-OLD' }
] })
assert.deepEqual(tasks.map(task => task.orderId), ['ORD-02', 'ORD-01'])
assert.equal(droneIdForOrder(tasks, 'ORD-02'), 'UAV-02')
assert.equal(droneIdForOrder(tasks, 'ORD-404'), '')

const steps = buildReplaySteps([
  { type: 'drone_state', data: { uavId: 'UAV-02', lon: 114.30, lat: 30.60, battery: 80, ts: 1000 } },
  { type: 'drone_state', data: { uavId: 'UAV-02', lon: Number.NaN, lat: 30.60, battery: 79, ts: 2000 } },
  { type: 'ship_traj', data: { points: [{ lon: Number.POSITIVE_INFINITY, lat: 30.6 }, { lon: 114.31, lat: 30.61 }] } }
])
assert.equal(steps.length, 2, '异常坐标帧应在渲染前跳过')
assert.equal(steps[0].frames[0][0], 'drone_state')
assert.deepEqual(steps[1].frames[0][1].points, [{ lon: 114.31, lat: 30.61 }])

console.log('replay model checks passed')
