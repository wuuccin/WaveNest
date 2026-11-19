import assert from 'node:assert/strict'
import { missionPosition } from '../src/realtime/missionPosition.js'

const t0 = 1_800_000_000_000
const near = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 1e-9,
  `${message}: expected ${expected}, got ${actual}`)

const track = {
  orderId: 'ORD-1',
  segments: [
    { kind: '去程', points: [
      { lon: 115.848, lat: 28.642, tMs: t0 },
      { lon: 115.848, lat: 28.650, tMs: t0 + 60_000 }
    ] },
    { kind: '末段交接', points: [
      { lon: 115.848, lat: 28.650, tMs: t0 + 60_000 },
      { lon: 115.858, lat: 28.662, tMs: t0 + 120_000 }
    ] }
  ]
}

assert.equal(missionPosition(null, t0), null, 'no track yields no display position')
assert.equal(missionPosition(track, Number.NaN), null, 'invalid clock yields no display position')
assert.equal(missionPosition({ orderId: 'X', segments: [] }, t0), null, 'empty track yields no display position')

const before = missionPosition(track, t0 - 1_000)
near(before.lon, 115.848, 'before takeoff stays at the first waypoint (lon)')
near(before.lat, 28.642, 'before takeoff stays at the first waypoint (lat)')

const middle = missionPosition(track, t0 + 30_000)
near(middle.lon, 115.848, 'midpoint longitude stays fixed on a northbound leg')
near(middle.lat, 28.646, 'midpoint latitude is linearly interpolated')
near(middle.headingDeg, 0, 'northbound segment heading is 0 degrees')
assert.equal(middle.segmentKind, '去程', 'segment kind follows the active leg')

const cross = missionPosition(track, t0 + 90_000)
near(cross.lon, 115.853, 'cross-leg interpolation uses absolute tMs, not array position')
near(cross.lat, 28.656, 'cross-leg latitude is interpolated inside the second leg')
assert.equal(cross.segmentKind, '末段交接', 'segment kind switches at the leg boundary')

const after = missionPosition(track, t0 + 999_000)
near(after.lon, 115.858, 'past the last waypoint stays at the final position')
near(after.lat, 28.662, 'past the last waypoint stays at the final latitude')

const eastward = missionPosition({ orderId: 'E', segments: [
  { kind: '去程', points: [
    { lon: 115.848, lat: 28.642, tMs: t0 },
    { lon: 115.850, lat: 28.642, tMs: t0 + 60_000 }
  ] }
] }, t0 + 30_000)
near(eastward.headingDeg, 90, 'eastbound segment heading is 90 degrees')

const withBadPoint = missionPosition({ orderId: 'B', segments: [
  { kind: '去程', points: [
    { lon: Number.NaN, lat: 28.642, tMs: t0 },
    { lon: 115.848, lat: 28.642, tMs: t0 },
    { lon: 115.848, lat: 28.650, tMs: t0 + 60_000 }
  ] }
] }, t0 + 30_000)
near(withBadPoint.lon, 115.848, 'invalid waypoints are skipped before interpolation')

assert.deepEqual(track.segments[0].points[0], { lon: 115.848, lat: 28.642, tMs: t0 },
  'interpolation must not mutate the stored plan track')

console.log('Mission position interpolation checks passed')
