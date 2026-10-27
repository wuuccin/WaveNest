import { chromium } from 'playwright'

const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {}) })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.addInitScript(() => localStorage.setItem('wavenest-source', 'mock'))
await page.goto(`${process.env.FRONTEND_URL || 'http://127.0.0.1:5173'}/#dashboard`, { waitUntil: 'domcontentloaded' })
await page.getByRole('button', { name: '离线演示' }).click()
console.log('dashboard blocks:', await page.evaluate(() => Object.fromEntries(['.page-heading', '.operations-hero', '.workflow-grid', '.metric-strip', '.dashboard-layout', '.dashboard-layout .map-panel', '.mission-column', '.mission-panel', '.mission-column .event-panel', '.demo-panel'].map(selector => {
  const rect = document.querySelector(selector).getBoundingClientRect()
  return [selector, [Math.round(rect.top), Math.round(rect.bottom), Math.round(rect.height)]]
}))))
const check = async (name, selector) => {
  const position = await page.locator(selector).first().evaluate(element => ({
    bottom: Math.round(element.getBoundingClientRect().bottom),
    scrollHeight: document.documentElement.scrollHeight, viewport: innerHeight
  }))
  console.log(`${name}: ${JSON.stringify(position)}`)
  if (position.bottom > position.viewport + 5) throw new Error(`${name}: primary action is below first screen`)
  if (position.scrollHeight > position.viewport + 2) throw new Error(`${name}: desktop page still needs vertical scrolling`)
}
await check('dashboard', '.demo-panel')
await page.getByRole('link', { name: '船员下单' }).click()
await check('crew', '.crew-button')
await page.getByRole('link', { name: '调度台' }).click()
await check('console', '.intervene-grid')
await page.getByRole('link', { name: '任务回放' }).click()
await check('replay', '.replay-range')
await check('replay map', '.replay-map-panel .map-shell')
await page.getByRole('link', { name: '分步演示' }).click()
await check('guide order form', '.guided-form-card .crew-button')
await page.getByRole('button', { name: '创建订单，开始演示' }).click()
await page.locator('.guided-story').waitFor()
for (let index = 0; index < 2; index += 1) await page.getByRole('button', { name: /下一步：/ }).click()
await check('guide decision', '.guided-decision-actions')
await check('guide map', '.guided-map-panel .map-shell')
await page.getByRole('button', { name: /① 原机继续配送/ }).click()
await check('guide continue', '.guided-next-action')
await page.setViewportSize({ width: 1366, height: 768 })
await check('1366 guide map', '.guided-map-panel .map-shell')
await page.getByRole('link', { name: '运行总览', exact: true }).click()
await check('1366 dashboard', '.demo-panel')
await page.getByRole('link', { name: '船员下单' }).click()
await check('1366 crew', '.crew-button')
await page.getByRole('link', { name: '调度台' }).click()
await check('1366 console', '.intervene-grid')
await page.getByRole('link', { name: '任务回放' }).click()
await check('1366 replay map', '.replay-map-panel .map-shell')
await browser.close()
