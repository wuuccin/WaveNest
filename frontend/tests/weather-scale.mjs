/**
 * 风况标尺电路自测——不启动浏览器，逐条核 2-7 的判据。
 *
 * <p>运行：{@code node tests/weather-scale.mjs}
 *
 * <p>考的不是「函数能跑」，而是四条容易想当然的地方：
 * ①阈值写反／相等时是否当场报错，而不是在界面上画两条重叠的线；
 * ②阈值是否闭区间生效（8.0 与 14.0 本身就要判超限，与 M4 检测器「达到即报」一致）；
 * ③机型上限缺失时是「回落最保守」还是「不画线」，两种情形不能混；
 * ④注入参数归一是否挡住「阵风低于风速」——后端会 400，前端不该先放过去。
 */
import assert from 'node:assert/strict'
import {
  WEATHER_THRESHOLDS, SCALE_MAX_MS,
  resolveThresholds, windLevel, scalePercent, pickDroneWindLimit, buildScaleModel, normalizeInjectInput
} from '../src/api/weather.js'

const results = []
async function check(name, fn) {
  try { await fn(); results.push([name, '✓', '']) }
  catch (error) { results.push([name, '✗', error.message]) }
}

const t = resolveThresholds()

await check('默认阈值取 sys_param 两档：8.0 / 14.0', () => {
  assert.equal(WEATHER_THRESHOLDS.warningMs, 8.0)
  assert.equal(WEATHER_THRESHOLDS.criticalMs, 14.0)
  assert.equal(t.warningMs, 8.0)
  assert.equal(t.criticalMs, 14.0)
  assert.match(t.source, /002_sys_param/)
})

await check('阈值写反或相等：当场报错，不静默接受', () => {
  assert.throws(() => resolveThresholds({ warningMs: 14, criticalMs: 8 }), /气象阈值非法/)
  assert.throws(() => resolveThresholds({ warningMs: 8, criticalMs: 8 }), /气象阈值非法/)
  assert.throws(() => resolveThresholds({ warningMs: 0, criticalMs: 14 }), /气象阈值非法/)
})

await check('分级为闭区间：8.0 判预警、14.0 判熔断', () => {
  assert.equal(windLevel(7.9, t), 'normal')
  assert.equal(windLevel(8.0, t), 'warning')
  assert.equal(windLevel(13.9, t), 'warning')
  assert.equal(windLevel(14.0, t), 'critical')
  assert.equal(windLevel(20, t), 'critical')
})

await check('非法风速判 unknown，不落到 normal 蒙混', () => {
  assert.equal(windLevel(null, t), 'unknown')
  assert.equal(windLevel(Number.NaN, t), 'unknown')
  assert.equal(windLevel(-1, t), 'unknown')
})

await check('标尺百分比越界截断，非法值不画线', () => {
  assert.equal(scalePercent(0, SCALE_MAX_MS), 0)
  assert.equal(scalePercent(8, SCALE_MAX_MS), 50)
  assert.equal(scalePercent(32, SCALE_MAX_MS), 100)
  assert.equal(scalePercent(Number.NaN, SCALE_MAX_MS), null)
  assert.equal(scalePercent(-1, SCALE_MAX_MS), null)
})

const fleet = [
  { droneId: 'UAV-A', model: 'M30T', windLimitMs: 10.0 },
  { droneId: 'UAV-B', model: 'M400', windLimitMs: 12.0 },
  { droneId: 'UAV-C', model: '未登记', windLimitMs: null }
]

await check('指定机号：取该机上限', () => {
  const picked = pickDroneWindLimit(fleet, 'UAV-B')
  assert.equal(picked.valueMs, 12.0)
  assert.equal(picked.scope, '当前任务机型')
})

await check('未指定机号：回落全机队最保守（10.0），并标注来源', () => {
  const picked = pickDroneWindLimit(fleet, '')
  assert.equal(picked.valueMs, 10.0)
  assert.equal(picked.scope, '全机队最保守')
})

await check('未登记机型的 null 不造默认值：跳过该行', () => {
  const picked = pickDroneWindLimit([{ droneId: 'UAV-C', windLimitMs: null }], 'UAV-C')
  assert.equal(picked, null)
})

await check('空台账：机型上限线不画，而不是画在 0 处', () => {
  const model = buildScaleModel({ windMs: 9, gustMs: null, windLimitMs: null, thresholds: t })
  assert.equal(model.droneLimit.valueMs, null)
  assert.equal(model.droneLimit.percent, null)
})

await check('无气象帧：hasWind=false，两条阈值线仍在（阈值是契约量，不依赖数据）', () => {
  const model = buildScaleModel({ windMs: null, gustMs: null, windLimitMs: 12, thresholds: t })
  assert.equal(model.hasWind, false)
  assert.equal(model.wind.percent, null)
  assert.equal(model.warning.percent, 50)
  assert.equal(model.critical.percent, 87.5)
})

await check('超机型上限与超熔断阈值分开判：11 m/s 对 M30T 超限、对全域未熔断', () => {
  const model = buildScaleModel({ windMs: 11, gustMs: 13.2, windLimitMs: 10, thresholds: t })
  assert.equal(model.overDroneLimit, true)
  assert.equal(model.overWarning, true)
  assert.equal(model.overCritical, false)
  assert.equal(model.gust.valueMs, 13.2)
})

await check('14 m/s 判熔断；同值不超机型上限（12）时为 true 仅因 14>12', () => {
  const model = buildScaleModel({ windMs: 14, gustMs: null, windLimitMs: 12, thresholds: t })
  assert.equal(model.overCritical, true)
  assert.equal(model.overDroneLimit, true)
})

await check('注入参数：风向 405° 归一到 45°', () => {
  assert.equal(normalizeInjectInput({ windSpeedMs: 11, windDirDeg: 405 }).windDirDeg, 45)
  assert.equal(normalizeInjectInput({ windSpeedMs: 11, windDirDeg: -10 }).windDirDeg, 350)
})

await check('注入参数：阵风低于风速被挡（后端同样 400）', () => {
  assert.throws(() => normalizeInjectInput({ windSpeedMs: 11, gustMs: 9 }), /阵风不得低于风速/)
})

await check('注入参数：风速缺失／负数被挡', () => {
  assert.throws(() => normalizeInjectInput({}), /风速须为非负数/)
  assert.throws(() => normalizeInjectInput({ windSpeedMs: -3 }), /风速须为非负数/)
})

await check('注入参数：persist 缺省为 true，显式 false 保留', () => {
  assert.equal(normalizeInjectInput({ windSpeedMs: 11 }).persist, true)
  assert.equal(normalizeInjectInput({ windSpeedMs: 11, persist: false }).persist, false)
  assert.equal(normalizeInjectInput({ windSpeedMs: 11 }).gustMs, null)
})

const failed = results.filter(([, mark]) => mark === '✗')
for (const [name, mark, detail] of results) console.log(`${mark} ${name}${detail ? ` — ${detail}` : ''}`)
console.log(`\n${results.length - failed.length}/${results.length} 通过`)
if (failed.length) process.exitCode = 1
