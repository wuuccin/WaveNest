import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {})
})

try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('wavenest-source', 'live')
    window.WebSocket = class TestWebSocket {
      constructor() { setTimeout(() => this.onopen?.({}), 0) }
      close() {}
    }
  })
  await page.route('**/api/orders', route => route.fulfill({ json: { orders: [] } }))
  await page.route('**/api/nests', route => route.fulfill({ json: [
    { nestId: 'NEST-DEBUG-1', lon: 115.858, lat: 28.641, capacity: 4, supportedModels: ['M30T'], status: '可用' }
  ] }))
  await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
  await page.route('**/api/drones', route => route.fulfill({ json: [] }))
  await page.route('**/api/airspace', route => route.fulfill({ json: [] }))

  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  assert.equal(await page.getByLabel('开发取点工具').count(), 0, 'ordinary demo URL must not expose the development pick tool')

  await page.goto(`${baseUrl}/?debug=1#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.getByLabel('开发取点工具').waitFor({ timeout: 10_000 })
  assert.equal(await page.locator('.debug-bridge-pin').count(), 3, 'development map should show all three bridge references')

  const mapBox = await page.locator('#operations-map').boundingBox()
  await page.mouse.click(mapBox.x + 170, mapBox.y + 120)
  const picked = await page.getByLabel('开发取点工具').locator('code').innerText()
  assert.match(picked, /^\d+\.\d{7}, \d+\.\d{7}$/, 'picked system coordinate should be shown with seven decimals')

  await page.locator('.nest-pin').click({ force: true })
  const popup = await page.locator('.leaflet-popup-content').innerText()
  assert.match(popup, /NEST-DEBUG-1/, 'entity popup should show the nest ID')
  assert.match(popup, /CGCS2000\/WGS-84/, 'entity popup should label the stored coordinate system')
  assert.match(popup, /115\.858000, 28\.641000/, 'entity popup should show unshifted stored coordinates')
  if (errors.length) throw new Error(errors.join('; '))

  console.log('Development map pick tool checks passed: system CRS, bridge references and entity coordinates')
} finally {
  await browser.close()
}
