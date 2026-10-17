import { chromium } from 'playwright'

const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {}) })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
page.on('pageerror', error => errors.push(error.message))
await page.goto(`${process.env.FRONTEND_URL || 'http://127.0.0.1:5173'}/#dashboard`, { waitUntil: 'domcontentloaded' })
await page.locator('.header-status').getByText('WebSocket 已连接').waitFor({ timeout: 10000 })
await page.getByRole('link', { name: '分步演示' }).click()
await page.getByRole('button', { name: '创建订单，开始演示' }).click()
await page.locator('.guided-story').waitFor({ timeout: 25000 })
const orderId = (await page.locator('.guided-order-id').textContent()).replace(/^订单\s*/, '')
if (!orderId.startsWith('ORD-')) throw new Error(`backend order ID missing: ${orderId}`)
for (let step = 1; step <= 7; step += 1) {
  if (!await page.getByText(`第 ${step} / 7 步`).count()) throw new Error(`live guide step ${step} missing`)
  if (step === 3) {
    if (await page.locator('.map-branch-label').count()) throw new Error('live guide chose a route automatically')
    await page.getByRole('button', { name: /② 放弃本次会面/ }).click()
    if (!await page.locator('.map-branch-label.return').count()) throw new Error('live return route missing')
  } else if (step < 7) await page.getByRole('button', { name: /下一步：/ }).click()
}
if (!await page.locator('.guided-status-card').getByText('已完成').count()) throw new Error('backend order did not finish in guide')
for (let index = 0; index < 4; index += 1) await page.getByRole('button', { name: /上一步/ }).click()
await page.getByRole('button', { name: /① 原机继续配送/ }).click()
if (!await page.locator('.map-branch-label.continue').count()) throw new Error('live continue scenario route missing')
for (let index = 0; index < 3; index += 1) await page.getByRole('button', { name: /下一步：/ }).click()
if (!await page.locator('.guided-status-card').getByText('UAV-01').count()) throw new Error('live continue scenario changed drone')
if (!await page.locator('.guided-status-card').getByText('已完成').count()) throw new Error('live continue scenario did not finish')
if (errors.length) throw new Error(errors.join('; '))
await page.getByRole('button', { name: '流程讲完，返回总览' }).click()
await page.locator('h1').getByText('运行总览').waitFor()
console.log(`Live guide passed: ${orderId}, backend return replay and local continue scenario`)
await browser.close()
