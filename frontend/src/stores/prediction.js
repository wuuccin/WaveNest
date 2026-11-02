import { defineStore } from 'pinia'
import { fetchPredictionList, fetchPredictionDetail, SOURCE } from '../api/predictionSource.js'
import { isPremiseFlagged } from '../api/prediction.js'

/**
 * 预测看台状态（阶段二 1 · 前端）。
 *
 * <p>只存三样东西：<b>在航船摘要列表</b>、<b>当前选中船的 MMSI</b>、<b>选中船的点列</b>。
 * 不在这里做渲染决策——颜色、排序、徽标全在组件与 {@code api/prediction.js} 里，
 * 状态层只负责「拿到的是什么、谁被选中」。
 *
 * <h3>三条不变式（都靠测试守）</h3>
 * <ol>
 *   <li><b>选中船必须还在列表里</b>：船掉出列表（不再预测）时选中态必须一起清掉，
 *       否则地图上会留一条「幽灵预测」——面板没有对应行，用户点不到也关不掉。</li>
 *   <li><b>点列只属于选中船</b>：{@code detail.mmsi} 与 {@code selectedMmsi} 不一致时丢弃，
 *       防止快速点选 A→B 时 A 的慢响应把 B 的点列覆盖（真实并发会踩到）。</li>
 *   <li><b>刷新不清选中</b>：定时刷新只替换列表与点列，不重置选中，否则每 5 秒把用户的选择打回原点。</li>
 * </ol>
 */
export const usePredictionStore = defineStore('prediction', {
  state: () => ({
    /** 在航船摘要（已归一，见 api/prediction.js） */
    items: [],
    /** 数据源：live / mock / fallback，面板必须显示 */
    source: '',
    /** 本轮的提示语（接口未就绪、条数不一致等） */
    notice: '',
    /** 拉取失败的错误文案；空串表示正常 */
    error: '',
    loading: false,
    /** 选中船 MMSI，空串表示未选 */
    selectedMmsi: '',
    /** 选中船的完整点列（归一后） */
    detail: null,
    detailLoading: false,
    detailError: '',
    /** 上次成功刷新的时刻，面板显示「数据新鲜度」 */
    updatedAt: 0,
    /** 列表条数与后端声明的在航数是否一致（接口若给 total 就用它核对） */
    countMismatch: false
  }),

  getters: {
    /** 选中船在列表里的那一行（没有则 null） */
    selectedRow: state => state.items.find(item => item.mmsi === state.selectedMmsi) || null,
    /**
     * 需要醒目标记的船——判据「premise 非 VALID 的船有醒目标记」的数据来源。
     * 组件不自己写 `!== 'VALID'`，一律读这里，改口径只改一处。
     */
    flagged: state => state.items.filter(item => isPremiseFlagged(item.premise)),
    /** 地图要画的东西：只有选中船且点列属于它时才有 */
    overlay: state => {
      if (!state.detail || !state.selectedMmsi) return null
      if (state.detail.mmsi !== state.selectedMmsi) return null
      if (!state.detail.points.length) return null
      return state.detail
    },
    /** 条数口径自检：页面写「共 N 条」，N 必须等于列表长度，不能另算 */
    count: state => state.items.length
  },

  actions: {
    /**
     * 拉列表。{@code mode='mock'} 时不发请求（离线演示）。
     *
     * <p>失败时<b>保留上一轮的列表</b>并置 {@code error}：
     * 一次抖动就把面板清空，值班的人会以为「船全没了」，那比显示稍旧的数据危险得多。
     * 但错误必须显示出来，不能让旧数据冒充新数据——{@code updatedAt} 不前移就是证据。
     */
    async refresh(mode = 'auto') {
      this.loading = true
      this.error = ''
      try {
        const nowMs = Date.now()
        const result = await fetchPredictionList({ mode, nowMs })
        this.items = result.items
        this.source = result.source
        this.notice = result.error
        this.updatedAt = nowMs
        this.countMismatch = false
        // 不变式 1：选中的船掉出列表 ⇒ 清选中态与点列
        if (this.selectedMmsi && !this.items.some(item => item.mmsi === this.selectedMmsi)) {
          this.selectedMmsi = ''
          this.detail = null
          this.detailError = ''
        }
        return this.items
      } catch (error) {
        this.error = error?.message || String(error)
        return this.items
      } finally {
        this.loading = false
      }
    },

    /**
     * 点选一条船。重复点同一条不会重复请求（除非要求强制刷新）。
     *
     * <p>选中态先落，再请求详情——面板立刻高亮，不必等网络。
     * 详情到了以后还要再核对一次 MMSI（不变式 2）。
     */
    async select(mmsi, mode = 'auto', options = {}) {
      const next = String(mmsi || '')
      if (!next) { this.clearSelection(); return null }
      const same = next === this.selectedMmsi
      if (same && this.detail && !options.force) return this.detail
      this.selectedMmsi = next
      this.detailError = ''
      this.detailLoading = true
      // 换船时先清旧点列：宁可短暂地图上没有预测线，也不让上一艘船的线留在屏幕上骗人
      if (!same) this.detail = null
      try {
        const result = await fetchPredictionDetail(next, { mode, nowMs: Date.now() })
        // 不变式 2：慢响应覆盖问题
        if (this.selectedMmsi !== next) return this.detail
        if (!result.detail) {
          this.detailError = `接口没有返回 ${next} 的预测点列`
          this.detail = null
          return null
        }
        this.detail = result.detail
        if (result.source !== SOURCE.live && this.source === SOURCE.live) {
          this.notice = `单船点列来自离线样例（接口未就绪），列表来自实时接口——两者不要混读。`
        }
        return this.detail
      } catch (error) {
        if (this.selectedMmsi === next) {
          this.detail = null
          this.detailError = error?.message || String(error)
        }
        return null
      } finally {
        if (this.selectedMmsi === next) this.detailLoading = false
      }
    },

    clearSelection() {
      this.selectedMmsi = ''
      this.detail = null
      this.detailError = ''
      this.detailLoading = false
    },

    reset() {
      this.items = []
      this.source = ''
      this.notice = ''
      this.error = ''
      this.loading = false
      this.updatedAt = 0
      this.countMismatch = false
      this.clearSelection()
    }
  }
})
