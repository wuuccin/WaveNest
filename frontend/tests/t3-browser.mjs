import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {})
})
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
const now = Date.now()
const frames = [
  ['order_state', { orderId: 'T3-DEMO', orderStatus: '已派发', eventTs: now, stateVersion: 2 }],
  ['ship_traj', { mmsi: '123456789', shipName: 'SIM-SHIP-01', lon: 115.8611, lat: 28.6660, t0: now, courseDeg: 31.2, points: [
    { lon: 115.8611, lat: 28.6660, sigmaAlong: 160, sigmaCross: 75 },
    { lon: 115.8700, lat: 28.6780, sigmaAlong: 120, sigmaCross: 55 }
  ] }],
  ['ship_traj', { mmsi: '123456789', shipName: 'SIM-SHIP-01', lon: 115.8620, lat: 28.6672, t0: now + 1000, courseDeg: 31.2, points: [
    { lon: 115.8620, lat: 28.6672, sigmaAlong: 95, sigmaCross: 44 },
    { lon: 115.8710, lat: 28.6790, sigmaAlong: 60, sigmaCross: 28 }
  ] }],
  ['rendezvous', { orderId: 'T3-DEMO', meetLon: 115.870, meetLat: 28.678, locked: false, candidateCount: 2, rejectionReasons: [] }],
  ['rendezvous', { orderId: 'T3-DEMO', meetLon: 115.875, meetLat: 28.684, locked: true, candidateCount: 2, chosenNestId: 'NEST-02', rejectionReasons: ['UAV-01：风速超限'], candidates: [
    { nestId: 'NEST-01', droneId: 'UAV-01', distKm: 2.31, feasible: false, reasons: ['风速超限'] },
    { nestId: 'NEST-02', droneId: 'UAV-02', distKm: 1.18, feasible: true, selected: true }
  ] }],
  ['drone_state', { uavId: 'UAV-02', lon: 115.868, lat: 28.674, battery: 83, phase: 'OUTBOUND', headingDeg: 42, eventTs: now + 2000 }],
  ['alarm', { alertLevel: 'WARN', alertType: 'DEVIATION', target: 'T3-DEMO', message: '会面方案已重新评估', lon: 115.868, lat: 28.674, eventTs: now + 3000 }]
]

try {
  await page.addInitScript(() => localStorage.setItem('wavenest-source', 'live'))
  await page.route('**/api/orders', route => route.fulfill({ json: { orders: [{ orderId: 'T3-DEMO', orderStatus: '已派发', eventTs: now, stateVersion: 2 }], dispatchGate: { gateStatus: 'NORMAL', gateSources: [] } } }))
  await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
  await page.route('**/api/nests', route => route.fulfill({ json: [
    { nestId: 'NEST-01', lon: 115.848, lat: 28.642, capacity: 4, status: '可用' },
    { nestId: 'NEST-02', lon: 115.867, lat: 28.698, capacity: 4, status: '可用' }
  ] }))
  await page.route('**/api/drones', route => route.fulfill({ json: [] }))
  await page.route('**/api/airspace', route => route.fulfill({ json: [] }))
  await page.routeWebSocket('**/ws', socket => {
    setTimeout(() => frames.forEach(([type, data]) => socket.send(JSON.stringify({ type, data }))), 200)
  })
  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.getByLabel('候选评估').waitFor({ timeout: 8000 })
  assert.match(await page.getByLabel('候选评估').innerText(), /评估 2 个 · 淘汰 1 个/)
  assert.match(await page.getByLabel('候选评估').innerText(), /中选 NEST-02/)
  assert.equal(await page.locator('.ship-actual-track').count(), 1)
  assert.equal(await page.locator('.ship-predicted-track').count(), 1)
  assert.equal(await page.locator('.ship-confidence-ellipse').count(), 2)
  assert.equal(await page.locator('.previous-meeting-pin').count(), 1)
  assert.equal(await page.locator('.meeting-pin.locked').count(), 1)
  assert.equal(await page.locator('.nest-pin.candidate-selected').count(), 1)
  assert.match(await page.locator('.drone-pin .map-marker-label').innerText(), /T3-DEMO/)
  assert.match(await page.locator('.drone-pin i').getAttribute('style'), /42\.0deg/)
  assert.match(await page.locator('.event-list').innerText(), /会面方案已重新评估/)
  if (process.env.T3_SCREENSHOT) await page.screenshot({ path: process.env.T3_SCREENSHOT, fullPage: true })
  console.log('T3 browser checks passed: candidate cards, three layers, rendezvous drift, heading and alarm linkage')
} finally {
  await browser.close()
}
