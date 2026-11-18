import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createPinia, setActivePinia } from 'pinia'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const vite = await createServer({ root: dirname(dirname(fileURLToPath(import.meta.url))), server: { middlewareMode: true } })
const { useOrderStore } = await vite.ssrLoadModule('/src/stores/index.js')

setActivePinia(createPinia())
const store = useOrderStore()
const review = { verdict: '调整', opType: '在飞安全复核-调整', eventTs: 1773648000000 }
assert.equal(store.apply({ orderId: 'ORD-1', orderStatus: '去程中', stateVersion: 1, eventTs: 1000, safetyReview: review }), true)
assert.equal(store.apply({ orderId: 'ORD-1', orderStatus: '去程中', stateVersion: 2, eventTs: 2000, safetyReview: null }), true)
assert.deepEqual(store.byId['ORD-1'].safetyReview, review)
assert.equal(store.apply({ orderId: 'ORD-1', orderStatus: '返航中', stateVersion: 3, eventTs: 3000 }), true)
assert.deepEqual(store.byId['ORD-1'].safetyReview, review)
assert.equal(store.apply({ orderId: 'ORD-1', orderStatus: '返航中', stateVersion: 2, eventTs: 4000 }), false)
assert.equal(store.timeline['ORD-1'].length, 3)
console.log('safety review merge checks passed')
await vite.close()
