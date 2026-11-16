import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const browser = await chromium.launch({
  headless: true,
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
})
const page = await browser.newPage({ viewport: { width: 1366, height: 850 } })
const requests = []
await page.route('**/api/orders', route => route.fulfill({ json: {
  orders: [], dispatchGate: { gateStatus: 'NORMAL', gateSources: [] }
} }))
await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [], total: 0 } }))
await page.route('**/api/intervene', async route => {
  requests.push(route.request().postDataJSON())
  await route.fulfill({ json: { confirmed: true, gateStatus: 'NORMAL', gateSources: [] } })
})
await page.routeWebSocket('**/ws', socket => socket.onMessage(() => {}))

try {
  await page.goto(process.env.FRONTEND_URL || 'http://127.0.0.1:5173/', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '实时服务' }).click()
  await page.getByRole('link', { name: '调度台' }).click()
  const reset = page.getByRole('button', { name: '重置闸门' })
  await reset.waitFor()
  assert.equal(await reset.isEnabled(), true, '无订单时仍应允许重置闸门')
  await reset.click()
  await page.locator('.action-feedback', { hasText: '重置闸门已确认' }).waitFor()
  assert.deepEqual(requests, [{ action: '重置闸门' }], 'reset 请求不应携带订单号')
  console.log('reset gate browser checks passed')
} finally {
  await browser.close()
}
