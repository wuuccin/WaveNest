import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const shots = join(root, 'screenshots')
const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
await mkdir(shots, { recursive: true })
const launchOptions = { headless: true }
if (process.env.BROWSER_PATH) launchOptions.executablePath = process.env.BROWSER_PATH
const browser = await chromium.launch(launchOptions)

for (const viewport of [{ width: 1440, height: 900, name: 'desktop' }, { width: 768, height: 1024, name: 'tablet' }, { width: 390, height: 844, name: 'mobile' }]) {
  const page = await browser.newPage({ viewport })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '离线演示' }).click()
  const checkOverflow = async area => {
    if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)) throw new Error(`${viewport.name}: ${area} horizontal overflow`)
  }
  await checkOverflow('dashboard')
  if (!await page.locator('.leaflet-pane').count()) throw new Error(`${viewport.name}: map missing`)
  await page.screenshot({ path: join(shots, `${viewport.name}.png`), fullPage: true })
  for (const [index, title] of ['船员下单', '调度台', '调度台', '任务回放'].entries()) {
    await page.locator('.workflow-card').nth(index).click()
    if (await page.locator('h1').textContent() !== title) throw new Error(`${viewport.name}: function card ${index + 1} opened the wrong page`)
    await checkOverflow(`function card ${index + 1}`)
    if (index === 0) await page.screenshot({ path: join(shots, `${viewport.name}-crew.png`), fullPage: true })
    if (index === 1) await page.screenshot({ path: join(shots, `${viewport.name}-console.png`), fullPage: true })
    if (index === 2 && !await page.locator('.workspace-grid.focus-intervene').count()) throw new Error(`${viewport.name}: intervention controls were not emphasized`)
    if (index === 3) await page.getByRole('button', { name: '退出回放' }).click()
    else await page.getByRole('link', { name: '运行总览', exact: true }).click()
  }
  await page.getByRole('button', { name: /^开始分步演示/ }).click()
  if (await page.locator('h1').textContent() !== '分步演示') throw new Error(`${viewport.name}: guide did not open`)
  await checkOverflow('guide order form')
  await page.getByRole('button', { name: '创建订单，开始演示' }).click()
  await page.locator('.guided-story').waitFor()
  if (!await page.getByText('第 1 / 7 步').count()) throw new Error(`${viewport.name}: first guide step missing`)
  await page.waitForTimeout(350)
  if (!await page.getByText('第 1 / 7 步').count()) throw new Error(`${viewport.name}: guide advanced without a click`)
  for (let index = 2; index <= 3; index += 1) {
    await page.getByRole('button', { name: /下一步：/ }).click()
    if (!await page.getByText(`第 ${index} / 7 步`).count()) throw new Error(`${viewport.name}: guide step ${index} missing`)
    if (index === 3) await page.screenshot({ path: join(shots, `${viewport.name}-guide.png`), fullPage: true })
  }
  if (await page.locator('.map-branch-label').count()) throw new Error(`${viewport.name}: branch was selected automatically`)
  await page.getByRole('button', { name: /① 原机继续配送/ }).click()
  if (!await page.locator('.map-branch-label.continue').count()) throw new Error(`${viewport.name}: continue route missing`)
  await checkOverflow('continue route')
  if (viewport.name !== 'tablet') await page.screenshot({ path: join(shots, `${viewport.name}-continue.png`), fullPage: true })
  for (let index = 5; index <= 7; index += 1) await page.getByRole('button', { name: /下一步：/ }).click()
  if (!await page.locator('.guided-status-card').getByText('UAV-01').count()) throw new Error(`${viewport.name}: continue branch used wrong drone`)
  for (let index = 0; index < 4; index += 1) await page.getByRole('button', { name: /上一步/ }).click()
  if (await page.locator('.map-branch-label').count()) throw new Error(`${viewport.name}: branch route remained after reselect`)
  await page.getByRole('button', { name: /② 放弃本次会面/ }).click()
  if (!await page.locator('.map-branch-label.return').count()) throw new Error(`${viewport.name}: return route missing`)
  await checkOverflow('return route')
  if (viewport.name !== 'tablet') await page.screenshot({ path: join(shots, `${viewport.name}-return.png`), fullPage: true })
  for (let index = 5; index <= 7; index += 1) await page.getByRole('button', { name: /下一步：/ }).click()
  if (!await page.getByText('已完成', { exact: true }).count()) throw new Error(`${viewport.name}: completion missing`)
  if (!await page.locator('.meeting-pin').count()) throw new Error(`${viewport.name}: meeting point missing`)
  await page.screenshot({ path: join(shots, `${viewport.name}-complete.png`), fullPage: true })
  await page.getByRole('button', { name: '流程讲完，返回总览' }).click()
  await page.getByRole('button', { name: '查看已有任务回放' }).click()
  if (await page.locator('h1').textContent() !== '任务回放' || await page.locator('.replay-list li').count() !== 7) throw new Error(`${viewport.name}: replay missing frames`)
  await page.getByRole('button', { name: '退出回放' }).click()
  await page.getByRole('link', { name: '调度台' }).click()
  await page.locator('h1').getByText('调度台').waitFor()
  await page.getByRole('link', { name: '船员下单' }).click()
  await page.locator('h1').getByText('船员下单').waitFor()
  await page.getByRole('button', { name: '提交配送订单' }).click()
  if (!await page.getByText(/加入本地演示/).count()) throw new Error(`${viewport.name}: offline crew order failed`)
  await checkOverflow('crew')
  if (errors.length) throw new Error(`${viewport.name}: ${errors.join('; ')}`)
  console.log(`${viewport.name}: navigation, manual order, both branch routes, replay, crew order and layout passed`)
  await page.close()
}

const live = await browser.newPage()
const liveErrors = []
live.on('pageerror', error => liveErrors.push(error.message))
await live.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
await live.getByRole('button', { name: '实时服务' }).click()
await live.locator('.header-status').getByText('WebSocket 已连接').waitFor({ timeout: 10000 })
if (!await live.locator('.live-order-summary').count()) throw new Error('live: backend order status missing')
if (liveErrors.length) throw new Error(`live: ${liveErrors.join('; ')}`)
await live.screenshot({ path: join(shots, 'live.png'), fullPage: true })
console.log('live: WebSocket connection and backend order status passed')
await browser.close()
