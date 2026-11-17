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

async function stubLiveApi(page) {
  await page.route('**/api/orders', route => route.fulfill({ json: { orders: [] } }))
  await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
  await page.route('**/api/drones', route => route.fulfill({ json: [] }))
  await page.route('**/api/airspace', route => route.fulfill({ json: [] }))
  await page.route('**/api/nests', route => route.fulfill({ json: [
    { nestId: 'NEST-NANCHANG-1', lon: 115.852, lat: 28.646, capacity: 4, supportedModels: ['M30T'], status: '可用' },
    { nestId: 'NEST-NANCHANG-2', lon: 115.879, lat: 28.692, capacity: 4, supportedModels: ['M400'], status: '可用' }
  ] }))
  await page.routeWebSocket('**/ws', socket => { socket.onMessage(() => {}) })
}

for (const viewport of [{ width: 1440, height: 900, name: 'desktop' }, { width: 768, height: 1024, name: 'tablet' }, { width: 390, height: 844, name: 'mobile' }]) {
  const page = await browser.newPage({ viewport })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await stubLiveApi(page)
  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.nest-pin').nth(1).waitFor({ timeout: 5000 })
  if (await page.locator('.nest-pin').count() !== 2) throw new Error(`${viewport.name}: map must draw only the two backend nest rows`)
  if (await page.locator('.nest-marker-label').allTextContents().then(names => names.join('|')) !== 'NEST-NANCHANG-1|NEST-NANCHANG-2') throw new Error(`${viewport.name}: nest ids did not match the backend rows`)
  await page.getByRole('button', { name: '离线演示' }).click()
  if (await page.locator('.nest-pin').count()) throw new Error(`${viewport.name}: live nest rows remained after switching to the offline data source`)
  const checkOverflow = async area => {
    const overflow = await page.evaluate(() => {
      if (document.documentElement.scrollWidth <= innerWidth + 1) return []
      return [...document.querySelectorAll('body *')].filter(element => element.getBoundingClientRect().right > innerWidth + 1 && !element.closest('.map-shell, .main-nav')).slice(0, 8).map(element => `${element.tagName.toLowerCase()}.${String(element.className).replace(/\s+/g, '.')}`)
    })
    if (overflow.length) throw new Error(`${viewport.name}: ${area} horizontal overflow from ${overflow.join(', ')}`)
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
  await page.getByLabel('配送品类').selectOption('应急药品')
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
  for (let index = 4; index <= 7; index += 1) await page.getByRole('button', { name: /下一步：/ }).click()
  await checkOverflow('weather recovery route')
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
  await page.getByLabel('配送品类').selectOption('应急药品')
  await page.getByRole('button', { name: '提交配送订单' }).click()
  if (!await page.getByText(/加入本地演示/).count()) throw new Error(`${viewport.name}: offline crew order failed`)
  await checkOverflow('crew')
  if (errors.length) throw new Error(`${viewport.name}: ${errors.join('; ')}`)
  console.log(`${viewport.name}: navigation, weather storyline, replay, crew order and layout passed`)
  await page.close()
}

const live = await browser.newPage()
const liveErrors = []
let uavOfftrackCalls = 0
let uavBatteryCalls = 0
let uavResetCalls = 0
live.on('pageerror', error => liveErrors.push(error.message))
await stubLiveApi(live)
await live.route('**/sim/uav', route => route.fulfill({ json: { drones: [{ uavId: 'UAV-01', targetDeviationM: uavOfftrackCalls ? 60 : 0, currentDeviationM: uavOfftrackCalls ? 24 : 0, bearingDeg: 90, batteryPercent: uavBatteryCalls ? 15 : 100, offtrackActive: uavOfftrackCalls > 0 }] } }))
await live.route('**/sim/uav/offtrack?**', route => { uavOfftrackCalls += 1; return route.fulfill({ json: { uavId: 'UAV-01', targetDeviationM: 60, currentDeviationM: 0, bearingDeg: 90, batteryPercent: 100, offtrackActive: true } }) })
await live.route('**/sim/uav/battery?**', route => { uavBatteryCalls += 1; return route.fulfill({ json: { uavId: 'UAV-01', targetDeviationM: 60, currentDeviationM: 24, bearingDeg: 90, batteryPercent: 15, offtrackActive: true } }) })
await live.route('**/sim/uav/injections?**', route => { uavResetCalls += 1; return route.fulfill({ json: { uavId: 'UAV-01', targetDeviationM: 0, currentDeviationM: 24, bearingDeg: 90, batteryPercent: 100, offtrackActive: false } }) })
await live.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
await live.getByRole('button', { name: '实时服务' }).click()
await live.locator('.header-status').getByText('WebSocket 已连接').waitFor({ timeout: 10000 })
if (!await live.locator('.live-order-summary').count()) throw new Error('live: backend order status missing')
await live.getByText('风况标尺与仿真注入').click()
await live.getByLabel('注入无人机').selectOption('UAV-01')
await live.getByRole('button', { name: '注入偏航' }).click()
await live.getByText(/已开始偏航注入/).waitFor()
await live.getByRole('button', { name: '注入低电量' }).click()
await live.getByText(/已注入电量 15/).waitFor()
await live.getByRole('button', { name: '复位所选无人机' }).click()
await live.getByText(/注入已复位/).waitFor()
if (uavOfftrackCalls !== 1 || uavBatteryCalls !== 1 || uavResetCalls !== 1) throw new Error('live: UAV injection requests missing')
if (liveErrors.length) throw new Error(`live: ${liveErrors.join('; ')}`)
await live.screenshot({ path: join(shots, 'live.png'), fullPage: true })
console.log('live: WebSocket connection and backend order status passed')
await browser.close()
