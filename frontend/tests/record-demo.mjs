import { chromium } from 'playwright'
import { mkdir, unlink } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const projectRoot = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const output = join(projectRoot, 'demo')
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
await page.goto('http://127.0.0.1:5173', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
for (let i = 0; i < 6; i++) {
  await page.getByRole('button', { name: '下一段' }).click()
  await page.waitForTimeout(1500)
}
await page.getByRole('button', { name: '查看回放' }).click()
await page.waitForTimeout(800)
await page.getByRole('slider', { name: '回放进度' }).fill('3')
await page.waitForTimeout(1000)
await page.getByRole('slider', { name: '回放进度' }).fill('6')
await page.waitForTimeout(1200)
const video = page.video()
await page.close()
const temporary = await video.path()
await video.saveAs(join(output, 'six-stage-demo.webm'))
await context.close()
await browser.close()
await unlink(temporary)
console.log(join(output, 'six-stage-demo.webm'))
