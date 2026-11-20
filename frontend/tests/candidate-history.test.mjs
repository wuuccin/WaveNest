import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeCandidateHistory } from '../src/realtime/candidateAssessment.js'

test('候选历史保留后端九项字段和落选会面时刻空值', () => {
  const rows = normalizeCandidateHistory([
    { orderId: 'ORD-A', model: 'M400', nestId: 'NEST-01', droneId: 'UAV-01', distKm: 1.2, meetTimeMs: 1795000000000, rejectReason: '中选', rankKey: 'delay=0;dist=1.2', snapshotVersion: 'v1' },
    { orderId: 'ORD-A', model: 'M400', nestId: 'NEST-02', droneId: 'UAV-02', distKm: 2.3, meetTimeMs: null, rejectReason: '电量不足', rankKey: 'delay=4;dist=2.3', snapshotVersion: 'v1' }
  ])
  assert.equal(rows[0].selected, true)
  assert.equal(rows[1].meetTimeMs, null)
  assert.equal(rows[1].rejectReason, '电量不足')
  assert.equal(rows[1].snapshotVersion, 'v1')
})

test('候选历史接口缺少数组时显示读取错误', () => {
  assert.throws(() => normalizeCandidateHistory({ candidates: [] }), /未返回数组/)
})
