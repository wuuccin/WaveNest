import assert from 'node:assert/strict'
import { toMapLatLng, wgs84ToGcj02 } from '../src/map/coordinates.js'

const original = { lon: 115.852, lat: 28.646, label: 'Chaoyang Bridge' }
const converted = wgs84ToGcj02(original)
assert.ok(converted.lon > original.lon + 0.002, 'Nanchang longitude should be converted for GCJ-02 tiles')
assert.ok(converted.lat < original.lat - 0.001, 'Nanchang latitude should be converted for GCJ-02 tiles')
assert.deepEqual(original, { lon: 115.852, lat: 28.646, label: 'Chaoyang Bridge' }, 'render conversion must not mutate stored WGS-84 values')
assert.deepEqual(toMapLatLng(original), [converted.lat, converted.lon])

const outsideChina = { lon: -73.9857, lat: 40.7484 }
assert.deepEqual(wgs84ToGcj02(outsideChina), outsideChina, 'coordinates outside China remain unchanged')
assert.equal(wgs84ToGcj02({ lon: Number.NaN, lat: 28.646 }), null, 'invalid points are not rendered')

console.log('WGS-84 → GCJ-02 map coordinate checks passed')
