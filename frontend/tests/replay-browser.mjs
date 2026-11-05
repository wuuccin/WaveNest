import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const browser = await chromium.launch({
  headless: true,
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const requests = []
page.on('request', request => requests.push(new URL(request.url()).pathname))
await page.route('**/api/orders', route => route.fulfill({ json: {
  orders: [{ orderId: 'ORD-02', orderStatus: '已派发', eventTs: 2, stateVersion: 2 }],
  dispatchGate: { gateStatus: 'NORMAL', gateSources: [] }
} }))
await page.route('**/api/monitor/tasks', route => route.fulfill({ json: { tasks: [
  { orderId: 'ORD-02', droneId: 'UAV-02' },
  { orderId: 'ORD-EMPTY', droneId: 'UAV-EMPTY' }
], total: 2 } }))
await page.route('**/api/replay/UAV-02', route => route.fulfill({ json: { frames: [
  { type: 'drone_state', data: { uavId: 'UAV-02', lon: 114.30, lat: 30.60, battery: 80, ts: 1000 } },
  { type: 'drone_state', data: { uavId: 'UAV-02', lon: null, lat: 30.61, battery: 79, ts: 2000 } }
] } }))
await page.route('**/api/replay/UAV-EMPTY', route => route.fulfill({
  status: 404,
  contentType: 'application/json',
  body: JSON.stringify({ detail: '未找到飞行任务：UAV-EMPTY' })
}))

try {
  await page.goto(process.env.E7B_BASE_URL || 'http://127.0.0.1:5173', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '实时服务' }).click()
  await page.getByRole('link', { name: '任务回放' }).click()
  const select = page.getByLabel('回放任务')
  await select.waitFor()
  assert.equal(await select.inputValue(), 'ORD-02', '下拉选中值必须是 orderId')
  assert.equal(await select.locator('option:checked').textContent(), 'ORD-02 · UAV-02')
  await page.getByText('2 条记录').waitFor()
  assert(requests.includes('/api/replay/UAV-02'), '回放请求必须把 orderId 映射为 droneId')
  assert(!requests.some(path => path.endsWith('/records')), '不得调用未实现的记录明细端点')

  await select.selectOption('ORD-EMPTY')
  await page.getByText('该任务暂无飞行记录', { exact: true }).waitFor()
  assert.equal(await page.getByText(/回放读取失败/).count(), 0, '404 应呈现空态而非失败')
  assert(requests.includes('/api/replay/UAV-EMPTY'))
  console.log('replay browser checks passed')
} finally {
  await page.close()
  await browser.close()
}
