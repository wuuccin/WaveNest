import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { candidateAssessment } from '../src/realtime/candidateAssessment.js'

const firstStep = candidateAssessment({
  candidateCount: 4,
  rejectionReasons: ['UAV-03：电量不足', 'UAV-04：超出服务半径']
}, 'NEST-01')
assert.equal(firstStep.count, 4)
assert.equal(firstStep.rejectedCount, 2)
assert.equal(firstStep.chosenNestId, 'NEST-01')

const comparison = candidateAssessment({
  candidateCount: 2,
  chosenNestId: 'NEST-02',
  candidates: [
    { nestId: 'NEST-01', droneId: 'UAV-01', distKm: 2.3, feasible: false, reasons: ['风速超限'] },
    { nestId: 'NEST-02', droneId: 'UAV-02', distKm: 1.4, feasible: true, selected: true }
  ]
})
assert.equal(comparison.rows.length, 2)
assert.equal(comparison.rejectedCount, 1)
assert.equal(comparison.rows[1].selected, true)

const shipLayer = await readFile(new URL('../src/layers/shipLayer.js', import.meta.url), 'utf8')
assert.match(shipLayer, /ship-actual-track/)
assert.match(shipLayer, /ship-predicted-track/)
assert.match(shipLayer, /2 \* uncertainty\.sigmaAlong/)
assert.match(shipLayer, /2 \* uncertainty\.sigmaCross/)
assert.match(shipLayer, /state\.courseDeg/)

const droneLayer = await readFile(new URL('../src/layers/droneTrackLayer.js', import.meta.url), 'utf8')
assert.match(droneLayer, /headingOf/)
assert.match(droneLayer, /执行中/)

const realtime = await readFile(new URL('../src/realtime/index.js', import.meta.url), 'utf8')
assert.match(realtime, /clearLiveMap/)
assert.match(realtime, /orders\.applyRendezvous/)

console.log('T3 visualization and candidate assessment checks passed')
