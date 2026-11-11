/**
 * 预测看台按需叠加回归。
 *
 * 运行：node tests/prediction-overlay-optin.mjs（需先 pnpm build，用 preview 起静态站点）
 *
 * 本脚本显式注入船舶首帧，让 useShipStore 自动选中船舶。既有 prediction-browser.mjs
 * 在 WebSocket 连不上时回落 REST 轮询，selectedMmsi 始终为空，覆盖不到自动选中触发叠加的路径。
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.PREVIEW_URL || 'http://127.0.0.1:4173'
const results = []
function check(name, fn) {
  return Promise.resolve().then(fn).then(() => { results.push([name, '✓', '']) },
    error => { results.push([name, '✗', error.message]) })
}

const trackPoints = [
  { lon: 115.858, lat: 28.663, t: 1767000000000, sigmaAlong: 120, sigmaCross: 40 },
  { lon: 115.862, lat: 28.670, t: 1767000060000, sigmaAlong: 160, sigmaCross: 55 },
  { lon: 115.868, lat: 28.678, t: 1767000120000, sigmaAlong: 210, sigmaCross: 70 }
]
const shipFrame = mmsi => ({
  mmsi,
  lon: trackPoints[0].lon,
  lat: trackPoints[0].lat,
  points: trackPoints,
  courseDeg: 31.2,
  t0: 1767000000000,
  version: 1
})
const shipFrames = [
  { type: 'ship_traj', data: shipFrame('413700001') },
  { type: 'ship_traj', data: shipFrame('413700002') }
]

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const consoleErrors = []
page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
page.on('pageerror', error => consoleErrors.push(`pageerror: ${error.message}`))

let injections = 0
// 预测接口置为 404，让面板回落到同结构离线样例。后注册的路由先匹配。
await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{"orders":[]}' }))
await page.route('**/api/prediction/**', route => route.fulfill({ status: 404, body: '{}' }))
// 注入首帧船舶数据，让 ship.selectedMmsi 被自动选中。
await page.routeWebSocket('**/ws', socket => {
  injections++
  setTimeout(() => shipFrames.forEach(frame => socket.send(JSON.stringify(frame))), 200)
})

await page.goto(BASE + '/#dashboard', { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.prediction-panel', { timeout: 15000 })
await page.waitForSelector('.prediction-table tbody tr', { timeout: 15000 })
await page.locator('.ship-confidence-ellipse').first().waitFor({ state: 'attached', timeout: 15000 })

const rows = page.locator('.prediction-table tbody tr')

await check('验证注入船舶首帧后看台层椭圆数量为零', async () => {
  assert.equal(await page.locator('.prediction-ellipse').count(), 0, '未经点选就叠加了看台预测')
  assert.equal(await page.locator('.prediction-track').count(), 0, '未经点选就画了看台预测折线')
})

await check('验证注入船舶首帧后常驻层椭圆仍然存在', async () => {
  const count = await page.locator('.ship-confidence-ellipse').count()
  assert.ok(count > 0, `常驻船舶层椭圆被一起删掉了，实际 ${count} 个`)
})

await check('验证图例标注常驻船舶层椭圆', async () => {
  const text = await page.locator('.map-guide').innerText()
  assert.match(text, /常驻船舶层椭圆/, '图例没有把常驻层单独列出来')
  assert.equal(await page.locator('.map-symbol-ellipse').count(), 1)
})

await check('验证切换船舶下拉后看台层椭圆不出现', async () => {
  const select = page.getByLabel('选择船舶')
  await select.waitFor({ state: 'attached', timeout: 10000 })
  await select.selectOption('413700002')
  await page.waitForTimeout(300)
  assert.equal(await page.locator('.prediction-ellipse').count(), 0, '下拉选船触发了看台叠加')
})

await check('验证点选预测看台行后看台层椭圆数量为二', async () => {
  await rows.first().click()
  await page.locator('.prediction-ellipse').first().waitFor({ state: 'attached', timeout: 10000 })
  assert.equal(await page.locator('.prediction-ellipse').count(), 2, '起点、终点应各一个椭圆')
  const text = await page.locator('.map-guide').innerText()
  assert.match(text, /预测看台层椭圆/, '图例没有把看台层单独列出来')
  assert.equal(await page.locator('.map-symbol-ellipse-overlay').count(), 1)
})

await check('验证再次点选同一行后看台层椭圆归零', async () => {
  await rows.first().click()
  await page.waitForFunction(() => document.querySelectorAll('.prediction-ellipse').length === 0, null, { timeout: 10000 })
  assert.equal(await page.locator('.map-symbol-ellipse-overlay').count(), 0)
})

await check('验证切换数据来源后看台层椭圆不出现', async () => {
  await page.getByRole('button', { name: '离线演示' }).click()
  await page.getByRole('button', { name: '实时服务' }).click()
  for (let attempt = 0; attempt < 40 && injections < 2; attempt++) await page.waitForTimeout(100)
  assert.ok(injections >= 2, `切换数据来源后 WebSocket 没有重新连接，注入次数 ${injections}`)
  await page.waitForTimeout(300)
  assert.equal(await page.locator('.prediction-ellipse').count(), 0, '切换数据来源后重新叠加了看台预测')
})

await check('验证控制台无异常', async () => {
  const unexpected = consoleErrors.filter(text => !/Failed to load resource|ERR_FAILED|net::|WebSocket/i.test(text))
  assert.deepEqual(unexpected, [], unexpected.join(' | '))
})

const shipEllipseCount = await page.locator('.ship-confidence-ellipse').count()
const overlayEllipseCount = await page.locator('.prediction-ellipse').count()
await browser.close()

for (const [name, mark, detail] of results) {
  console.log(`  ${mark} ${name}${detail ? `\n      ${detail}` : ''}`)
}
const failed = results.filter(([, mark]) => mark === '✗').length
console.log(`\n常驻船舶层椭圆 ${shipEllipseCount} 个，预测看台层椭圆 ${overlayEllipseCount} 个`)
console.log(`${results.length - failed}/${results.length} 通过`)
if (failed) process.exitCode = 1
