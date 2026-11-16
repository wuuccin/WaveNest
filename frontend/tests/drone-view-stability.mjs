/**
 * 无人机单机视图稳定性回归。
 *
 * 运行：node tests/drone-view-stability.mjs（需先 pnpm build，用 preview 起静态站点）
 *
 * 后端仿真器每拍按 UAV-01 到 UAV-04 顺序连发四帧。本脚本注入三拍帧，
 * 验证地图只画一架机，当前任务面板与运行指标条都粘在首架在网机。
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.PREVIEW_URL || 'http://127.0.0.1:4173'
const results = []
function check(name, fn) {
  return Promise.resolve().then(fn).then(() => { results.push([name, '✓', '']) },
    error => { results.push([name, '✗', error.message]) })
}

const now = Date.now()
const uavIds = ['UAV-01', 'UAV-02', 'UAV-03', 'UAV-04']
const framesFor = tick => uavIds.map((uavId, index) => ({
  type: 'drone_state',
  data: {
    uavId,
    lon: 115.858 + index * 0.002,
    lat: 28.663 + tick * 0.0003 + index * 0.0004,
    battery: 90 - index,
    phase: 'OUTBOUND',
    headingDeg: 30 + index * 10,
    ts: now + tick * 1000 + index
  }
}))

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const consoleErrors = []
page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
page.on('pageerror', error => consoleErrors.push(`pageerror: ${error.message}`))

await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{"orders":[],"tasks":[]}' }))
await page.route('**/api/orders', route => route.fulfill({ json: { orders: [] } }))
await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [] } }))
await page.route('**/api/nests', route => route.fulfill({ json: [] }))
await page.route('**/api/drones', route => route.fulfill({ json: [] }))
await page.route('**/api/airspace', route => route.fulfill({ json: [] }))
await page.route('**/api/prediction/**', route => route.fulfill({ status: 404, body: '{}' }))
await page.routeWebSocket('**/ws', socket => {
  setTimeout(() => framesFor(0).forEach(frame => socket.send(JSON.stringify(frame))), 200)
  setTimeout(() => framesFor(1).forEach(frame => socket.send(JSON.stringify(frame))), 700)
  setTimeout(() => framesFor(2).forEach(frame => socket.send(JSON.stringify(frame))), 1200)
})

await page.goto(BASE + '/#dashboard', { waitUntil: 'domcontentloaded' })
await page.locator('.drone-pin').first().waitFor({ state: 'attached', timeout: 15000 })

const missionDrone = page.locator('.mission-facts div').first().locator('strong')
const metricCard = page.locator('.metric-strip div').nth(2)
const metricDrone = metricCard.locator('small')
const metricBattery = metricCard.locator('strong')

await check('验证地图只渲染一架无人机', async () => {
  assert.equal(await page.locator('.drone-pin').count(), 1, '地图画出了多架无人机')
})

await check('验证当前任务面板机号取首架', async () => {
  assert.equal((await missionDrone.innerText()).trim(), 'UAV-01', '当前任务面板没有粘住首架在网机')
})

await check('验证运行指标条机号取首架', async () => {
  assert.equal((await metricDrone.innerText()).trim(), 'UAV-01', '运行指标条没有粘住首架在网机')
})

await check('验证运行指标条电量取首架', async () => {
  assert.equal((await metricBattery.innerText()).trim(), '90%', '运行指标条读到了别架机的电量')
})

await page.waitForTimeout(1600)

await check('验证多拍帧后机号仍不漂移', async () => {
  assert.equal((await missionDrone.innerText()).trim(), 'UAV-01', '后续帧把当前任务面板机号带走了')
  assert.equal((await metricDrone.innerText()).trim(), 'UAV-01', '后续帧把运行指标条机号带走了')
  assert.equal(await page.locator('.drone-pin').count(), 1, '后续帧让地图多画了无人机')
})

await check('验证控制台无异常', async () => {
  const unexpected = consoleErrors.filter(text => !/Failed to load resource|ERR_FAILED|net::|WebSocket/i.test(text))
  assert.deepEqual(unexpected, [], unexpected.join(' | '))
})

await browser.close()

for (const [name, mark, detail] of results) {
  console.log(`  ${mark} ${name}${detail ? `\n      ${detail}` : ''}`)
}
const failed = results.filter(([, mark]) => mark === '✗').length
console.log(`${results.length - failed}/${results.length} 通过`)
if (failed) process.exitCode = 1
