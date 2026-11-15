import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const browser = await chromium.launch({ headless: true,
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
page.on('pageerror', error => errors.push(error.message))
await page.route('**/api/orders', route => route.fulfill({ json: { orders: [], dispatchGate: { gateStatus: 'NORMAL', gateSources: [] } } }))
await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [], total: 0 } }))
await page.route('**/api/drones', route => route.fulfill({ json: [{ droneId: 'UAV-1', model: 'M400', windLimitMs: 12, homeNestId: 'N-1' }] }))
await page.route('**/api/nests', route => route.fulfill({ json: [] }))
await page.route('**/api/airspace', route => route.fulfill({ json: [] }))
await page.route('**/api/monitor/statistics', route => route.fulfill({ json: { totalOrders: 0, byStatus: [], inFlightCount: 0, finishedCount: 0, terminalCount: 0 } }))
await page.route('**/sim/weather', route => route.fulfill({ json: { biasActive: false, injectedCount: 0, gridPointCount: 1 } }))
await page.route('**/sim/weather/inject?**', route => route.fulfill({ json: { windMs: 11, dirDeg: 135, publishedCount: 1, persist: true, injectedCount: 1 } }))
await page.routeWebSocket('**/ws', socket => {
  socket.onMessage(() => {})
  setTimeout(() => socket.send(JSON.stringify({ type: 'weather', data: {
    lon: 115.86, lat: 28.66, windSpeedMs: 11, windDirDeg: 135, gustMs: 13, obsTs: Date.now()
  } })), 100)
})

try {
  await page.goto(process.env.FRONTEND_URL || 'http://127.0.0.1:5173/', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '实时服务' }).click()
  await page.locator('.weather-disclosure summary').click()
  await page.getByRole('heading', { name: '风况标尺' }).waitFor()
  await page.getByRole('heading', { name: '风速注入' }).waitFor()
  await page.getByText('11.0 m/s', { exact: true }).first().waitFor()
  assert.equal(await page.locator('.weather-pin').count(), 1, '地图应绘制真实气象帧网格点')
  await page.getByRole('button', { name: '注入这片风' }).click()
  await page.getByText(/向 1 个网格点各发一条/).waitFor()
  assert.deepEqual(errors, [], '气象挂载不得触发页面异常')
  console.log('weather mount browser checks passed')
} finally {
  await browser.close()
}
