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
  console.log(`STAGE=${title}`)
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

async function waitForOrderState(label, title, explanation, facts = []) {
  await page.waitForFunction(expected => {
    return document.querySelector('.mission-panel .state-chip')?.textContent.trim() === expected
  }, label, { timeout: 8 * 60 * 1000 })
  await caption(title, explanation, [`实时状态 ${label}`, ...facts], 4600)
}

async function orderSnapshot(id) {
  return page.evaluate(async orderId => {
    const response = await fetch('/api/orders')
    if (!response.ok) return null
    return (await response.json()).orders.find(item => item.orderId === orderId) || null
  }, id)
}

let video
let orderId = ''
try {
  await page.addInitScript(() => localStorage.setItem('wavenest-source', 'live'))
  await page.goto(`${baseUrl}/#dashboard`, { waitUntil: 'domcontentloaded' })
  await page.locator('.header-status').getByText('WebSocket 已连接').waitFor({ timeout: 15000 })
  video = page.video()
  const reset = await page.evaluate(async () => {
    const response = await fetch('/sim/ship/reset', { method: 'POST' })
    if (!response.ok) throw new Error(`POST /sim/ship/reset ${response.status}`)
    return response.json()
  })
  await caption(
    'WaveNest · 前后端实时联通演示',
    '当前画面连接本地正式 Spring Boot 服务和独立演示库，数据来源保持为“实时服务”。',
    ['前端 5174', '后端 8081', 'WebSocket 已连接', 'RabbitMQ / Redis 健康', `仿真航向 ${reset.cog}°`],
    4300
  )

  await page.getByRole('link', { name: '船员下单' }).click()
  const form = page.locator('.form-panel .crew-form')
  await form.getByLabel('目标船舶 MMSI（9 位数字）').fill('123456789')
  await form.getByLabel('配送品类').locator('option', { hasText: '应急药品' }).waitFor({ state: 'attached', timeout: 15000 })
  await form.getByLabel('配送品类').selectOption('应急药品')
  await form.getByLabel('物资重量（kg）').fill('1.5')
  await form.getByLabel('紧急程度').selectOption('紧急')
  await caption(
    '填写并提交真实订单',
    '仿真船 MMSI 必须是 123456789；配送品类来自后端可承运字典；提交按钮会调用 POST /api/orders。',
    ['MMSI 123456789', '配送品类 应急药品', '重量 1.5 kg', '优先级 URGENT'],
    3600
  )
  await form.getByRole('button', { name: /提交配送订单/ }).click()
  const feedback = form.locator('.action-feedback')
  await feedback.getByText(/已由服务接收/).waitFor({ timeout: 20000 })
  const feedbackText = await feedback.textContent()
  orderId = feedbackText.match(/ORD-[A-Za-z0-9-]+/)?.[0] || ''
  if (!orderId) throw new Error(`未从下单反馈取得订单号：${feedbackText}`)

  const orderBeforeCargo = await page.evaluate(async id => {
    const listResponse = await fetch('/api/orders')
    if (!listResponse.ok) throw new Error(`GET /api/orders ${listResponse.status}`)
    const order = (await listResponse.json()).orders.find(item => item.orderId === id)
    if (!order?.cargoNestId) throw new Error(`订单未返回货巢：${id}`)
    const response = await fetch(`/api/orders/${encodeURIComponent(id)}/cargo-ready`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cargoNestId: order.cargoNestId })
    })
    if (!response.ok) throw new Error(`POST /api/orders/${id}/cargo-ready ${response.status}`)
    const receipt = await response.json()
    if (!receipt.accepted) throw new Error(`货到巢登记失败：${receipt.reasonCode || receipt.message}`)
    return { cargoNestId: order.cargoNestId, receipt }
  }, orderId)
  await caption(
    '确认货物已到巢',
    '正式服务按货到巢关等待货物确认；确认后，后端自动继续组批、选机、求解会面点和派发。',
    [`订单 ${orderId}`, `货巢 ${orderBeforeCargo.cargoNestId || '后端已记录'}`, 'POST cargo-ready 200'],
    3600
  )

  await page.waitForFunction(async id => {
    const response = await fetch('/api/orders')
    if (!response.ok) return false
    const order = (await response.json()).orders.find(item => item.orderId === id)
    return Boolean(order?.droneId && order?.meetTime && order?.meetPoint)
  }, orderId, { timeout: 60000, polling: 1000 })
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
  await page.locator('.mission-facts').waitFor({ timeout: 30000 })
  await caption(
    '第 3 步 · 实时监控船舶和无人机',
    `M1 仿真器持续把 AIS、无人机遥测和气象消息送入 RabbitMQ，再由 WebSocket 推到地图。任务卡和地图已绑定执行机 ${taskPayload.droneId}。`,
    ['data.ais', 'data.uav.telemetry', 'data.weather', 'WebSocket /ws', `执行机 ${taskPayload.droneId}`],
    6500
  )

  await caption(
    '等待飞行阶段自动推进',
    '后端根据真实会面时刻、机巢距离、巡航速度和交接时长推进状态；等待期间地图仍持续接收实时遥测。',
    [`预计会面 ${taskPayload.meetTime.replace('T', ' ')}`, `执行机 ${taskPayload.droneId}`, '轮询间隔 1 秒'],
    3500
  )
  await waitForOrderState(
    '去程中',
    '第 4 步 · 起飞确认，进入去程',
    '后端 FlightPhaseAdvancer 写入 task_log，并通过 WebSocket 广播“去程中”。',
    ['已派发 → 去程中']
  )
  const reviewBefore = (await orderSnapshot(orderId))?.safetyReview?.eventTs ?? 0
  const weather = await page.evaluate(async () => {
    const response = await fetch('/sim/weather/inject?wind=18&dir=135&persist=true', { method: 'POST' })
    if (!response.ok) throw new Error(`POST /sim/weather/inject ${response.status}`)
    return response.json()
  })
  await caption(
    '第 5 步 · 飞行中注入风速突变',
    '原机处于去程中时，气象仿真接口注入超限风速；等待后端告警和安全处置。',
    [`风速 ${weather.windMs} m/s`, `风向 ${weather.dirDeg}°`, `发布 ${weather.publishedCount} 个网格点`],
    6500
  )
  await page.waitForFunction(async () => {
    const response = await fetch('/api/orders')
    if (!response.ok) return false
    const data = await response.json()
    return ['熔断中', 'BROKEN'].includes(data.dispatchGate?.gateStatus)
  }, null, { timeout: 25000, polling: 1000 })
  await page.waitForFunction(async ({ id, reviewBefore }) => {
    const response = await fetch('/api/orders')
    if (!response.ok) return false
    const order = (await response.json()).orders.find(item => item.orderId === id)
    return order?.safetyReview?.verdict === '终止'
      && Number(order.safetyReview.eventTs) > Number(reviewBefore)
  }, { id: orderId, reviewBefore }, { timeout: 60000, polling: 1000 })
  await caption('第 6 步 · 查看在飞安全复核', '超限后收到新的“终止”复核结论，原机中止本次会面并执行返航。', [`订单 ${orderId}`, '复核结论 终止'], 5000)
  const cleared = await page.evaluate(async () => {
    const response = await fetch('/sim/weather/bias', { method: 'DELETE' })
    if (!response.ok) throw new Error(`DELETE /sim/weather/bias ${response.status}`)
    return response.json()
  })
  await page.waitForFunction(async () => {
    const response = await fetch('/api/orders')
    if (!response.ok) return false
    const data = await response.json()
    return ['正常', 'NORMAL'].includes(data.dispatchGate?.gateStatus)
  }, null, { timeout: 25000, polling: 1000 })
  await caption('第 7 步 · 风况回落与熔断解除', '已清除注入偏置，后端订单快照确认派单闸门恢复正常。',
    [`偏置清除 ${Boolean(cleared)}`, 'GET /api/orders 确认闸门正常'], 5000)

  await page.waitForFunction(async id => {
    const order = await fetch('/api/orders').then(response => response.json()).then(data => data.orders.find(item => item.orderId === id))
    return ['HANDOVER', 'RETURNING', 'FINISHED'].includes(order?.orderStatus)
  }, orderId, { timeout: 180000, polling: 1000 })
  let current = await orderSnapshot(orderId)
  if (current?.orderStatus === 'RETURNING' && current?.safetyReview?.verdict === '终止') {
    const goodsId = `G-${orderId}-1`
    await page.evaluate(async ({ orderId, goodsId, uavId, nestId }) => {
      const response = await fetch(`/sim/recycle?orderId=${encodeURIComponent(orderId)}&goodsId=${encodeURIComponent(goodsId)}&uavId=${encodeURIComponent(uavId)}&nestId=${encodeURIComponent(nestId)}`, { method: 'POST' })
      if (!response.ok) throw new Error(`POST /sim/recycle ${response.status}`)
    }, { orderId, goodsId, uavId: taskPayload.droneId, nestId: current.cargoNestId })
    await caption('原机返航后确认落巢回收', '当前安全处置已经选择原机返航。真实回收确认通过 data.uav.recycle 消费链写回货物状态，订单随后回到调度队列。', [`原机 ${taskPayload.droneId}`, `落巢 ${current.cargoNestId}`, 'data.uav.recycle'], 5000)
    await page.waitForFunction(async id => {
      const order = await fetch('/api/orders').then(response => response.json()).then(data => data.orders.find(item => item.orderId === id))
      return Boolean(order?.droneId && ['DISPATCHED', 'OUTBOUND', 'HANDOVER'].includes(order.orderStatus))
    }, orderId, { timeout: 120000, polling: 1000 })
  }
  await waitForOrderState('末段交接中', '重新派单后等待交接',
    '风况恢复后，后端已重新派发可用无人机并推进到末段交接。', ['重派交接'])
  current = await orderSnapshot(orderId)
  await page.evaluate(async ({ orderId, uavId }) => {
    const response = await fetch(`/sim/delivery?taskId=${encodeURIComponent(orderId)}&orderId=${encodeURIComponent(orderId)}&uavId=${encodeURIComponent(uavId)}`, { method: 'POST' })
    if (!response.ok) throw new Error(`POST /sim/delivery ${response.status}`)
  }, { orderId, uavId: taskPayload.droneId })
  await waitForOrderState('返航中', '交接完成后无人机返航',
    '真实投放回执已由后端消费，订单进入返航状态。', ['data.uav.delivery'])
  await waitForOrderState('已完成', '订单完成',
    '后端已广播订单完成状态。', ['返航中 → 已完成', `订单 ${orderId}`])

  await page.getByRole('link', { name: '任务回放' }).click()
  const select = page.getByRole('combobox', { name: '回放任务' })
  await select.waitFor({ timeout: 12000 })
  await select.selectOption(orderId)
  const slider = page.getByRole('slider', { name: '回放进度' })
  await slider.waitFor({ state: 'visible', timeout: 12000 })
  await page.waitForFunction(() => Number(document.querySelector('[aria-label="回放进度"]')?.max) > 0, null, { timeout: 12000 })
  const max = Number(await slider.getAttribute('max'))
  await caption(
    '第 8 步 · 读取真实历史轨迹',
    `回放按订单 ${orderId} 找到 ${taskPayload.droneId}，GET /api/replay/${taskPayload.droneId} 返回历史帧。`,
    [`历史帧 ${max + 1} 条`, '来源 MySQL flight_point', '可逐帧拖动'],
    4800
  )
  await slider.fill(String(Math.floor(max / 2)))
  await caption('回放中段', '地图恢复到历史中间时刻，可以查看无人机位置、电量和事件。', [`进度 ${Math.floor(max / 2) + 1} / ${max + 1}`], 3900)
  await slider.fill(String(max))
  await caption(
    '实时联通演示结束',
    '本次已验证下单、自动派发、去程、气象超限、返航、完成、实时遥测和任务回放。',
    [`订单 ${orderId}`, `无人机 ${taskPayload.droneId}`, '全程实时服务数据', '状态由后端广播'],
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
