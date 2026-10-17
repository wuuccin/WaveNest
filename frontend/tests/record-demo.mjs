import { chromium } from 'playwright'
import { mkdir, unlink } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const projectRoot = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const output = join(projectRoot, 'demo')
const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
await mkdir(output, { recursive: true })
// 浏览器路径可用环境变量 BROWSER_PATH 覆盖；未指定时不传 executablePath，交给 playwright 自动解析
const launchOptions = { headless: true }
if (process.env.BROWSER_PATH) launchOptions.executablePath = process.env.BROWSER_PATH
const browser = await chromium.launch(launchOptions)
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  recordVideo: { dir: output, size: { width: 1440, height: 900 } }
})
const page = await context.newPage()
await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
await page.getByRole('button', { name: '离线演示' }).click()
await page.waitForTimeout(1200)
await page.getByRole('button', { name: /^开始分步演示/ }).click()
await page.waitForTimeout(1100)
await page.getByRole('button', { name: '创建订单，开始演示' }).click()
await page.locator('.guided-story').waitFor()
await page.waitForTimeout(6000)
for (let i = 0; i < 6; i++) {
  await page.getByRole('button', { name: /我看懂了，下一步/ }).click()
  await page.waitForTimeout(6000)
}
await page.getByRole('button', { name: '流程讲完，返回总览' }).click()
await page.getByRole('button', { name: '查看已有任务回放' }).click()
await page.waitForTimeout(800)
await page.getByRole('slider', { name: '回放进度' }).fill('3')
await page.waitForTimeout(1000)
await page.getByRole('slider', { name: '回放进度' }).fill('6')
await page.waitForTimeout(1200)
const video = page.video()
await page.close()
const temporary = await video.path()
await video.saveAs(join(output, 'guided-demo-offline.webm'))
await context.close()
await browser.close()
await unlink(temporary)
console.log(join(output, 'guided-demo-offline.webm'))
