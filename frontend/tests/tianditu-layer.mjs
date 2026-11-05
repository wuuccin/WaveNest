import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5174'
const tileUrls = []
const onePixelPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p0sAAAAASUVORK5CYII=', 'base64')
const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {})
})
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await page.addInitScript(() => localStorage.setItem('wavenest-source', 'live'))
  await page.routeWebSocket('**/ws', socket => { socket.onMessage(message => socket.send(message)) })
  await page.route('**/api/orders', route => route.fulfill({ json: { orders: [] } }))
  await page.route('**/api/nests', route => route.fulfill({ json: [] }))
  await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
  await page.route(/https:\/\/t\d\.tianditu\.gov\.cn\/DataServer\?/, route => {
    tileUrls.push(route.request().url())
    return route.fulfill({ status: 200, contentType: 'image/png', body: onePixelPng })
  })

  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.waitForFunction(() => document.querySelector('.map-caption')?.textContent.includes('卫星影像'), null, { timeout: 10000 })
  assert.ok(tileUrls.some(url => url.includes('T=img_w')), 'satellite imagery layer should request img_w')
  assert.ok(tileUrls.some(url => url.includes('T=cia_w')), 'satellite imagery should pair with cia_w labels')
  if (await page.locator('.map-tile-notice').count()) throw new Error('valid local key should not show a missing-key notice')

  await page.getByRole('combobox', { name: '地图底图' }).selectOption('vector')
  await page.waitForFunction(() => document.querySelector('.map-caption')?.textContent.includes('矢量底图'), null, { timeout: 10000 })
  assert.ok(tileUrls.some(url => url.includes('T=vec_w')), 'vector base layer should request vec_w')
  assert.ok(tileUrls.some(url => url.includes('T=cva_w')), 'vector base layer should pair with cva_w labels')
  console.log('Tianditu layer checks passed: img_w/cia_w and vec_w/cva_w with rendered layer switching')
} finally {
  await browser.close()
}
