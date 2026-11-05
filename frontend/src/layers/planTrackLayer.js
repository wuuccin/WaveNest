import L from 'leaflet'
import { systemMapPoint } from '../map/coordinates'
import { KIND, SEGMENT_STYLE, segmentLabel, offsetText, kindRank } from '../api/planTrack'

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character])

/**
 * 契约外分段的样式——<b>最刺眼的暗红</b>：分段口径被改坏，比少画一段严重得多。
 *
 * <p>为什么不复用「未知分段」的灰：灰在这张图上到处都是（未选中、占位、参考线），
 * 一眼扫过去会被当成正常装饰；契约漂移必须自己跳出来。
 */
const UNKNOWN_STYLE = { color: '#7c2d3a', label: '契约外分段' }

/** 取一段的样式：契约三段查表，契约外一律用警示色。 */
const styleOf = segment => (segment?.known && SEGMENT_STYLE[segment.kind]) || UNKNOWN_STYLE

/**
 * 规划航迹叠加（阶段二 2 · 杨添爻 11-04）。
 *
 * <p><b>与既有三条线的区分</b>（这是本任务的判据原文「与预测轨迹不混」）：
 *
 * <table>
 *   <tr><th>线</th><th>来源</th><th>颜色</th><th>线型</th><th>图层</th></tr>
 *   <tr><td>船舶预测轨迹</td><td>{@code ship_traj}</td><td>#0f9f8c 青绿</td><td>虚线 11 8</td><td>shipGroup</td></tr>
 *   <tr><td>无人机实飞</td><td>{@code drone_state.segments}</td><td>三色实线</td><td>实线 5px</td><td>droneGroup</td></tr>
 *   <tr><td><b>规划航迹</b></td><td><b>{@code plan_track}</b></td><td><b>同色系深一档</b></td><td><b>点划线 9 6 2 6</b></td><td><b>planTrackGroup</b></td></tr>
 * </table>
 *
 * <p>三条独立 {@link L.layerGroup}：清一条不碰另一条。这点很关键——
 * 用同一个 group 时 {@code clearLayers()} 会把别人的线一起抹掉。
 *
 * <p>画法：每段「白色描边 + 彩色点划线 + 段中点标签」，段端点画小圆环标出衔接处。
 * 白色描边是因为底图是卫星影像时，深色点划线压在深色江面上会看不清。
 */

/** 点划线——Leaflet 的 dashArray 只支持「实/虚」交替，点划线用 9 6 2 6 三段近似。 */
const DASH = '9 6 2 6'

function toLatLngs(points) {
  return points.map(point => {
    const mapped = systemMapPoint(point)
    return mapped ? [mapped.lat, mapped.lon] : null
  }).filter(Boolean)
}

/**
 * 画一条规划航迹。调用前 group 会被清空（同一时刻只展示一条航迹）。
 *
 * @param {L.layerGroup} group  由 MapView 创建的图层组
 * @param {object|null} track   {@code normalizePlanTrack} 的结果
 * @param {{fit?: boolean, highlightKind?: string}} options
 *        {@code fit=true} 时视野移到整条航迹；{@code highlightKind} 指定的段加粗（面板悬停联动）
 * @returns {{drawn: number, skipped: string[]}} 画了几段、跳过了哪几段
 */
export function drawPlanTrack(group, track, options = {}) {
  if (!group) return { drawn: 0, skipped: [] }
  group.clearLayers()
  if (!track) return { drawn: 0, skipped: [] }

  // 按契约顺序画：去程→末段交接→返程，保证返程压在去程之上（返程通常更长、更靠外）
  const segments = [...track.segments].sort((a, b) => kindRank(a.kind) - kindRank(b.kind))
  const skipped = []
  let drawn = 0
  const allLatLngs = []

  for (const segment of segments) {
    const latLngs = toLatLngs(segment.points || [])
    if (latLngs.length < 2) { skipped.push(segment.kind || '（空分段）'); continue }
    allLatLngs.push(...latLngs)
    drawn++

    const style = styleOf(segment)
    const strong = options.highlightKind && options.highlightKind === segment.kind
    const weight = strong ? 8 : 5

    L.polyline(latLngs, { color: '#ffffff', weight: weight + 4, opacity: .85, interactive: false }).addTo(group)
    const line = L.polyline(latLngs, {
      className: `plan-track plan-track-${segment.known ? segment.kind : 'unknown'}`,
      color: style.color, weight, opacity: .98, dashArray: DASH
    }).addTo(group)

    // 段信息：点数、时长、首末高度——评审想知道的「这段到底多长、飞多久」一悬停就有
    const first = segment.points[0], last = segment.points.at(-1)
    const span = offsetText(last?.tMs, first?.tMs)
    const altitudes = segment.points.map(point => point.altM).filter(value => Number.isFinite(value))
    const altText = altitudes.length
      ? ` · 高度 ${Math.round(Math.min(...altitudes))}–${Math.round(Math.max(...altitudes))} m`
      : ''
    line.bindTooltip(
      `${escapeHtml(segmentLabel(segment.kind))} · ${segment.points.length} 个航点`
      + `${span ? ` · 历时 ${span.replace('+', '')}` : ''}${altText}`
    )

    // 段中点的文字标签：三段颜色接近时，只靠颜色分辨在色弱/小屏上不成立
    const middle = latLngs[Math.floor((latLngs.length - 1) / 2)]
    L.marker(middle, {
      interactive: false,
      icon: L.divIcon({
        className: `plan-track-tag plan-track-tag-${segment.known ? segment.kind : 'unknown'}`,
        html: escapeHtml(segment.known ? style.label : `${UNKNOWN_STYLE.label}（${segment.kind || '空'}）`),
        iconSize: [96, 22], iconAnchor: [48, 11]
      })
    }).addTo(group)

    // 段端点小圆环：标出「这里换段」，三段衔接处一眼可见
    for (const endpoint of [latLngs[0], latLngs.at(-1)]) {
      L.circleMarker(endpoint, {
        radius: 4, color: style.color, weight: 2, fillColor: '#fff', fillOpacity: 1, interactive: false
      }).addTo(group)
    }
  }

  if (options.fit && allLatLngs.length > 1) fitToPoints(group, allLatLngs)
  return { drawn, skipped }
}

/** 视野校正重试上限——与函数注释「最多三轮」一致，容器尺寸持续为 0 时最多重试 3 次。 */
const MAX_FIT_RETRIES = 3

/** 挂起的视野校正 timer（模块级：同一函数多次触发时只保留最后一次，组件卸载时由 cancelFit 清掉）。 */
let fitTimer = null

/** 视野移到整条航迹；退化（单点/无效包围盒）时不动视野，免得把整段航道缩没。 */
function fitToPoints(group, latLngs, retries = 0) {
  const map = group?._map
  if (!map) return
  // 首帧可能在地图完成布局之前到达（容器尺寸还是 0，fitBounds 算出的是垃圾缩放，
  // 表现就是「线画了但小得看不见」）。布局没好就等一拍再试，最多三轮。
  if (!map.getSize().x || !map.getSize().y) {
    if (retries >= MAX_FIT_RETRIES) return
    clearTimeout(fitTimer)
    fitTimer = setTimeout(() => fitToPoints(group, latLngs, retries + 1), 250)
    return
  }
  const bounds = L.latLngBounds(latLngs)
  if (!bounds.isValid()) return
  if (bounds.getNorthEast().equals(bounds.getSouthWest())) return
  map.fitBounds(bounds.pad(.25), { padding: [30, 30], maxZoom: 15, animate: true })
}

/** 取消任何挂起的视野校正——组件卸载时调用，避免卸载后残留回调继续触发。 */
export function cancelFit() {
  clearTimeout(fitTimer)
  fitTimer = null
}

/** 清空规划航迹（取消订单选中、切换数据源时调用）。 */
export function clearPlanTrack(group) {
  group?.clearLayers()
}

/**
 * 只在缺失的段位置上补一个虚位标记（可选）。
 *
 * <p>为什么需要：契约里「某段为空」是合法状态（如未组批时无返程），
 * 但地图上「什么都没有」和「画失败了」长得一样。给缺失段一个小提示，把这两种情况分开。
 */
export function markMissingSegments(group, track) {
  if (!group || !track) return
  const missing = [KIND.outbound, KIND.terminal, KIND.return]
    .filter(kind => !(track.segments || []).some(segment => segment.kind === kind && !segment.empty))
  if (!missing.length) return
  const anchor = (track.segments || []).flatMap(segment => segment.points || [])[0]
  const mapped = anchor ? systemMapPoint(anchor) : null
  if (!mapped) return
  L.marker([mapped.lat, mapped.lon], {
    interactive: false,
    icon: L.divIcon({
      className: 'plan-track-missing',
      html: `缺 ${missing.map(kind => escapeHtml(kind)).join(' / ')}`,
      iconSize: [150, 24], iconAnchor: [-10, 34]
    })
  }).addTo(group)
}
