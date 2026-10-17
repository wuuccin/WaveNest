import { chromium } from 'playwright'
import { mkdir, unlink } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const projectRoot = dirname(dirname(dirname(fileURLToPath(import.meta.url))))
const outputDir = join(projectRoot, 'demo')
const outputFile = join(outputDir, 'wavenest-complete-demo.webm')
const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
await mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({ headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {}) })
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  recordVideo: { dir: outputDir, size: { width: 1440, height: 900 } }
})
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))

async function caption(title, explanation, ms = 2800) {
  await page.evaluate(({ title, explanation }) => {
    let overlay = document.getElementById('demo-video-caption')
    if (!overlay) {
      overlay = document.createElement('div')
      overlay.id = 'demo-video-caption'
      overlay.style.cssText = 'position:fixed;z-index:10000;left:50%;bottom:13px;transform:translateX(-50%);width:min(960px,calc(100vw - 40px));padding:10px 18px;border:1px solid rgba(139,225,211,.5);border-radius:11px;color:#fff;background:rgba(8,39,50,.94);box-shadow:0 9px 30px rgba(0,0,0,.25);font-family:Microsoft YaHei UI,Arial,sans-serif;text-align:center;pointer-events:none;'
      overlay.innerHTML = '<strong style="display:block;font-size:16px"></strong><span style="display:block;margin-top:3px;font-size:12px;color:#d4eee9"></span>'
      document.body.append(overlay)
    }
    overlay.querySelector('strong').textContent = title
    overlay.querySelector('span').textContent = explanation
  }, { title, explanation })
  await page.waitForTimeout(ms)
}
async function next(label, explanation, ms = 3100) {
  await page.getByRole('button', { name: /下一步：/ }).click()
  await caption(label, explanation, ms)
}

let video
try {
  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.header-status').getByText('WebSocket 已连接').waitFor({ timeout: 12000 })
  video = page.video()
  await caption('WaveNest · 沿江智能配送系统', '这段视频展示电脑端主要功能，以及船舶变向后的两种处理。', 3500)
  await caption('运行总览', '四个入口分别是船员下单、订单调度、异常处置和任务回放。', 3000)

  await page.getByRole('link', { name: '船员下单' }).click()
  await caption('船员下单 · 先填写配送需求', '主要表单和订单反馈同时出现在电脑首屏。', 2200)
  const form = page.locator('.form-panel .crew-form')
  await form.locator('input').nth(1).fill('急救药品及绷带')
  await form.locator('input').nth(2).fill('2.5')
  await form.locator('select').selectOption('紧急')
  await caption('提交订单到后端', '目标船舶、物资、重量和紧急程度都可以在这里填写。', 2000)
  await form.getByRole('button', { name: /提交配送订单/ }).click()
  await form.getByText(/已由服务接收/).waitFor({ timeout: 12000 })
  await caption('订单已受理', '右侧显示订单反馈；调度台会列出这笔订单。', 3000)

  await page.getByRole('link', { name: '调度台' }).click()
  await page.locator('.order-table tbody tr').first().click()
  await caption('订单调度', '左侧选择订单，右侧查看派单闸门、人工干预和状态进展。', 3200)
  await page.getByRole('link', { name: '运行总览', exact: true }).click()
  await page.locator('.workflow-card').nth(2).click()
  await caption('异常处置', '从总览进入时，人工干预操作区会排在最醒目的位置。', 3200)

  await page.getByRole('link', { name: '分步演示' }).click()
  await caption('分步演示 · 创建一笔演示订单', '这笔订单会生成后端返航仿真记录；原机续送是前端情境推演。', 3300)
  await page.locator('.guided-form-card .crew-form input').nth(1).fill('应急救援物资')
  await page.getByRole('button', { name: '创建订单，开始演示' }).click()
  await caption('等待后端生成仿真记录', '记录生成后停在第一步，后续步骤由操作员逐一推进。', 2700)
  await page.locator('.guided-story').waitFor({ timeout: 30000 })
  const orderId = (await page.locator('.guided-order-id').textContent()).replace(/^订单\s*/, '')
  if (!orderId.startsWith('ORD-')) throw new Error(`后端订单编号异常：${orderId}`)
  await caption('第 1 步 · 预测会面', '地图显示船舶预测航迹和预计会面点。', 3500)
  await next('第 2 步 · 原机起飞去程', 'UAV-01 携带物资飞向预计会面点；订单状态变为去程中。', 3600)
  await next('第 3 步 · 船舶变向', '灰线是原预测航迹，绿线是更新后的预测；会面点也随之变化。', 4200)
  await caption('重新评估会面方案', '船舶变向并不自动触发返航。现在由你选择原机续送或放弃本次会面。', 4200)

  await page.getByRole('button', { name: /① 原机继续配送/ }).click()
  await caption('选择一：原机继续配送', '蓝色粗线表示 UAV-01 追向新会面点。这一分支明确标为前端情境推演。', 4300)
  await next('原机接近新会面点', '继续检查船位、剩余电量和会面时间窗口。', 3300)
  await next('UAV-01 与船舶交货', '这次没有第二架无人机；原机在更新后的会面点完成交接推演。', 3900)
  await next('原机返航完成', '交货后原机返航。当前完成状态属于前端情境推演。', 3900)

  for (let index = 0; index < 4; index += 1) {
    await page.getByRole('button', { name: /上一步/ }).click()
    await page.waitForTimeout(600)
  }
  await caption('回到变向步骤 · 重新选择', '同一笔订单可以切换处理方案，地图上不会预先选定返航。', 3200)
  await page.getByRole('button', { name: /② 放弃本次会面/ }).click()
  await caption('选择二：放弃会面，原机带货返航', '红色粗线通向机巢；这个分支使用后端现有的仿真回放记录。', 4400)
  await next('原机回收，货物重入队', '只有确认原机和货物回收后，才能重新调度下一架无人机。', 3900)
  await next('第二架无人机重派并交货', 'UAV-02 从货物所在机巢出发，与船舶交接；没有空中交货。', 4200)
  await next('第二架无人机返航完成', '后端仿真记录显示订单完成，配送流程闭环。', 3900)
  await page.locator('.guided-history summary').click()
  await caption('订单进展与事件流', '展开后可以核对每一步的订单状态和告警记录。', 3500)
  await page.getByRole('button', { name: '流程讲完，返回总览' }).click()

  await page.getByRole('link', { name: '任务回放' }).click()
  await page.locator('.replay-list li').first().waitFor({ timeout: 12000 })
  await caption('任务回放', '左侧选任务并拖动进度条，右侧地图显示对应历史帧。', 3400)
  const slider = page.getByRole('slider', { name: '回放进度' })
  const max = Number(await slider.getAttribute('max'))
  await slider.fill(String(Math.max(0, Math.floor(max / 2))))
  await caption('查看中间状态', '可以逐帧检查船舶轨迹、无人机位置和订单状态。', 3100)
  await slider.fill(String(max))
  await caption('查看最终记录', '回放结果用于复盘；前端续送情境不会冒充后端历史记录。', 3700)
  await page.locator('.replay-history summary').click()
  await caption('按需展开订单进展', '详细时间线保留在折叠区，平时不会把回放控制挤到屏幕下面。', 2900)
  await page.locator('.replay-history summary').click()
  await page.getByRole('button', { name: '退出回放' }).click()
  await caption('演示结束', `订单 ${orderId} 的两条变向分支已演示完成。`, 4200)

  if (errors.length) throw new Error(errors.join('; '))
  await page.close()
  await video.saveAs(outputFile)
  const temporary = await video.path()
  if (resolve(temporary).startsWith(resolve(outputDir) + '\\') && resolve(temporary) !== resolve(outputFile)) await unlink(temporary)
  console.log(`SAVED=${outputFile}`)
  console.log(`ORDER=${orderId}`)
} finally {
  await context.close()
  await browser.close()
}
