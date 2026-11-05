import { defineStore } from 'pinia'
import { normalizePlanTrack, segmentSummary } from '../api/planTrack'

/**
 * 规划航迹状态（阶段二 2 · 杨添爻 11-04）。
 *
 * <p><b>为什么按订单号分槽而不是只留一条</b>：{@code plan_track} 的归属是订单，
 * 而大屏可能同时显示多笔在途订单。只留「最后一条」会让上一笔订单的航迹凭空消失，
 * 用户以为「航迹没了」——其实是自己被覆盖了。
 *
 * <p><b>三条不变式</b>（与 {@code stores/prediction.js} 同思路，踩过的坑不再踩）：
 * <ol>
 *   <li>同一订单重复收帧 → 后到覆盖先到（重派会产新 {@code routeId}，旧航线必须消失）；</li>
 *   <li>换数据源/回放重置 → 全清，不留上一份数据源的计划线；</li>
 *   <li>选中订单掉出列表 → 自动回落到「无选中」，但航迹本身仍在（不因选中态丢失数据）。</li>
 * </ol>
 */
export const usePlanTrackStore = defineStore('planTrack', {
  state: () => ({
    /** orderId → 归一后的航迹；Map 换成普通对象便于 devtools 观察 */
    byOrder: {},
    /** 当前高亮的段（面板悬停联动；空字符串=不高亮） */
    highlightKind: '',
    /** 最近一次收到帧的时刻，用于「数据是否新鲜」提示 */
    updatedAt: 0,
    /** 契约漂移计数：收到但被丢弃的帧数（不可用时静默丢弃会让问题隐形） */
    rejected: 0
  }),

  getters: {
    /** 全部航迹，按订单号稳定排序（避免对象键顺序在不同引擎下不一致） */
    tracks: state => Object.values(state.byOrder).sort((a, b) => a.orderId.localeCompare(b.orderId)),

    count: state => Object.keys(state.byOrder).length,

    /** 指定订单的航迹 */
    ofOrder: state => orderId => state.byOrder[orderId] || null,

    /** 总航点数，面板计数用 */
    totalPoints: state => Object.values(state.byOrder).reduce((sum, track) => sum + track.pointCount, 0),

    /** 分段完整性汇总（缺段/契约外段）——面板据此给出提示 */
    summary: state => {
      const tracks = Object.values(state.byOrder)
      const missing = new Set()
      const unexpected = new Set()
      for (const track of tracks) {
        const result = segmentSummary(track)
        result.missing.forEach(kind => missing.add(kind))
        result.unexpected.forEach(kind => unexpected.add(kind))
      }
      return { missing: [...missing], unexpected: [...unexpected], complete: missing.size === 0 && unexpected.size === 0 }
    }
  },

  actions: {
    /**
     * 收一帧 {@code plan_track}。
     * @returns {boolean} 是否被接受（false = 契约不满足，已计入 rejected）
     */
    accept(raw) {
      const track = normalizePlanTrack(raw)
      if (!track) { this.rejected += 1; return false }
      this.byOrder[track.orderId] = track
      this.updatedAt = Date.now()
      return true
    },

    /** 清掉某一笔订单的航迹（订单到终态、被取消时）。 */
    drop(orderId) {
      if (orderId && this.byOrder[orderId]) {
        const next = { ...this.byOrder }
        delete next[orderId]
        this.byOrder = next
      }
    },

    setHighlight(kind) { this.highlightKind = kind || '' },

    reset() { this.byOrder = {}; this.highlightKind = ''; this.updatedAt = 0; this.rejected = 0 }
  }
})
