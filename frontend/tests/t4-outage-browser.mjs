import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {})
})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
let backendOnline = true
let outsideOnline = true
let blockedTileRequests = 0
let servedTileRequests = 0
let socketCount = 0
const sockets = new Set()
const errors = []
const onePixelPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p0sAAAAASUVORK5CYII=', 'base64')
const frames = () => {
  const timestamp = Date.now()
  return [
    ['ship_traj', { version: timestamp, t0: timestamp, courseDeg: 31.2, validitySec: 300,
      lon: 115.8611, lat: 28.666, points: [{ t: timestamp + 60000, lon: 115.870, lat: 28.678, sigmaAlong: 90, sigmaCross: 40 }] }],
    ['drone_state', { uavId: 'UAV-OUTAGE', lon: 115.860, lat: 28.665, battery: 87, phase: 'OUTBOUND', ts: timestamp }],
    ['rendezvous', { orderId: 'ORD-OUTAGE', meetLon: 115.86944, meetLat: 28.67808, locked: false,
      candidateCount: 2, rejectionReasons: ['UAV-02：电量不足'] }],
    ['alarm', { alertLevel: 'WARN', alertType: 'DEVIATION', target: 'UAV-OUTAGE',
      message: '断连测试告警', lon: 115.860, lat: 28.665, eventTs: timestamp }]
  ]
}

async function snapshot() {
  return {
    title: await page.title(),
    bodyVisible: await page.locator('body').isVisible(),
    businessFeatures: await page.locator('.ship-pin, .drone-pin, .meeting-pin, .alert-pin, .alert-flash, .nest-pin, .fleet-pin, .bridge-pin, .ship-predicted-track, .ship-actual-track, .ship-confidence-ellipse').count(),
    staticFeatures: await page.locator('.nest-pin, .fleet-pin, .bridge-pin').count(),
    dynamicFeatures: await page.locator('.ship-pin, .drone-pin, .meeting-pin, .alert-pin, .alert-flash, .ship-predicted-track, .ship-actual-track, .ship-confidence-ellipse').count(),
    connection: await page.locator('.header-status').innerText(),
    notice: await page.locator('.system-notice, .map-connection-state, .map-data-notice, .map-tile-notice').allTextContents()
  }
}

try {
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('wavenest-source', 'live'))
  await page.route('https://t*.tianditu.gov.cn/**', route => {
    if (!outsideOnline) { blockedTileRequests++; return route.abort('internetdisconnected') }
    servedTileRequests++
    return route.fulfill({ status: 200, contentType: 'image/png', body: onePixelPng })
  })
  await page.route('**/api/orders', route => backendOnline
    ? route.fulfill({ json: { orders: [{ orderId: 'ORD-OUTAGE', orderStatus: '已派发', eventTs: Date.now(), stateVersion: 2 }] } })
    : route.abort('connectionrefused'))
  await page.route('**/api/nests', route => backendOnline
    ? route.fulfill({ json: [{ nestId: 'NEST-01', lon: 115.848, lat: 28.642, status: '可用' }] })
    : route.abort('connectionrefused'))
  await page.route('**/api/drones', route => backendOnline
    ? route.fulfill({ json: [{ droneId: 'UAV-OUTAGE', homeNestId: 'NEST-01', currentNestId: 'NEST-01', battery: 87 }] })
    : route.abort('connectionrefused'))
  await page.route('**/api/airspace', route => backendOnline
    ? route.fulfill({ json: [{ restrictionId: 'BRIDGE-1', kind: 'bridge', name: '朝阳大桥', center: { lon: 115.852, lat: 28.646 } }] })
    : route.abort('connectionrefused'))
  await page.route('**/api/monitor/tasks', route => backendOnline
    ? route.fulfill({ json: { tasks: [] } })
    : route.abort('connectionrefused'))
  await page.routeWebSocket('**/ws', socket => {
    socketCount++
    if (!backendOnline) { socket.close(); return }
    sockets.add(socket)
    setTimeout(() => {
      if (backendOnline) for (const [type, data] of frames()) socket.send(JSON.stringify({ type, data }))
    }, 300)
  })

  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.ship-pin').waitFor({ timeout: 10000 })
  await page.locator('.nest-pin').waitFor({ timeout: 10000 })
  const before = await snapshot()
  assert(before.businessFeatures >= 6, 'before outage, map must have live business features')
  if (before.notice.some(text => /瓦片加载失败/.test(text))) throw new Error(`online tile baseline failed: served=${servedTileRequests}, notice=${before.notice.join('|')}`)

  backendOnline = false
  for (const socket of sockets) socket.close()
  sockets.clear()
  await page.waitForFunction(() => {
    const dynamic = document.querySelectorAll('.ship-pin, .drone-pin, .meeting-pin, .alert-pin, .alert-flash, .ship-predicted-track, .ship-actual-track, .ship-confidence-ellipse').length
    const staticCount = document.querySelectorAll('.nest-pin, .fleet-pin, .bridge-pin').length
    return dynamic === 0 && staticCount === 0
  }, { timeout: 20000 })
  const backendDown = await snapshot()
  assert(backendDown.bodyVisible, 'backend outage must not blank the page')
  assert.equal(backendDown.businessFeatures, 0, 'backend outage must remove all live business features')
  assert(backendDown.notice.some(text => /失败|断开|读取/.test(text)), 'outage must show a readable notice')

  backendOnline = true
  await page.waitForFunction(() => document.querySelectorAll('.ship-pin, .nest-pin, .meeting-pin').length >= 3, { timeout: 30000 })
  const restored = await snapshot()
  assert(restored.bodyVisible, 'recovery must keep page usable')
  assert(restored.businessFeatures >= 6, 'business features must return after reconnect')
  assert(socketCount >= 2, 'WebSocket must reconnect automatically')

  outsideOnline = false
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.locator('.ship-pin').waitFor({ timeout: 10000 })
  await page.locator('.map-tile-notice.error').waitFor({ timeout: 10000 })
  const outsideDown = await snapshot()
  assert(outsideDown.bodyVisible, 'external network outage must not blank the page')
  assert(outsideDown.businessFeatures >= 6, 'backend data must remain visible when only map tiles are offline')
  assert(blockedTileRequests > 0, 'external map requests must actually be blocked')
  assert(outsideDown.notice.some(text => /瓦片加载失败/.test(text)), 'external tile outage must show a readable notice')

  if (errors.length) throw new Error(`Browser errors: ${errors.join('; ')}`)
  console.log(JSON.stringify({ before, backendDown, restored, outsideDown, socketCount, blockedTileRequests, browserErrors: errors }, null, 2))
} finally {
  await browser.close()
}
