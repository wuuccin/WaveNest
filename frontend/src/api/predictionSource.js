/**
 * 预测看台的数据源——<b>真实接口优先，同结构 mock 兜底</b>。
 *
 * <h3>为什么不用 try/catch 猜</h3>
 * <p>最省事的写法是「请求失败就退回 mock」。但这会把<b>鉴权失败、500、路由写错</b>
 * 全部伪装成「接口还没上线」，面板上永远看着正常——这是最难查的一类问题。
 *
 * <p>所以这里区分三种情况：
 * <ul>
 *   <li>{@code 404}／{@code 501}：路由确实还没有 ⇒ 用 mock，并如实标注「接口未就绪」；</li>
 *   <li>其它错误（5xx、超时、解析失败）：<b>不退回 mock</b>，把错误抛给面板显示。
 *       拿样例数据冒充实时数据，比空着更糟。</li>
 * </ul>
 *
 * <p>手动切到 mock（{@code mode='mock'}）时不发请求——离线演示/评审要能脱离后端跑。
 */

import { api } from './client.js'
import { FIELDS, PATHS, normalizeList, normalizeDetail } from './prediction.js'
import { mockPredictionList, mockPredictionDetail } from './mockPrediction.js'

/** 真实接口「还没上线」的判定：只有这两种状态码算未就绪。 */
const NOT_READY_STATUS = new Set([404, 501])

/** 数据源标注——面板要把「这是样例」写到脸上，不能只靠颜色。 */
export const SOURCE = {
  live: 'live',
  mock: 'mock',
  fallback: 'fallback'
}

export const SOURCE_TEXT = {
  live: '实时接口',
  mock: '离线样例（手动切换）',
  fallback: '离线样例（接口未就绪）'
}

/**
 * 在航船列表。
 *
 * @param {{mode?: 'auto'|'mock', nowMs?: number, signal?: AbortSignal}} options
 * @returns {Promise<{source: string, items: Array, error: string}>}
 */
export async function fetchPredictionList(options = {}) {
  const nowMs = options.nowMs ?? Date.now()
  if (options.mode === 'mock') {
    return { source: SOURCE.mock, items: normalizeList(mockPredictionList(nowMs), nowMs), error: '' }
  }
  try {
    const payload = await api.predictionList(options.signal)
    return { source: SOURCE.live, items: normalizeList(payload, nowMs), error: '' }
  } catch (error) {
    if (NOT_READY_STATUS.has(error?.status)) {
      return {
        source: SOURCE.fallback,
        items: normalizeList(mockPredictionList(nowMs), nowMs),
        error: `预测查询接口尚未就绪（HTTP ${error.status} → ${PATHS.list}），当前展示的是同字段结构的离线样例。`
      }
    }
    throw error
  }
}

/**
 * 单船完整点列。
 *
 * <p>点列没取到时<b>不拿列表里的点数糊弄</b>：列表摘要可能只带少量点或被裁剪，
 * 用摘要冒充详情，地图上会少画一截而没人发现。
 */
export async function fetchPredictionDetail(mmsi, options = {}) {
  const nowMs = options.nowMs ?? Date.now()
  if (options.mode === 'mock') {
    return { source: SOURCE.mock, detail: normalizeDetail(mockPredictionDetail(mmsi, nowMs), nowMs) }
  }
  try {
    const payload = await api.predictionDetail(mmsi, options.signal)
    return { source: SOURCE.live, detail: normalizeDetail(payload, nowMs) }
  } catch (error) {
    if (NOT_READY_STATUS.has(error?.status)) {
      return {
        source: SOURCE.fallback,
        detail: normalizeDetail(mockPredictionDetail(mmsi, nowMs), nowMs)
      }
    }
    throw error
  }
}

/** 仅供样例自检：确认 mock 的键名真的取自契约表，没有另写一套。 */
export function mockShapeOk() {
  const sample = mockPredictionList(Date.now())[FIELDS.list][0]
  return Boolean(sample) && Object.hasOwn(sample, FIELDS.mmsi) && Object.hasOwn(sample, FIELDS.points)
}
