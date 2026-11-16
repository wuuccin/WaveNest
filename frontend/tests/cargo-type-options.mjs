/**
 * 配送品类验收，分别在运行页面与模块层核对判据。
 *
 * <p>字典接口用页面路由拦截，因此本脚本不需要启动后端服务。
 * 运行：先执行 pnpm build 与 pnpm preview，再执行 node tests/cargo-type-options.mjs
 */
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'
import { createServer } from 'vite'

const BASE = process.env.PREVIEW_URL || 'http://127.0.0.1:4173'
const CARGO_TYPES = ['医疗物资', '应急药品', '食品', '生活物资']
const results = []
function check(name, fn) {
  return Promise.resolve().then(fn).then(() => { results.push([name, '通过', '']) },
    error => { results.push([name, '未通过', error.message]) })
}

const browser = await chromium.launch()
const vite = await createServer({
  root: fileURLToPath(new URL('..', import.meta.url)),
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error'
})
try {
  await check('验证下拉选项等于后端有效承运集合', async () => {
    const page = await browser.newPage()
    try {
      await page.route('**/api/cargo-types', route => route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ cargoTypes: CARGO_TYPES })
      }))
      await page.goto(BASE)
      await page.getByRole('link', { name: '船员下单' }).click()
      const select = page.locator('.form-panel .crew-form').getByLabel('配送品类')
      await select.locator('option', { hasText: '应急药品' }).waitFor({ state: 'attached', timeout: 15000 })
      const values = await select.locator('option').evaluateAll(
        nodes => nodes.map(node => node.value).filter(Boolean))
      assert.deepEqual(values, CARGO_TYPES)
    } finally { await page.close() }
  })

  await check('阻止字典加载失败时提交', async () => {
    const page = await browser.newPage()
    try {
      await page.route('**/api/cargo-types', route => route.fulfill({
        status: 500, contentType: 'application/json', body: '{}'
      }))
      await page.goto(BASE)
      await page.getByRole('link', { name: '船员下单' }).click()
      const form = page.locator('.form-panel .crew-form')
      await form.getByRole('button', { name: '重试' }).waitFor({ timeout: 15000 })
      assert.equal(await form.getByRole('button', { name: /提交配送订单/ }).isDisabled(), true)
    } finally { await page.close() }
  })

  await check('保留离线模式中的用户所选货型', async () => {
    const page = await browser.newPage()
    try {
      await page.goto(BASE)
      await page.getByRole('button', { name: '离线演示' }).click()
      await page.getByRole('link', { name: '船员下单' }).click()
      const form = page.locator('.form-panel .crew-form')
      await form.getByLabel('配送品类').selectOption('食品')
      await form.getByRole('button', { name: /提交配送订单/ }).click()
      await page.locator('.crew-status-panel').getByText('配送品类：食品')
        .waitFor({ timeout: 15000 })
    } finally { await page.close() }
  })

  await check('保留订单状态推进后的货型', async () => {
    const { buildOfflineGuideSteps, buildContinueGuideSteps } =
      await vite.ssrLoadModule('/src/realtime/guided.js')
    const base = buildOfflineGuideSteps('ORD-TEST', '食品')
    const steps = buildContinueGuideSteps(base, 'ORD-TEST', '食品')
    const frames = steps.flatMap(step => step.frames).filter(([type]) => type === 'order_state')
    assert.ok(frames.length > 0, '继续配送分支必须产出订单状态帧')
    for (const [, data] of frames) {
      assert.equal(data.goodsType, '食品', '高版本状态帧必须携带配送品类')
    }
  })

  await check('保留离线引导中的用户所选货型', async () => {
    const { buildOfflineGuideSteps } = await vite.ssrLoadModule('/src/realtime/guided.js')
    const steps = buildOfflineGuideSteps('ORD-TEST', '食品')
    const frames = steps.flatMap(step => step.frames).filter(([type]) => type === 'order_state')
    assert.ok(frames.length > 0, '离线引导必须产出订单状态帧')
    for (const [, data] of frames) {
      assert.equal(data.goodsType, '食品', '离线引导状态帧必须继承用户所选品类')
    }
  })
} finally {
  await browser.close()
  await vite.close()
}

for (const [name, mark, error] of results) {
  console.log(`${mark} ${name}${error ? ` · ${error}` : ''}`)
}
if (results.some(([, mark]) => mark === '未通过')) process.exitCode = 1
