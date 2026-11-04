/**
 * 规划航迹浏览器验收——真正打开页面，逐条核 11-04 任务判据。
 *
 * <p>判据原文：「三段着色正确；与预测轨迹不混」。三条都要在真实 DOM/Canvas 上核，
 * 因为「代码里颜色写对了」和「画出来能分开」不是一回事：图层顺序、线宽、是否被别的图层盖住，
 * 都只有跑起来才知道。
 *
 * <p>做法：起 preview 静态站点 → 用 Playwright 的 {@code addInitScript} 注入一个假 WebSocket，
 * 把一帧 {@code plan_track} 推进去（后端没起也能验前端链路），然后检查：
 * <ul>
 *   <li>地图上出现 3 条 {@code .plan-track} 折线，且三段颜色互不相同；</li>
 *   <li>三段都是点划线，<b>而</b>船舶预测轨迹仍是虚线——两者线型可区分；</li>
 *   <li>面板按契约顺序列出三段，点数与帧一致；</li>
 *   <li>契约外分段单列且原样回显；</li>
 *   <li>缺段时给出提示而不是静默消失。</li>
 * </ul>
 *
 * <p>运行：{@code node tests/plan-track-browser.mjs}（需先 pnpm build 并用 preview 起站点）
 */
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const BASE = process.env.PREVIEW_URL || 'http://127.0.0.1:4173'
const results = []
function check(name, fn) {
  return Promise.resolve().then(fn).then(() => { results.push([name, '✓', '']) },
    error => { results.push([name, '✗', error.message]) })
}

const t0 = 1793800000000
const pts = (lon0, lat0, lon1, lat1, n, tStart) => Array.from({ length: n }, (_, i) => ({
  lon: lon0 + (lon1 - lon0) * i / (n - 1),
  lat: lat0 + (lat1 - lat0) * i / (n - 1),
  altM: 40 + i * 6,
  speedMs: 11.5,
  tMs: tStart + i * 30000
}))

const FRAME = {
  orderId: 'ORD-1793800000000-1',
  routeId: 'ROUTE-A771',
  comboId: null,
  opType: '规划航迹',
  segments: [
    { kind: '去程', points: pts(115.854, 28.647, 115.862, 28.666, 6, t0) },
    { kind: '末段交接', points: pts(115.862, 28.666, 115.875, 28.681, 5, t0 + 180000) },
    { kind: '返程', points: pts(115.875, 28.681, 115.854, 28.647, 7, t0 + 330000) }
  ]
}

async function open(planFrames) {
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const consoleErrors = []
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
  page.on('pageerror', error => consoleErrors.push(`pageerror: ${error.message}`))

  // 后端未起：静态台账与预测接口都 mock 掉；WS 用假实现，开连即推 plan_track 帧。
  await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{"orders":[]}' }))
  await page.route('**/api/prediction/**', route => route.fulfill({ status: 404, body: '{}' }))
  await page.addInitScript(frames => {
    class FakeSocket {
      constructor() {
        this.readyState = 1
        setTimeout(() => {
          this.onopen?.({})
          for (const frame of frames) this.onmessage?.({ data: JSON.stringify(frame) })
        }, 120)
      }
      send() {}
      close() { this.readyState = 3 }
      addEventListener() {}
      removeEventListener() {}
    }
    window.WebSocket = FakeSocket
  }, planFrames.map(data => ({ type: 'plan_track', data })))

  await page.goto(BASE + '/#dashboard', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.plan-track-panel', { timeout: 15000 })
  return { browser, page, consoleErrors }
}

/** 从 Leaflet 的 SVG 渲染器上读实际下发的样式——读的是画出来的东西，不是源码里的常量。 */
const readLines = page => page.evaluate(() => [...document.querySelectorAll('path.plan-track')].map(node => ({
  cls: node.getAttribute('class') || '',
  stroke: node.getAttribute('stroke'),
  dash: node.getAttribute('stroke-dasharray') || '',
  width: Number(node.getAttribute('stroke-width'))
})))

// ══ 场景一：完整三段 ══
{
  const { browser, page, consoleErrors } = await open([FRAME])
  await page.waitForFunction(() => document.querySelectorAll('path.plan-track').length === 3, { timeout: 10000 })

  await check('判据1 地图画出三条规划航迹折线（三段各一条）', async () => {
    const lines = await readLines(page)
    assert.equal(lines.length, 3, `实际 ${lines.length} 条：${JSON.stringify(lines.map(l => l.cls))}`)
  })

  await check('判据1 三段着色互不相同', async () => {
    const lines = await readLines(page)
    const colors = lines.map(line => line.stroke.toUpperCase())
    assert.equal(new Set(colors).size, 3, `颜色重复：${colors}`)
    for (const color of colors) assert.ok(color && color !== 'NONE', `某段没上色：${colors}`)
  })

  await check('判据1 去程/末段交接/返程各用自己的类名（类名即口径）', async () => {
    const lines = await readLines(page)
    const classes = lines.map(line => line.cls).join(' ')
    for (const kind of ['去程', '末段交接', '返程']) {
      assert.ok(classes.includes(`plan-track-${kind}`), `缺 ${kind} 的折线，实际类名：${classes}`)
    }
  })

  await check('判据1 三段都是点划线（三段之间线型一致）', async () => {
    const lines = await readLines(page)
    const dashes = new Set(lines.map(line => line.dash))
    assert.equal(dashes.size, 1, `三段线型不一致：${[...dashes]}`)
    const only = [...dashes][0]
    assert.ok(only && only !== 'none', '规划航迹必须是虚线/点划线，实线会与无人机实飞混淆')
    assert.ok(only.split(/[\s,]+/).filter(Boolean).length >= 3, `点划线至少三段式，实际 ${only}`)
  })

  await check('判据2 与船舶预测轨迹不混：线型不同（预测=虚线，规划=点划线）', async () => {
    // 走离线演示让 ship_traj 真的画出来，再比对两种线型
    await page.evaluate(() => { document.querySelectorAll('button').forEach(b => { if (/离线演示/.test(b.textContent)) b.click() }) })
    await page.waitForSelector('path.ship-predicted-track', { timeout: 10000 })
    const shipDash = await page.evaluate(() => document.querySelector('path.ship-predicted-track')?.getAttribute('stroke-dasharray') || '')
    const planDash = (await readLines(page))[0]?.dash || ''
    assert.ok(shipDash, '船舶预测轨迹没有虚线样式，无法比较')
    assert.notEqual(planDash.replace(/\s+/g, ' '), shipDash.replace(/\s+/g, ' '),
      `规划与预测线型相同（都是 ${shipDash}），判据「不混」不成立`)
  })

  await check('判据2 与船舶预测轨迹不混：颜色不同', async () => {
    const shipStroke = await page.evaluate(() => (document.querySelector('path.ship-predicted-track')?.getAttribute('stroke') || '').toUpperCase())
    const planStrokes = (await readLines(page)).map(line => line.stroke.toUpperCase())
    assert.ok(shipStroke, '船舶预测轨迹没有描边色')
    assert.ok(!planStrokes.includes(shipStroke), `规划航迹用了船舶预测色 ${shipStroke}`)
  })

  await check('判据2 两图层共存：预测轨迹画完后规划三条仍在（不被 clearLayers 误清）', async () => {
    const lines = await readLines(page)
    assert.equal(lines.length, 3, `预测帧到达后规划航迹剩 ${lines.length} 条——图层串了`)
    for (const line of lines) assert.equal(line.dash, '9 6 2 6', '线型被改写')
  })

  await check('判据1 面板按契约顺序列出三段：去程→末段交接→返程', async () => {
    const names = await page.locator('.plan-track-card .plan-track-segment .name').allInnerTexts()
    assert.deepEqual(names.slice(0, 3), ['计划去程', '计划末段交接', '计划返程'], `实际 ${names}`)
  })

  await check('判据1 面板点数与帧一致（6 / 5 / 7）', async () => {
    const metas = await page.locator('.plan-track-card .plan-track-segment .meta').allInnerTexts()
    assert.match(metas[0], /6 个航点/, metas[0])
    assert.match(metas[1], /5 个航点/, metas[1])
    assert.match(metas[2], /7 个航点/, metas[2])
  })

  await check('面板显示订单号与航迹编号', async () => {
    const head = await page.locator('.plan-track-card-head').first().innerText()
    assert.match(head, /ORD-1793800000000-1/)
    assert.match(head, /ROUTE-A771/)
  })

  await check('图例出现三条计划航迹条目', async () => {
    const legend = await page.locator('.map-guide').innerText()
    for (const label of ['计划去程', '计划末段交接', '计划返程']) assert.match(legend, new RegExp(label))
  })

  await check('控制台无异常（除后端未起的请求失败）', async () => {
    const real = consoleErrors.filter(text => !/Failed to load resource|WebSocket|net::ERR/i.test(text))
    assert.equal(real.length, 0, real.join(' | '))
  })

  await browser.close()
}

// ══ 场景二：缺返程段 + 契约外分段 ══
{
  const partial = {
    ...FRAME,
    orderId: 'ORD-1793800000000-2',
    routeId: 'ROUTE-B002',
    segments: [
      FRAME.segments[0],
      { kind: '待机盘旋', points: pts(115.862, 28.666, 115.870, 28.672, 4, t0 + 180000) }
    ]
  }
  const { browser, page, consoleErrors } = await open([partial])
  await page.waitForFunction(() => document.querySelectorAll('path.plan-track').length === 2, { timeout: 10000 })

  await check('缺段提示：显示「缺 末段交接、返程」而不是静默不画', async () => {
    const warns = await page.locator('.plan-track-warn').allInnerTexts()
    const missing = warns.find(text => text.includes('缺分段'))
    assert.ok(missing, `未见缺段提示，实际提示：${warns.join(' | ') || '（无）'}`)
    assert.match(missing, /末段交接/)
    assert.match(missing, /返程/)
  })

  await check('两条提示并列显示：契约外分段不遮蔽缺段提示', async () => {
    const warns = await page.locator('.plan-track-warn').allInnerTexts()
    assert.equal(warns.length, 2, `应同时给出两条提示，实际 ${warns.length} 条：${warns.join(' | ')}`)
    assert.ok(warns.some(text => text.includes('契约外分段')))
    assert.ok(warns.some(text => text.includes('缺分段')))
  })

  await check('契约外分段单列：原样回显「待机盘旋」，不与三段混排', async () => {
    const names = await page.locator('.plan-track-card .plan-track-segment .name').allInnerTexts()
    const extra = names.find(name => name.includes('待机盘旋'))
    assert.ok(extra, `未见契约外分段，实际 ${names}`)
    assert.equal(extra, '未知分段（待机盘旋）', '契约外分段应原样带出原值')
  })

  await check('契约外分段在地图上用警示色画，不是三段配色之一', async () => {
    const lines = await readLines(page)
    const unknown = lines.find(line => line.cls.includes('plan-track-unknown'))
    assert.ok(unknown, `未画出契约外分段：${lines.map(l => l.cls)}`)
    assert.equal(unknown.stroke.toUpperCase(), '#7C2D3A')
  })

  await check('缺段场景控制台无异常', async () => {
    const real = consoleErrors.filter(text => !/Failed to load resource|WebSocket|net::ERR/i.test(text))
    assert.equal(real.length, 0, real.join(' | '))
  })

  await browser.close()
}

// ══ 场景三：坏帧不透传 ══
{
  const broken = { orderId: '', segments: FRAME.segments, routeId: 'X', opType: '规划航迹' }
  const { browser, page } = await open([broken])
  await page.waitForTimeout(800)

  await check('缺 orderId 的帧被丢弃：面板显示空态而不是半个航迹', async () => {
    const lines = await page.evaluate(() => document.querySelectorAll('path.plan-track').length)
    assert.equal(lines, 0, `坏帧仍被画了 ${lines} 条`)
    const empty = await page.locator('.plan-track-empty').count()
    assert.equal(empty, 1, '应显示空态提示')
  })

  await browser.close()
}

let failed = 0
for (const [name, mark, detail] of results) {
  if (mark === '✗') failed++
  console.log(`  ${mark} ${name}${detail ? `\n      ${detail}` : ''}`)
}
console.log(`\n${results.length - failed}/${results.length} 通过`)
process.exit(failed ? 1 : 0)
