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
      constructor() {
        this.readyState = 0
        setTimeout(() => { this.readyState = 1; this.onopen?.({}) }, 0)
      }
      close() { this.readyState = 3 }
    }
  })
  await page.route('**/api/orders', route => route.fulfill({ json: { orders: [] } }))
  await page.route('**/api/nests', route => route.fulfill({ json: [
    { nestId: 'NEST-NANCHANG-1', lon: 115.852, lat: 28.646, capacity: 4, supportedModels: ['M30T'], status: '可用' },
    { nestId: 'NEST-NANCHANG-2', lon: 115.879, lat: 28.692, capacity: 4, supportedModels: ['M400'], status: '可用' }
  ] }))
  await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
  await page.route('**/api/drones', route => route.fulfill({ json: [] }))

  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.nest-pin').nth(1).waitFor({ timeout: 8000 })
  const names = await page.locator('.nest-marker-label').allTextContents()
  if (names.join('|') !== 'NEST-NANCHANG-1|NEST-NANCHANG-2') throw new Error(`unexpected backend nests: ${names.join(', ')}`)
  await page.getByRole('combobox', { name: '地图底图' }).waitFor()
  if (await page.getByRole('combobox', { name: '地图底图' }).isEnabled()) throw new Error('basemap switch should remain disabled without a local Tianditu key')
  await page.getByText('卫星底图待配置').waitFor()
  if (errors.length) throw new Error(errors.join('; '))
  console.log('Map UI checks passed: actual nest rows, data-driven markers and missing-key notice')
} finally {
  await browser.close()
}
