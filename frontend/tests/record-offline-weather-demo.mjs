import { chromium } from 'playwright'
import { mkdir, unlink } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const outputDir = join(dirname(frontendRoot), 'demo')
const outputFile = join(outputDir, 'wavenest-weather-offline-demo.webm')
await mkdir(outputDir, { recursive: true })
const browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {}) })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, recordVideo: { dir: outputDir, size: { width: 1440, height: 900 } } })
const page = await context.newPage()
let video

async function pause(ms = 3200) { await page.waitForTimeout(ms) }
async function caption(title, detail) {
  await page.evaluate(({ title, detail }) => {
    let banner = document.getElementById('offline-demo-caption')
    if (!banner) {
      banner = document.createElement('div')
      banner.id = 'offline-demo-caption'
      banner.style.cssText = 'position:fixed;z-index:10000;left:50%;bottom:14px;transform:translateX(-50%);width:min(1000px,calc(100vw - 40px));padding:13px 18px;border-radius:11px;background:rgba(7,32,43,.96);color:white;font-family:Microsoft YaHei UI,Arial,sans-serif;pointer-events:none;box-shadow:0 10px 34px #0005'
      banner.innerHTML = '<strong style="display:block;font-size:17px"></strong><span style="display:block;margin-top:5px;font-size:13px;color:#d9efec"></span>'
      document.body.append(banner)
    }
    banner.querySelector('strong').textContent = title
    banner.querySelector('span').textContent = detail
  }, { title, detail })
  await pause()
}

try {
  await page.addInitScript(() => localStorage.setItem('wavenest-source', 'mock'))
  await page.goto(process.env.FRONTEND_URL || 'http://127.0.0.1:5173/#dashboard', { waitUntil: 'domcontentloaded' })
  video = page.video()
  await caption('WaveNest 界面流程演示', '本段使用离线样例，展示界面操作和风速突变故事线。状态由样例帧驱动。')
  await page.getByRole('button', { name: '开始分步演示 →', exact: true }).click()
  await page.getByLabel('配送品类').selectOption('应急药品')
  await caption('填写配送订单', '选择配送品类，确认船舶、重量和紧急程度，然后创建样例订单。')
  await page.getByRole('button', { name: '创建订单，开始演示' }).click()
  await page.locator('.guided-story').waitFor()
  const stages = [
    ['预测会面', '地图展示船舶预测轨迹和预计会面点。'],
    ['起飞去程', '首架无人机出发，界面显示机号、电量和订单状态。'],
    ['风速突变', '风速升至超限区间，气象超限告警出现在事件流。'],
    ['原机返航', '本例的安全处置选择返航，订单状态和无人机阶段分别展示。'],
    ['回收与风况回落', '原机回收货物后，风况回落，订单重新进入调度。'],
    ['重新派单与交接', '第二架无人机从货物所在机巢出发并与船舶交接。'],
    ['返航完成', '演示订单完成，可以进入任务回放逐步查看记录。']
  ]
  for (const [index, [title, detail]] of stages.entries()) {
    await page.getByText(`第 ${index + 1} / 7 步`).waitFor()
    await caption(title, detail)
    if (index < stages.length - 1) await page.getByRole('button', { name: /下一步：/ }).click()
  }
  await page.getByRole('button', { name: '流程讲完，返回总览' }).click()
  await page.getByRole('button', { name: '查看已有任务回放' }).click()
  await caption('查看任务回放', '回放按阶段展示样例记录。本视频只证明界面流程，不作为正式服务联跑证据。')
  await page.close()
  await video.saveAs(outputFile)
  const temporary = await video.path()
  if (resolve(temporary) !== resolve(outputFile) && resolve(temporary).startsWith(resolve(outputDir))) await unlink(temporary)
  console.log(`SAVED=${outputFile}`)
} finally {
  await context.close()
  await browser.close()
}
