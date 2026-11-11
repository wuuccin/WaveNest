import { chromium } from 'playwright'

const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {})
})
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const errors = []
  let backendOnline = true
  const staticReply = (route, data) => backendOnline ? route.fulfill({ json: data }) : route.abort('connectionrefused')
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('wavenest-source', 'live')
    window.WebSocket = class TestWebSocket {
      constructor() {
        this.readyState = 0
        setTimeout(() => { this.readyState = 1; this.onopen?.({}) }, 0)
      }
      close() { this.readyState = 3 }
    }
  })
  await page.route('**/api/orders', route => route.fulfill({ json: { orders: [] } }))
  await page.route('**/api/nests', route => staticReply(route, [
    { nestId: 'NEST-NANCHANG-1', lon: 115.852, lat: 28.646, capacity: 4, supportedModels: ['M30T'], status: '可用' },
    { nestId: 'NEST-NANCHANG-2', lon: 115.879, lat: 28.692, capacity: 4, supportedModels: ['M400'], status: '可用' }
  ]))
  await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
  await page.route('**/api/drones', route => staticReply(route, [
    { droneId: 'UAV-01', homeNestId: 'NEST-NANCHANG-1', currentNestId: 'NEST-NANCHANG-1', status: '空闲', battery: 90 }
  ]))
  await page.route('**/api/airspace', route => staticReply(route, [
    { restrictionId: 'BRIDGE-CHAOYANG', name: '朝阳大桥', kind: 'bridge', type: '限高', center: { lon: 115.844393, lat: 28.645998 }, clearanceHeightM: 12, spanWidthM: 80 },
    { restrictionId: 'BRIDGE-NANCHANG', name: '南昌大桥', kind: 'bridge', type: '限高', center: { lon: 115.857937, lat: 28.663776 }, clearanceHeightM: 10, spanWidthM: 60 },
    { restrictionId: 'BRIDGE-BAYI', name: '八一大桥', kind: 'bridge', type: '限高', center: { lon: 115.872180, lat: 28.692014 }, clearanceHeightM: 14, spanWidthM: 100 }
  ]))

  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.nest-pin').nth(1).waitFor({ timeout: 8000 })
  const names = await page.locator('.nest-marker-label').allTextContents()
  if (names.join('|') !== 'NEST-NANCHANG-1|NEST-NANCHANG-2') throw new Error(`unexpected backend nests: ${names.join(', ')}`)
  if (await page.locator('.fleet-pin').count() !== 1) throw new Error('backend fleet was not grouped at its current nest')
  if (await page.locator('.bridge-pin').count() !== 3) throw new Error('three backend bridge records were not rendered')
  await page.getByRole('combobox', { name: '地图底图' }).waitFor()
  const scaleText = await page.locator('.map-scale').innerText()
  if (!scaleText.includes('影像桥面') || !scaleText.includes('天地图注记瓦片')) throw new Error('map scale must state the imagery bridge points and tile annotations')
  const guideText = await page.locator('.map-guide').innerText()
  if (!guideText.includes('台账中心线') || !guideText.includes('水面矢量约束')) throw new Error('map guide must state the missing shoreline constraints for prediction tracks')
  await page.locator('.bridge-pin').first().click({ force: true })
  const bridgePopup = await page.locator('.leaflet-popup-content').first().innerText()
  if (!bridgePopup.includes('影像桥面') || !bridgePopup.includes('台账中心')) throw new Error('bridge popup must state the imagery point and the ledger centre')
  backendOnline = false
  await page.waitForTimeout(5500)
  if (await page.locator('.nest-pin, .fleet-pin, .bridge-pin').count()) throw new Error('static business features remained after the backend became unreachable')
  if (errors.length) throw new Error(errors.join('; '))
  console.log('Map UI checks passed: backend nests/fleet/bridges render and disappear after backend loss')
} finally {
  await browser.close()
}
