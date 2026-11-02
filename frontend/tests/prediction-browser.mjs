/**
 * 预测看台浏览器验收：真正打开页面，逐条核任务清单的三个判据。
 *
 * <p>不用肉眼看，是因为「看着有」和「判据成立」不是一回事：
 * 列表可能被 CSS 挡住、叠加可能画在视野外、标记可能只是颜色变了但没文字。
 *
 * <p>运行：{@code node tests/prediction-browser.mjs}（需先 pnpm build，用 preview 起静态站点）
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.PREVIEW_URL || 'http://127.0.0.1:4173'
const results = []
function check(name, fn) {
  return Promise.resolve().then(fn).then(() => { results.push([name, '✓', '']) },
    error => { results.push([name, '✗', error.message]) })
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const consoleErrors = []
page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
page.on('pageerror', error => consoleErrors.push(`pageerror: ${error.message}`))

// 后端没起：/api 会失败。预测看台在 404/501 时回落样例，其它错误如实报错。
// 这里把预测接口显式 mock 成 404，模拟“接口还没上线”的真实阶段。
// 注意：Playwright 后注册的路由先匹配——所以「预测接口 404」这条必须放在通配的 /api/** 之后
  
await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{"orders":[]}' }))
await page.route('**/api/prediction/**', route => route.fulfill({ status: 404, body: '{}' }))

await page.goto(BASE + '/#dashboard', { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.prediction-panel', { timeout: 15000 })
await page.waitForSelector('.prediction-table tbody tr', { timeout: 15000 })

const rows = page.locator('.prediction-table tbody tr')

await check('判据1 面板列出全部在航船（样例 5 条，一条不少）', async () => {
  const count = await rows.count()
  assert.equal(count, 5, `实际 ${count} 行`)
  const header = await page.locator('.prediction-panel .count-badge').innerText()
  assert.match(header, /5\s*艘在航/, `表头计数不符：${header}`)
})

await check('判据1 六列齐全：MMSI/版本/前提/航向/预测点数/有效期剩余', async () => {
  const headers = await page.locator('.prediction-table thead th').allInnerTexts()
  assert.deepEqual(headers.map(text => text.trim()), ['MMSI', '版本', '前提', '航向', '预测点数', '有效期剩余'])
})

await check('判据1 每行都有 MMSI，且表头计数与行数一致', async () => {
  const mmsis = await page.locator('.prediction-table tbody tr td:first-child strong').allInnerTexts()
  assert.equal(mmsis.length, 5)
  for (const mmsi of mmsis) assert.match(mmsi.trim(), /^\d{9}$/, `MMSI 形状不对：${mmsi}`)
  assert.equal(new Set(mmsis.map(text => text.trim())).size, 5, '有重复行')
})

await check('判据3 非 VALID 的船有醒目标记：行级 flagged + 徽标 + 顶部计数条', async () => {
  const flagged = page.locator('.prediction-table tbody tr.flagged')
  const flaggedCount = await flagged.count()
  assert.equal(flaggedCount, 3, `flagged 行数 ${flaggedCount}，应为 INVALID/UNKNOWN/异常值 共 3 条`)

  const flagbar = await page.locator('.prediction-flagbar').innerText()
  assert.match(flagbar, /3 条前提非 VALID/, `顶部计数条文案不符：${flagbar}`)

  // 徽标文字与背景色都要能区分：只变颜色对色弱不可用，所以还断言文字
  const invalidChip = page.locator('.premise-chip.invalid').first()
  assert.equal((await invalidChip.innerText()).trim(), 'INVALID')
  const background = await invalidChip.evaluate(node => getComputedStyle(node).backgroundColor)
  const validBackground = await page.locator('.premise-chip.valid').first().evaluate(node => getComputedStyle(node).backgroundColor)
  assert.notEqual(background, validBackground, 'INVALID 徽标与 VALID 同色，等于没标')
})

await check('判据3 契约外取值单列 unexpected，并与 INVALID 区分开', async () => {
  assert.equal(await page.locator('.premise-chip.unexpected').count(), 1, 'PENDING 未被单列')
  const caption = await page.locator('.premise-chip.unexpected').first().locator('xpath=following-sibling::small').innerText()
  assert.match(caption, /未知取值 PENDING/, `异常取值未原样回显：${caption}`)
})

await check('判据2 点选一行后地图叠加该船预测点列', async () => {
  const before = await page.locator('.prediction-end-pin').count()
  assert.equal(before, 0, '未点选时不应有叠加')

  await rows.first().click()
  await page.waitForSelector('.prediction-end-pin', { timeout: 5000 })
  assert.equal(await page.locator('.prediction-track').count(), 1, '应有 1 条预测折线')
  assert.equal(await page.locator('.prediction-end-pin').count(), 1, '应有 1 个终点标记')
  assert.equal(await page.locator('.prediction-ellipse').count(), 2, '起点与终点各一个置信椭圆')

  const track = await page.locator('.prediction-end-pin').innerText()
  assert.match(track, /413700001/, `叠加的船不对：${track}`)

  const selected = await page.locator('.prediction-selected').innerText()
  assert.match(selected, /413700001/)
  assert.match(selected, /12 个预测点/, `叠加点数不符：${selected}`)
})

await check('判据2 改选另一条船，叠加跟着换（不残留上一条）', async () => {
  await rows.nth(1).click()
  await page.waitForFunction(() => {
    const pin = document.querySelector('.prediction-end-pin')
    return pin && pin.textContent.includes('413700002')
  }, { timeout: 5000 })
  assert.equal(await page.locator('.prediction-track').count(), 1, '两条船同时叠加了')
  const pin = await page.locator('.prediction-end-pin').innerText()
  assert.match(pin, /413700002/)
  // 这张船的 premise 是 INVALID，地图上也要能看出来
  assert.equal(await page.locator('.prediction-end-pin-invalid').count(), 1, 'INVALID 船在地图上没变红')
})

await check('判据2 再点一次取消叠加', async () => {
  await rows.nth(1).click()
  await page.waitForFunction(() => document.querySelectorAll('.prediction-end-pin').length === 0, { timeout: 5000 })
})

await check('数据源如实标注：接口 404 时明说「接口未就绪」而不是装作实时', async () => {
  const source = await page.locator('.prediction-source').innerText()
  assert.match(source, /离线样例（接口未就绪）/, `数据源标注不符：${source}`)
  const notice = await page.locator('.prediction-notice').innerText()
  assert.match(notice, /404/, '未把 HTTP 状态码写出来，排障时看不到')
})

await check('控制台无异常（除后端未启动引起的请求失败）', async () => {
  // WS 失败同样是「后端没起」的必然结果，不属于本面板的缺陷
  const unexpected = consoleErrors.filter(text => !/Failed to load resource|ERR_FAILED|net::|WebSocket/i.test(text))
  assert.deepEqual(unexpected, [], unexpected.join(' | '))
})

await page.screenshot({ path: 'tests/__screenshots__/prediction-dashboard.png', fullPage: false })
await browser.close()

for (const [name, mark, detail] of results) {
  console.log(`  ${mark} ${name}${detail ? `\n      ${detail}` : ''}`)
}
const failed = results.filter(([, mark]) => mark === '✗').length
console.log(`\n${results.length - failed}/${results.length} 通过`)
if (failed) process.exitCode = 1
