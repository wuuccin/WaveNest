import { chromium } from 'playwright'
import { mkdir, unlink } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const frontendRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const projectRoot = dirname(frontendRoot)
const outputDir = join(projectRoot, 'demo')
const outputFile = join(outputDir, 'wavenest-live-service-demo.webm')
const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
await mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({
  headless: true,
  ...(process.env.BROWSER_PATH ? { executablePath: process.env.BROWSER_PATH } : {})
})
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  recordVideo: { dir: outputDir, size: { width: 1440, height: 900 } }
})
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))

async function caption(title, explanation, facts = [], ms = 3200) {
  await page.evaluate(({ title, explanation, facts }) => {
    let overlay = document.getElementById('live-video-caption')
    if (!overlay) {
      overlay = document.createElement('div')
      overlay.id = 'live-video-caption'
      overlay.style.cssText = 'position:fixed;z-index:10000;left:50%;bottom:14px;transform:translateX(-50%);width:min(1040px,calc(100vw - 44px));padding:12px 18px;border:1px solid rgba(82,209,192,.55);border-radius:12px;color:#fff;background:rgba(7,32,43,.95);box-shadow:0 10px 34px rgba(0,0,0,.3);font-family:Microsoft YaHei UI,Arial,sans-serif;pointer-events:none;'
      overlay.innerHTML = '<strong style="display:block;font-size:17px"></strong><span style="display:block;margin-top:4px;font-size:13px;color:#d9efec"></span><div style="display:flex;justify-content:center;gap:8px;flex-wrap:wrap;margin-top:7px"></div>'
      document.body.append(overlay)
    }
    overlay.querySelector('strong').textContent = title
    overlay.querySelector('span').textContent = explanation
    const list = overlay.querySelector('div')
    list.replaceChildren(...facts.map(fact => {
      const item = document.createElement('i')
      item.style.cssText = 'font-style:normal;font-size:12px;padding:3px 8px;border-radius:999px;background:rgba(55,178,159,.18);border:1px solid rgba(82,209,192,.28);color:#bfece5'
      item.textContent = fact
      return item
    }))
  }, { title, explanation, facts })
  await page.waitForTimeout(ms)
}

let video
let orderId = ''
try {
  await page.addInitScript(() => localStorage.setItem('wavenest-source', 'live'))
  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.header-status').getByText('WebSocket 已连接').waitFor({ timeout: 15000 })
  video = page.video()
  await caption(
    'WaveNest · 前后端实时联通演示',
    '当前画面连接本机正式 Spring Boot 服务，数据来源保持为“实时服务”。',
    ['前端 5173', '后端 8080', 'WebSocket 已连接', 'RabbitMQ / Redis 健康'],
    4300
  )

  await page.getByRole('link', { name: '船员下单' }).click()
  const form = page.locator('.form-panel .crew-form')
  await form.locator('input').nth(0).fill('123456789')
  await form.locator('input').nth(1).fill('实时联调急救药品')
  await form.locator('input').nth(2).fill('1.5')
  await form.locator('select').selectOption('紧急')
  await caption(
    '第 1 步 · 填写并提交真实订单',
    '仿真船 MMSI 必须是 123456789；提交按钮会调用 POST /api/orders。',
    ['MMSI 123456789', '重量 1.5 kg', '优先级 URGENT'],
    3600
  )
  await form.getByRole('button', { name: /提交配送订单/ }).click()
  const feedback = form.locator('.action-feedback')
  await feedback.getByText(/已由服务接收/).waitFor({ timeout: 20000 })
  const feedbackText = await feedback.textContent()
  orderId = feedbackText.match(/ORD-[\d-]+/)?.[0] || ''
  if (!orderId) throw new Error(`未从下单反馈取得订单号：${feedbackText}`)

  const orderPayload = await page.evaluate(async id => {
    const response = await fetch('/api/orders')
    if (!response.ok) throw new Error(`GET /api/orders ${response.status}`)
    const payload = await response.json()
    return payload.orders.find(order => order.orderId === id)
  }, orderId)
  if (!orderPayload) throw new Error(`GET /api/orders 中找不到 ${orderId}`)
  await caption(
    '后端已受理并自动派发',
    `订单 ${orderId} 已由后端完成接入、受理、选机、会面求解和派发。`,
    ['POST /api/orders 200', `订单状态 ${orderPayload.orderStatus}`, `状态版本 ${orderPayload.stateVersion}`],
    4500
  )

  await page.getByRole('link', { name: '调度台' }).click()
  const row = page.locator('.order-table tbody tr').filter({ hasText: orderId })
  await row.waitFor({ timeout: 12000 })
  await row.click()
  const taskPayload = await page.evaluate(async id => {
    const response = await fetch('/api/monitor/tasks')
    if (!response.ok) throw new Error(`GET /api/monitor/tasks ${response.status}`)
    const payload = await response.json()
    return payload.tasks.find(task => task.orderId === id)
  }, orderId)
  if (!taskPayload?.droneId) throw new Error(`监控任务未返回派发无人机：${JSON.stringify(taskPayload)}`)
  await caption(
    '第 2 步 · 调度台核对派发结果',
    '订单队列来自 GET /api/orders；任务详情来自数据库监控查询。',
    [`订单 ${orderId}`, `无人机 ${taskPayload.droneId}`, `状态 ${taskPayload.statusLabel}`, `会面点 ${taskPayload.meetPoint.lon.toFixed(4)}, ${taskPayload.meetPoint.lat.toFixed(4)}`],
    5000
  )

  await page.getByRole('link', { name: '运行总览', exact: true }).click()
  await page.locator('.map-panel').first().waitFor()
  await caption(
    '第 3 步 · 实时监控船舶和无人机',
    'M1 仿真器持续把 AIS、无人机遥测和气象消息送入 RabbitMQ，再由 WebSocket 推到地图。请观察图标位置和电量持续变化。',
    ['data.ais', 'data.uav.telemetry', 'data.weather', 'WebSocket /ws'],
    6500
  )

  const turn = await page.evaluate(async id => {
    const response = await fetch(`/sim/ship/turn?delta=45&orderId=${encodeURIComponent(id)}`, { method: 'POST' })
    if (!response.ok) throw new Error(`POST /sim/ship/turn ${response.status}`)
    return response.json()
  }, orderId)
  await caption(
    '第 4 步 · 后端注入船舶变向',
    '真实调用仿真控制接口，船舶右转 45°；随后产生的新 AIS 会实时更新轨迹。',
    [`turnCount ${turn.turnCount}`, `新航向 ${turn.newCog}°`, `triggerMode ${turn.triggerMode}`],
    6500
  )

  await page.getByRole('link', { name: '任务回放' }).click()
  const select = page.getByRole('combobox', { name: '回放任务' })
  await select.waitFor({ timeout: 12000 })
  await select.selectOption(orderId)
  const slider = page.getByRole('slider', { name: '回放进度' })
  await slider.waitFor({ state: 'visible', timeout: 12000 })
  await page.waitForFunction(() => Number(document.querySelector('[aria-label="回放进度"]')?.max) > 0, null, { timeout: 12000 })
  const max = Number(await slider.getAttribute('max'))
  await caption(
    '第 5 步 · 读取真实历史轨迹',
    `回放按订单 ${orderId} 找到 ${taskPayload.droneId}，GET /api/replay/${taskPayload.droneId} 返回历史帧。`,
    [`历史帧 ${max + 1} 条`, '来源 MySQL flight_point', '可逐帧拖动'],
    4800
  )
  await slider.fill(String(Math.floor(max / 2)))
  await caption('回放中段', '地图恢复到历史中间时刻，可以查看无人机位置、电量和事件。', [`进度 ${Math.floor(max / 2) + 1} / ${max + 1}`], 3900)
  await slider.fill(String(max))
  await caption(
    '实时联通演示结束',
    '本次已验证下单、自动派发、实时遥测、船舶变向和任务回放。当前正式后端状态自动推进到“已派发”，交接完成态仍需后续业务模块接通。',
    [`订单 ${orderId}`, `无人机 ${taskPayload.droneId}`, '全程实时服务数据'],
    6000
  )

  const materialErrors = errors.filter(message => !message.includes("_leaflet_pos"))
  if (materialErrors.length) throw new Error(materialErrors.join('; '))
  await page.close()
  await video.saveAs(outputFile)
  const temporary = await video.path()
  if (resolve(temporary).startsWith(`${resolve(outputDir)}\\`) && resolve(temporary) !== resolve(outputFile)) {
    await unlink(temporary)
  }
  console.log(`SAVED=${outputFile}`)
  console.log(`ORDER=${orderId}`)
} finally {
  await context.close()
  await browser.close()
}
