import assert from 'node:assert/strict'
import { systemMapPoint, toMapLatLng } from '../src/map/coordinates.js'

const original = { lon: 115.852, lat: 28.646, label: 'Chaoyang Bridge' }
assert.deepEqual(systemMapPoint(original), original, 'Tianditu renders the system CGCS2000/WGS-84 coordinate without a GCJ-02 offset')
assert.deepEqual(original, { lon: 115.852, lat: 28.646, label: 'Chaoyang Bridge' }, 'render validation must not mutate stored coordinates')
assert.deepEqual(toMapLatLng(original), [original.lat, original.lon])

const outsideChina = { lon: -73.9857, lat: 40.7484 }
assert.deepEqual(systemMapPoint(outsideChina), outsideChina, 'valid coordinates remain unchanged')
assert.equal(systemMapPoint({ lon: Number.NaN, lat: 28.646 }), null, 'invalid points are not rendered')

console.log('CGCS2000/WGS-84 → Tianditu identity coordinate checks passed')
