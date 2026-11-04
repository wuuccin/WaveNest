/**
 * 规划航迹（plan_track）电路自测——不启动浏览器，逐条核任务判据。
 *
 * <p>运行：{@code node tests/plan-track-circuit.mjs}
 *
 * <p>考的不是「函数能跑」，而是三条容易想当然的地方：
 * ①三段口径是否按契约（去程／末段交接／返程）而非无人机实飞的 phase 词；
 * ②契约外分段是否被原样保留而不是被洗成「未知」；
 * ③非法点是否被剔除而不是交给 Leaflet 画出「看不见的线」。
 */
import assert from 'node:assert/strict'
import {
  FIELDS, KIND, OP_TYPE, SEGMENT_ORDER, SEGMENT_STYLE,
  normalizePlanTrack, segmentSummary, segmentLabel, offsetText, kindRank
} from '../src/api/planTrack.js'

const results = []
async function check(name, fn) {
  try { await fn(); results.push([name, '✓', '']) }
  catch (error) { results.push([name, '✗', error.message]) }
}

const t0 = 1793800000000
const point = (lon, lat, i = 0) => ({ lon, lat, altM: 60 + i, speedMs: 12, tMs: t0 + i * 60000 })
const segment = (kind, points) => ({ kind, points })
const frame = (overrides = {}) => ({
  orderId: 'ORD-1793800000000-1',
  routeId: 'ROUTE-01',
  comboId: null,
  opType: '规划航迹',
  segments: [
    segment('去程', [point(115.854, 28.647, 0), point(115.862, 28.666, 1)]),
    segment('末段交接', [point(115.862, 28.666, 1), point(115.875, 28.681, 2)]),
    segment('返程', [point(115.875, 28.681, 2), point(115.854, 28.647, 3)])
  ],
  ...overrides
})

// ── 判据：三段口径 ──
await check('帧归一：三段齐全，点数如实统计', () => {
  const track = normalizePlanTrack(frame())
  assert.equal(track.orderId, 'ORD-1793800000000-1')
  assert.equal(track.routeId, 'ROUTE-01')
  assert.equal(track.segments.length, 3)
  assert.equal(track.pointCount, 6)
  assert.deepEqual(track.segments.map(s => s.kind), ['去程', '末段交接', '返程'])
})

await check('三段词与后端 PlanTrackFrame.SEGMENT_* 逐字一致', () => {
  assert.equal(KIND.outbound, '去程')
  assert.equal(KIND.terminal, '末段交接')
  assert.equal(KIND.return, '返程')
  assert.equal(OP_TYPE, '规划航迹')
  assert.deepEqual(SEGMENT_ORDER, ['去程', '末段交接', '返程'])
})

await check('三段配色互不相同，且与船舶预测的青绿 #0f9f8c 不同', () => {
  const colors = SEGMENT_ORDER.map(kind => SEGMENT_STYLE[kind].color)
  assert.equal(new Set(colors).size, 3, `三段颜色有重复：${colors}`)
  assert.ok(!colors.includes('#0f9f8c'), '计划航迹不能复用船舶预测轨迹色')
})

await check('点归一：lon/lat 字符串也接受（JSON 数字可能被序列化成字符串）', () => {
  const track = normalizePlanTrack(frame({
    segments: [segment('去程', [{ lon: '115.854', lat: '28.647', altM: 50, speedMs: 10, tMs: t0 }])]
  }))
  assert.equal(track.segments[0].points[0].lon, 115.854)
  assert.equal(track.pointCount, 1)
})

await check('点归一：NaN / 越界 / 缺失经纬度的点被剔除，不交给 Leaflet', () => {
  const track = normalizePlanTrack(frame({
    segments: [segment('去程', [
      point(115.854, 28.647, 0),
      { lon: null, lat: 28.66 },          // 缺经度
      { lon: NaN, lat: 28.66, altM: 60 }, // NaN
      { lon: 200, lat: 28.66, altM: 60 }, // 经度越界
      { lon: 115.86, lat: 95, altM: 60 }, // 纬度越界
      point(115.862, 28.666, 1)
    ])]
  }))
  assert.equal(track.segments[0].points.length, 2, '合法点应只剩首末两点')
  assert.equal(track.pointCount, 2)
})

// ── 判据：与预测轨迹区分（数据结构层面）──
await check('规划点只带 lon/lat/altM/speedMs/tMs，不带 sigma——与预测点的 sigma 语义分开', () => {
  const track = normalizePlanTrack(frame())
  const sample = track.segments[0].points[0]
  assert.deepEqual(Object.keys(sample).sort(), ['altM', 'lat', 'lon', 'speedMs', 'tMs'])
  assert.equal(sample.sigmaAlong, undefined)
})

// ── 契约漂移 ──
await check('缺 orderId 整帧丢弃（画出来无法归属到订单）', () => {
  assert.equal(normalizePlanTrack(frame({ orderId: '' })), null)
  assert.equal(normalizePlanTrack(frame({ orderId: undefined })), null)
})

await check('segments 非数组整帧丢弃（契约要求无航迹不发帧）', () => {
  assert.equal(normalizePlanTrack(frame({ segments: null })), null)
  assert.equal(normalizePlanTrack(frame({ segments: {} })), null)
})

await check('契约外分段原样保留，kind 原值回显，known=false', () => {
  const track = normalizePlanTrack(frame({
    segments: [
      segment('去程', [point(115.854, 28.647, 0), point(115.862, 28.666, 1)]),
      segment('待机盘旋', [point(115.862, 28.666, 1), point(115.87, 28.67, 2)])
    ]
  }))
  assert.equal(track.segments.length, 2, '不能把契约外分段丢掉')
  const extra = track.segments.find(s => s.kind === '待机盘旋')
  assert.ok(extra, '契约外分段应保留原值')
  assert.equal(extra.known, false)
  assert.match(segmentLabel('待机盘旋'), /待机盘旋/, '契约外分段标签应原样回显')
})

await check('段排序：契约三段按固定顺序，契约外段排最后', () => {
  const sorted = ['返程', '待机盘旋', '去程', '末段交接'].sort((a, b) => kindRank(a) - kindRank(b))
  assert.deepEqual(sorted, ['去程', '末段交接', '返程', '待机盘旋'])
})

// ── 缺段是信息，不是错误 ──
await check('缺返程段：保留段位但标 empty，summary 报 missing', () => {
  const track = normalizePlanTrack(frame({
    segments: [
      segment('去程', [point(115.854, 28.647, 0), point(115.862, 28.666, 1)]),
      segment('末段交接', [point(115.862, 28.666, 1), point(115.875, 28.681, 2)])
    ]
  }))
  assert.equal(track.segments.length, 2)
  const result = segmentSummary(track)
  assert.deepEqual(result.missing, ['返程'])
  assert.equal(result.complete, false)
})

await check('空点列的段：保留段位并标 empty（不是静默消失）', () => {
  const track = normalizePlanTrack(frame({ segments: [segment('去程', []), segment('返程', [point(115.854, 28.647, 0)])] }))
  const outbound = track.segments.find(s => s.kind === '去程')
  assert.ok(outbound, '空段也应保留')
  assert.equal(outbound.empty, true)
  assert.equal(outbound.points.length, 0)
})

await check('三段齐全时 summary.complete 为 true', () => {
  assert.equal(segmentSummary(normalizePlanTrack(frame())).complete, true)
})

// ── 时刻偏移 ──
await check('offsetText：秒/分钟两种量级都对，负值与缺失返回 null', () => {
  assert.equal(offsetText(t0 + 45000, t0), '+45 秒')
  assert.equal(offsetText(t0 + 200000, t0), '+3 分 20 秒')
  assert.equal(offsetText(t0 + 180000, t0), '+3 分')
  assert.equal(offsetText(t0 - 1000, t0), null)
  assert.equal(offsetText(null, t0), null)
  assert.equal(offsetText(t0 + 1000, null), null)
})

// ── 字段表 ──
await check('字段名只在 FIELDS 里出现，图层/组件不写裸字面量', () => {
  assert.equal(FIELDS.orderId, 'orderId')
  assert.equal(FIELDS.segmentKind, 'kind')
  assert.equal(FIELDS.pointTMs, 'tMs')
})

let failed = 0
for (const [name, mark, detail] of results) {
  if (mark === '✗') failed++
  console.log(`  ${mark} ${name}${detail ? `\n      ${detail}` : ''}`)
}
console.log(`\n${results.length - failed}/${results.length} 通过`)
if (failed) process.exit(1)
