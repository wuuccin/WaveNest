/**
 * 规划航迹帧 {@code plan_track} 的契约唯一来源（阶段二 2 · 杨添爻 11-04）。
 *
 * <p>与 {@code ship_traj} 的分工（见 {@code PlanTrackFrame} 类注释）：
 * 本帧是<b>计划</b>航迹（机巢→会面点→交接点→归巢），{@code ship_traj} 是<b>船舶预测</b>轨迹。
 * 两者在地图上必须可分辨——不仅颜色不同，线型也不同（计划=点划线，预测=虚线）。
 *
 * <p>字段名只在 {@link FIELDS} 一张表里出现；后端改契约只改这个文件。
 */

/** 后端 JSON 字段名。禁止在组件/图层里写这些字面量。 */
export const FIELDS = {
  orderId: 'orderId',
  routeId: 'routeId',
  comboId: 'comboId',
  opType: 'opType',
  segments: 'segments',
  segmentKind: 'kind',
  segmentPoints: 'points',
  pointLon: 'lon',
  pointLat: 'lat',
  pointAltM: 'altM',
  pointSpeedMs: 'speedMs',
  pointTMs: 'tMs'
}

/** 分段口径词——与 {@code PlanTrackFrame.SEGMENT_*} 逐字一致（后端按中文落库，不翻译）。 */
export const KIND = {
  outbound: '去程',
  terminal: '末段交接',
  return: '返程'
}

/** 摘要操作词，与 {@code PlanTrackFrame.OP_TYPE} 同源；前端只用它做帧归属校验。 */
export const OP_TYPE = '规划航迹'

/** 三段固定顺序——后端 {@code segments} 声明「顺序固定为去程、末段交接、返程」。 */
export const SEGMENT_ORDER = [KIND.outbound, KIND.terminal, KIND.return]

/**
 * 三段配色——<b>刻意与 {@code droneTrackLayer} 的实飞配色错开</b>。
 *
 * <p>实飞（{@code drone_state.segments}）用 #3579b5 / #d3962f / #319780 实线；
 * 计划航迹用同色系的<b>深一档</b>并一律点划线，保证「同一条航线、计划与实况」能一眼分开。
 */
export const SEGMENT_STYLE = {
  [KIND.outbound]: { color: '#1d4f85', label: '计划去程' },
  [KIND.terminal]: { color: '#9a6414', label: '计划末段交接' },
  [KIND.return]: { color: '#12695a', label: '计划返程' }
}

/**
 * 取数值，但<b>不让 null/空串变成 0</b>。
 *
 * <p>{@code Number(null)} 是 0——直接用会把「缺经度」的点当成 lon=0 留在点列里，
 * Leaflet 照画不误，于是航迹上多出一条从赣江横跨到本初子午线的鬼线。
 * 这类问题在几百个点里根本看不出来，所以必须在入口挡住。
 */
const num = value => {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 分段的排序权重：契约顺序优先，契约外的 kind 排最后（原样保留，不丢弃）。 */
export function kindRank(kind) {
  const index = SEGMENT_ORDER.indexOf(kind)
  return index === -1 ? SEGMENT_ORDER.length : index
}

/**
 * 点归一：只保留经纬度都有限且在合法范围内的点。
 *
 * <p>为什么要剔：Leaflet 拿到 NaN 经纬度不会报错，而是「什么都不画」——
 * 这类问题最难查。剔掉后点数如实反映，不让「航点数」变成假指标。
 */
function normalizePoint(raw) {
  const lon = num(raw?.[FIELDS.pointLon])
  const lat = num(raw?.[FIELDS.pointLat])
  if (lon === null || lat === null || lon < -180 || lon > 180 || lat < -90 || lat > 90) return null
  const altM = num(raw[FIELDS.pointAltM])
  const speedMs = num(raw[FIELDS.pointSpeedMs])
  const tMs = num(raw[FIELDS.pointTMs])
  return {
    lon,
    lat,
    altM: altM === null ? null : altM,
    speedMs: speedMs === null ? null : speedMs,
    tMs: tMs === null ? null : tMs
  }
}

/**
 * 帧归一：返回可直接画的结构，或 {@code null}（帧不可用）。
 *
 * <p>丢弃判据（任一成立即整帧丢弃，不做部分渲染）：
 * <ul>
 *   <li>缺 {@code orderId}——画出来无法归属到订单，点击也查不到是谁；</li>
 *   <li>{@code segments} 不是数组——契约要求「无航迹应不发帧」，发来了却不是数组就是契约漂移。</li>
 * </ul>
 *
 * <p>保留判据：某段点列为空 → <b>保留该段但标记 {@code empty: true}</b>，
 * 面板上显示「无航点」而不是静默消失——段缺失本身是信息（例如未组批时没有返程）。
 *
 * @param {object} raw  WS 帧 data（{@code plan_track}）
 * @returns {object|null} 归一后的航迹；不可用时返回 null
 */
export function normalizePlanTrack(raw) {
  if (!raw || typeof raw !== 'object') return null
  const orderId = raw[FIELDS.orderId]
  if (typeof orderId !== 'string' || !orderId.trim()) return null
  const rawSegments = raw[FIELDS.segments]
  if (!Array.isArray(rawSegments)) return null

  const segments = rawSegments.map(segment => {
    const kind = segment?.[FIELDS.segmentKind]
    const rawPoints = segment?.[FIELDS.segmentPoints]
    const points = (Array.isArray(rawPoints) ? rawPoints : []).map(normalizePoint).filter(Boolean)
    return {
      kind: typeof kind === 'string' && kind.trim() ? kind : '',
      // 契约外 kind 单列：不并进「未知」，避免把契约漂移洗掉
      known: SEGMENT_ORDER.includes(kind),
      points,
      empty: points.length === 0
    }
  })

  return {
    orderId,
    routeId: typeof raw[FIELDS.routeId] === 'string' ? raw[FIELDS.routeId] : '',
    comboId: typeof raw[FIELDS.comboId] === 'string' ? raw[FIELDS.comboId] : null,
    opType: typeof raw[FIELDS.opType] === 'string' ? raw[FIELDS.opType] : '',
    segments,
    /** 三段（或后端给出的全部段）的航点总数——面板计数用，不含被剔除的非法点 */
    pointCount: segments.reduce((sum, segment) => sum + segment.points.length, 0)
  }
}

/** 段的数量口径：契约三段是否齐、缺哪段、是否多出契约外段。 */
export function segmentSummary(track) {
  const segments = track?.segments || []
  const present = SEGMENT_ORDER.filter(kind => segments.some(segment => segment.kind === kind && !segment.empty))
  const missing = SEGMENT_ORDER.filter(kind => !present.includes(kind))
  const unexpected = segments.filter(segment => !segment.known).map(segment => segment.kind)
  return { present, missing, unexpected, complete: missing.length === 0 }
}

/** 段的展示名：契约内给中文全称，契约外原样回显并加前缀（不臆造翻译）。 */
export function segmentLabel(kind) {
  return SEGMENT_STYLE[kind]?.label || `未知分段（${kind || '空'}）`
}

/** 时刻 → 相对帧内首点的偏移文本（{@code +3 分 20 秒}）；无时刻返回 null。 */
export function offsetText(tMs, baseMs) {
  if (!Number.isFinite(tMs) || !Number.isFinite(baseMs)) return null
  const seconds = Math.round((tMs - baseMs) / 1000)
  if (seconds < 0) return null
  if (seconds < 60) return `+${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest ? `+${minutes} 分 ${rest} 秒` : `+${minutes} 分`
}
