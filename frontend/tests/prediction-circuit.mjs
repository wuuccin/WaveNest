/**
 * 预测看台前端逻辑自测（阶段二 1 · 杨添爻）。纯 Node，无浏览器、无后端。
 *
 * <p>三条判据在界面上靠肉眼也能看，但"看着对"挡不住下次改坏。这里把
 * <b>判据背后的不变量</b>落成断言，不用等浏览器：
 *
 * <ol>
 *   <li>列出全部在航船 → 归一不得丢条目（只丢无 mmsi 的"无主船"）；</li>
 *   <li>点选后叠加 → 点列只认同选中船（并发迟到的响应不得覆盖）；</li>
 *   <li>premise 非 VALID 有醒目标记 → 判定函数一处口径，未知取值不得被当成 UNKNOWN。</li>
 * </ol>
 *
 * 运行：{@code node tests/prediction-circuit.mjs}（已挂到 package.json 的 test:prediction）
 */

import assert from 'node:assert/strict'
import {
  FIELDS, PREMISE_VALUES, premiseLevel, isPremiseFlagged,
  normalizeList, normalizeSummary, normalizeDetail, normalizePoints, remainingText, PATHS
} from '../src/api/prediction.js'
import { mockPredictionList } from '../src/api/mockPrediction.js'

let passed = 0
const cases = []
function test(name, fn) {
  cases.push([name, fn])
}

/* ─────────────────────────  契约与样例  ───────────────────────── */

test('mock 的键名取自 FIELDS，没有第二套字面量', () => {
  const row = mockPredictionList(Date.now())[FIELDS.list][0]
  assert.ok(Object.hasOwn(row, FIELDS.mmsi), '缺 FIELDS.mmsi 键')
  assert.ok(Object.hasOwn(row, FIELDS.points), '缺 FIELDS.points 键')
  const point = row[FIELDS.points][0]
  for (const key of ['pointT', 'pointLon', 'pointLat', 'pointSigmaAlong', 'pointSigmaCross']) {
    assert.ok(Object.hasOwn(point, FIELDS[key]), `点缺 FIELDS.${key}`)
  }
})

test('路径只有一份来源，且带 mmsi 时做了转义（张睿哲 11-02 契约）', () => {
  assert.equal(PATHS.list, '/api/prediction/ships')
  assert.equal(PATHS.detail('413700001'), '/api/prediction/ships/413700001')
  assert.equal(PATHS.detail('a/b'), '/api/prediction/ships/a%2Fb')
})

test('张睿哲契约的真实摘要形状：不带点列，pointCount/validityRemainingSec 直取后端', () => {
  const now = Date.now()
  const rows = normalizeList({ ships: [
    { mmsi: '413700001', version: 7, premise: 'VALID', courseDeg: 28, pointCount: 12, validityRemainingSec: 480 }
  ] }, now)
  assert.equal(rows.length, 1)
  const row = rows[0]
  assert.equal(row.pointCount, 12, '摘要点数应取后端 pointCount，不是 0')
  assert.equal(row.remainingSec, 480, '剩余应取后端 validityRemainingSec')
  assert.equal(row.flagged, false)
  assert.deepEqual(row.points, [], '摘要没有点列，前端不得臆造')
})

/* ─────────────────────  判据 1：列出全部在航船  ───────────────────── */

test('判据1 列表不丢条目：五条样例全部解析出来', () => {
  const raw = mockPredictionList(Date.now())
  const rows = normalizeList(raw, Date.now())
  assert.equal(rows.length, raw[FIELDS.list].length, '有船被静默丢弃')
})

test('判据1 只有缺 mmsi 的条目被丢——它本来也无法点选', () => {
  const now = Date.now()
  const rows = normalizeList({ [FIELDS.list]: [
    { [FIELDS.mmsi]: '413700001', [FIELDS.version]: 1, [FIELDS.premise]: 'VALID', [FIELDS.points]: [] },
    { [FIELDS.version]: 2, [FIELDS.premise]: 'VALID', [FIELDS.points]: [] }
  ] }, now)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].mmsi, '413700001')
})

test('判据1 列表兼容裸数组 / ships / items 三种包装', () => {
  const one = [{ [FIELDS.mmsi]: '413700009', [FIELDS.premise]: 'VALID', [FIELDS.points]: [] }]
  for (const payload of [one, { [FIELDS.list]: one }, { items: one }]) {
    assert.equal(normalizeList(payload, Date.now()).length, 1, '包装形状未被识别')
  }
})

test('判据1 点数为 0 的船照常列出，不因"没点"就被隐藏', () => {
  const rows = normalizeList({ [FIELDS.list]: [{ [FIELDS.mmsi]: '413700010', [FIELDS.premise]: 'VALID', [FIELDS.points]: [] }] })
  assert.equal(rows.length, 1)
  assert.equal(rows[0].pointCount, 0)
})

/* ─────────────────  判据 3：premise 非 VALID 要标  ───────────────── */

test('判据3 只有 VALID 不标，其余（含未知取值）一律要标', () => {
  assert.equal(isPremiseFlagged('VALID'), false)
  for (const value of ['INVALID', 'UNKNOWN', 'PENDING', '', null, undefined]) {
    assert.equal(isPremiseFlagged(value), true, `${String(value)} 应当被标记`)
  }
})

test('判据3 未知取值单列 unexpected，不被并入 unknown 洗掉', () => {
  assert.equal(premiseLevel('VALID'), 'valid')
  assert.equal(premiseLevel('INVALID'), 'invalid')
  assert.equal(premiseLevel('UNKNOWN'), 'unknown')
  assert.equal(premiseLevel('PENDING'), 'unexpected')
  assert.equal(premiseLevel(null), 'unexpected')
})

test('判据3 契约外的取值不会被规范化成 UNKNOWN', () => {
  const row = normalizeSummary({ [FIELDS.mmsi]: '413700003', [FIELDS.premise]: 'PENDING', [FIELDS.points]: [] })
  assert.equal(row.premise, 'PENDING', '原值被改写，契约问题被吞')
  assert.equal(row.flagged, true)
})

test('判据3 premise 缺失时按 UNKNOWN 处理并标记（不是当成 VALID）', () => {
  const row = normalizeSummary({ [FIELDS.mmsi]: '413700011', [FIELDS.points]: [] })
  assert.equal(row.premise, 'UNKNOWN')
  assert.equal(row.flagged, true)
})

test('判据3 样例里同时存在 VALID 与非 VALID，标记能区分开', () => {
  const rows = normalizeList(mockPredictionList(Date.now()))
  const flagged = rows.filter(row => row.flagged).map(row => row.mmsi).sort()
  const clean = rows.filter(row => !row.flagged).map(row => row.mmsi).sort()
  assert.deepEqual(clean, ['413700001', '413700005'])
  assert.deepEqual(flagged, ['413700002', '413700003', '413700004'])
})

test('契约取值表与判定函数一致：PREMISE_VALUES 内每条都能落到非 unexpected', () => {
  for (const value of PREMISE_VALUES) {
    assert.notEqual(premiseLevel(value), 'unexpected', `${value} 在契约内却被判为异常取值`)
  }
})

/* ──────────────  点列质量：坏点要剔，但不许整套丢  ────────────── */

test('NaN/越界的点被剔除，正常点保留（点数指标必须诚实）', () => {
  const points = normalizePoints([
    { [FIELDS.pointT]: 1, [FIELDS.pointLon]: 115.86, [FIELDS.pointLat]: 28.66, [FIELDS.pointSigmaAlong]: 10, [FIELDS.pointSigmaCross]: 5 },
    { [FIELDS.pointT]: 2, [FIELDS.pointLon]: NaN, [FIELDS.pointLat]: 28.66 },
    { [FIELDS.pointT]: 3, [FIELDS.pointLon]: 115.87, [FIELDS.pointLat]: 128.66 },
    { [FIELDS.pointT]: 4, [FIELDS.pointLon]: 115.88, [FIELDS.pointLat]: 28.67, [FIELDS.pointSigmaAlong]: 'x' }
  ])
  assert.equal(points.length, 2)
  assert.equal(points[1].sigmaAlong, null, '非数值 σ 应归 null 而不是 NaN')
})

test('σ 为 NaN 的点不会把椭圆半径传成 NaN', () => {
  const [point] = normalizePoints([{ [FIELDS.pointLon]: 115.86, [FIELDS.pointLat]: 28.66, [FIELDS.pointSigmaAlong]: NaN, [FIELDS.pointSigmaCross]: NaN }])
  assert.equal(point.sigmaAlong, null)
  assert.ok(!Number.isNaN(point.sigmaAlong))
})

/* ────────────────────  有效期剩余的口径  ──────────────────── */

test('剩余有效期：后端直给时优先用后端的', () => {
  const row = normalizeSummary({ [FIELDS.mmsi]: '1', [FIELDS.t0Ms]: 0, [FIELDS.validitySec]: 999, [FIELDS.remainingSec]: 42, [FIELDS.points]: [] })
  assert.equal(row.remainingSec, 42)
})

test('剩余有效期：后端没给时前端自算，且过期钳到 0 不为负', () => {
  const now = 1_000_000
  assert.equal(normalizeSummary({ [FIELDS.mmsi]: '1', [FIELDS.t0Ms]: now, [FIELDS.validitySec]: 60, [FIELDS.points]: [] }, now).remainingSec, 60)
  assert.equal(normalizeSummary({ [FIELDS.mmsi]: '2', [FIELDS.t0Ms]: now - 90_000, [FIELDS.validitySec]: 60, [FIELDS.points]: [] }, now).remainingSec, 0)
})

test('剩余有效期：既无 t0 也无后端值时给 null，不谎报「0 秒」', () => {
  const row = normalizeSummary({ [FIELDS.mmsi]: '1', [FIELDS.points]: [] })
  assert.equal(row.remainingSec, null)
  assert.equal(remainingText(null), '—')
  assert.equal(remainingText(0), '已过期')
})

/* ─────────  判据 2：点选后叠加，且只叠加选中船的  ───────── */

test('判据2 详情返回的是被请求那条船的点列', () => {
  const detail = normalizeDetail(mockPredictionList(Date.now())[FIELDS.list][0])
  assert.equal(detail.mmsi, '413700001')
  assert.ok(detail.points.length > 0)
})

test('判据2 详情点列是独立数组，改一处不影响原对象', () => {
  const raw = mockPredictionList(Date.now())[FIELDS.list][0]
  const detail = normalizeDetail(raw)
  detail.points.pop()
  assert.notEqual(detail.points.length, raw[FIELDS.points].length)
})

test('判据2 裸点列也能当详情用，但主键需由调用方校对', () => {
  const detail = normalizeDetail([{ [FIELDS.pointLon]: 115.86, [FIELDS.pointLat]: 28.66 }])
  assert.equal(detail.points.length, 1)
  assert.equal(detail.mmsi, null, '裸点列没有 mmsi，不能凭空造一个')
})

/* ────────────────────────  执行  ──────────────────────── */

for (const [name, fn] of cases) {
  try {
    fn()
    console.log(`  ✓ ${name}`)
    passed += 1
  } catch (error) {
    console.error(`  ✗ ${name}\n    ${error.message}`)
    process.exitCode = 1
  }
}
console.log(`\n${passed}/${cases.length} 通过`)
