import { defineStore } from 'pinia'
import { ORDER_STATES, normalizeOrder, displayEnum } from '../api/adapter.js'
import { normalizeCandidateHistory } from '../realtime/candidateAssessment.js'

export const useShipStore = defineStore('ship', {
  state: () => ({ selectedMmsi: '', byId: {}, tracks: {} }),
  getters: {
    current: state => state.byId[state.selectedMmsi] || null,
    actualTrack: state => state.tracks[state.selectedMmsi] || [],
    ships: state => Object.values(state.byId)
  },
  actions: {
    select(mmsi) { if (this.byId[mmsi]) this.selectedMmsi = mmsi },
    apply(data) {
      if (!data) { this.selectedMmsi = ''; this.byId = {}; this.tracks = {}; return }
      if (!Number.isFinite(data.lon) || !Number.isFinite(data.lat)) return
      const mmsi = String(data.mmsi || '')
      if (!mmsi) return
      const track = this.tracks[mmsi] ??= []
      const point = { lon: data.lon, lat: data.lat, ts: data.t0 ?? data.eventTs ?? Date.now() }
      const last = track.at(-1)
      if (!last || point.ts > last.ts || point.lon !== last.lon || point.lat !== last.lat) track.push(point)
      if (track.length > 900) track.shift()
      this.byId[mmsi] = data
      if (!this.selectedMmsi) this.selectedMmsi = mmsi
    }
  }
})
export const useNestStore = defineStore('nest', {
  state: () => ({ items: [], loaded: false, error: '' }),
  actions: {
    apply(items) {
      if (!Array.isArray(items)) throw new Error('GET /api/nests 未返回机巢数组')
      this.items = items.filter(item => item?.nestId && Number.isFinite(Number(item.lon)) && Number.isFinite(Number(item.lat)))
      this.loaded = true
      this.error = ''
    },
    fail(error) { this.items = []; this.loaded = true; this.error = error?.message || String(error) },
    reset() { this.items = []; this.loaded = false; this.error = '' }
  }
})
export const useFleetStore = defineStore('fleet', {
  state: () => ({ items: [], loaded: false, error: '' }),
  actions: {
    apply(items) {
      if (!Array.isArray(items)) throw new Error('GET /api/drones 未返回机队数组')
      this.items = items.filter(item => item?.droneId && (item.currentNestId || item.homeNestId))
      this.loaded = true
      this.error = ''
    },
    fail(error) { this.items = []; this.loaded = true; this.error = error?.message || String(error) },
    reset() { this.items = []; this.loaded = false; this.error = '' }
  }
})
export const useAirspaceStore = defineStore('airspace', {
  state: () => ({ items: [], loaded: false, error: '' }),
  actions: {
    apply(items) {
      if (!Array.isArray(items)) throw new Error('GET /api/airspace 未返回空域台账数组')
      this.items = items.filter(item => item?.restrictionId && item?.kind)
      this.loaded = true
      this.error = ''
    },
    fail(error) { this.items = []; this.loaded = true; this.error = error?.message || String(error) },
    reset() { this.items = []; this.loaded = false; this.error = '' }
  }
})
export const useDroneStore = defineStore('drone', {
  state: () => ({ selectedUavId: '', byId: {}, tracks: {} }),
  getters: {
    current: state => state.byId[state.selectedUavId] || null
  },
  actions: {
    apply(data) {
      if (!data) { this.selectedUavId = ''; this.byId = {}; this.tracks = {}; return }
      if (!Number.isFinite(data.lon) || !Number.isFinite(data.lat)) return
      const uavId = String(data.uavId || '')
      if (!uavId) return
      const track = this.tracks[uavId] ??= []
      if (!track.length || data.ts > track.at(-1).ts) track.push(data)
      if (track.length > 3000) track.shift()
      const currentFrame = { ...data, segments: data.segments || track.slice(1).map((point,i) => ({ phase:point.flightPhase, points:[track[i],point] })) }
      this.byId[uavId] = currentFrame
      if (!this.selectedUavId) this.selectedUavId = uavId
    }
  }
})
export const useAlertStore = defineStore('alert', { state: () => ({ items: [] }), actions: { apply(data) { this.items.unshift(data); this.items = this.items.slice(0, 80) }, clear() { this.items = [] } } })
export const useEventStore = defineStore('event', { state: () => ({ items: [] }), actions: { add(type, data) { this.items.unshift({ type, data, ts: data?.eventTs || Date.now() }); this.items = this.items.slice(0, 120) }, clear() { this.items = [] } } })
/**
 * 气象仓库（11-14 任务 2-7）。
 *
 * <p>原来只有一个 `current` 字段且无消费者，`data.weather` 帧进了实时层也没人接
 * （`realtime/index.js` 的 emit 对未知类型直接 return）。这里补齐三件事：
 * <ol>
 *   <li>按网格点留存最近一帧（{@code byPoint}），而不是只留最后一条——
 *       后端按位置与时刻发多条，只留一条等于丢掉「按位置取最不利」这件事；</li>
 *   <li>对外给「最不利风况」聚合值，与 M2 选机、M3 会面重算的取数口径一致；</li>
 *   <li>留存注入状态（偏置是否生效、注入计数、网格点数），供标尺显示「注入中」与复位按钮判态。</li>
 * </ol>
 *
 * <p>不做的事：不在这里造默认风速、不写 5 m/s 之类的兜底值——没有帧就是没有数据，
 * 标尺显示「等待气象帧」，由调用方自己决定怎么提示。
 */
export const useWeatherStore = defineStore('weather', {
  state: () => ({
    current: null,
    byPoint: {},
    frames: 0,
    updatedAt: 0,
    status: { biasActive: false, injectedCount: 0, gridPointCount: 0, loaded: false, error: '' }
  }),
  getters: {
    points: state => Object.values(state.byPoint),
    pointCount: state => Object.keys(state.byPoint).length,
    hasData: state => Boolean(state.current),
    /** 最不利风况：所有网格点里的最大风速；无有效帧为 null（不回落默认值）。 */
    windSpeedMs: state => {
      const values = Object.values(state.byPoint).map(point => point.windSpeedMs).filter(Number.isFinite)
      return values.length ? Math.max(...values) : null
    },
    gustMs: state => {
      const values = Object.values(state.byPoint).map(point => point.gustMs).filter(Number.isFinite)
      return values.length ? Math.max(...values) : null
    },
    windDirDeg: state => (Number.isFinite(state.current?.windDirDeg) ? state.current.windDirDeg : null),
    /** 最不利点在哪个网格点——标尺脚注用，避免「最大值」说不清来源。 */
    worstPoint: state => {
      const points = Object.values(state.byPoint).filter(point => Number.isFinite(point.windSpeedMs))
      if (!points.length) return null
      return points.reduce((left, right) => (left.windSpeedMs >= right.windSpeedMs ? left : right))
    }
  },
  actions: {
    apply(data) {
      if (!data || typeof data !== 'object') return false
      const lon = Number(data.lon)
      const lat = Number(data.lat)
      const windSpeedMs = Number(data.windSpeedMs)
      // 坐标与风速任一非法即丢弃：宁可少画一点，也不让 NaN 进标尺把指针顶到 0 或满格
      if (!Number.isFinite(lon) || !Number.isFinite(lat) || !Number.isFinite(windSpeedMs) || windSpeedMs < 0) return false
      const obsTs = Number(data.obsTs ?? data.ts)
      const point = {
        lon,
        lat,
        windSpeedMs,
        windDirDeg: Number(data.windDirDeg),
        gustMs: Number(data.gustMs),
        obsTs: Number.isFinite(obsTs) ? obsTs : null
      }
      const key = `${lon.toFixed(5)},${lat.toFixed(5)}`
      const previous = this.byPoint[key]
      // 同一点只认更新的观测：乱序到达的旧帧不覆盖新值
      if (previous && Number.isFinite(previous.obsTs) && Number.isFinite(point.obsTs) && point.obsTs < previous.obsTs) return false
      if (!previous && Object.keys(this.byPoint).length >= 500) this.byPoint = {}
      this.byPoint[key] = point
      this.current = point
      this.frames += 1
      this.updatedAt = Number.isFinite(point.obsTs) ? point.obsTs : Date.now()
      return true
    },
    /** 注入/复位后的状态回显（GET /sim/weather 的返回体），只存展示用字段。 */
    applyStatus(payload) {
      if (!payload || typeof payload !== 'object') return
      this.status = {
        biasActive: Boolean(payload.biasActive),
        injectedCount: Number(payload.injectedCount) || 0,
        gridPointCount: Number(payload.gridPointCount ?? payload.gridPoints?.length) || 0,
        loaded: true,
        error: ''
      }
    },
    failStatus(error) { this.status = { ...this.status, loaded: true, error: error?.message || String(error) } },
    reset() {
      this.current = null
      this.byPoint = {}
      this.frames = 0
      this.updatedAt = 0
      this.status = { biasActive: false, injectedCount: 0, gridPointCount: 0, loaded: false, error: '' }
    }
  }
})
export const useOrderStore = defineStore('order', {
  state: () => ({ byId: {}, timeline: {}, candidateHistory: {}, candidateLoading: {}, candidateError: {}, gate: { gateStatus: '正常', gateSources: [] }, rendezvous: null, previousRendezvous: null, replaying: false }),
  getters: { orders: state => Object.values(state.byId).sort((a,b) => b.eventTs - a.eventTs) },
  actions: {
    async loadCandidateHistory(orderId, api) {
      if (!orderId) return
      this.candidateLoading[orderId] = true
      this.candidateError[orderId] = ''
      try { this.candidateHistory[orderId] = normalizeCandidateHistory(await api.candidateHistory(orderId)) }
      catch (error) { this.candidateError[orderId] = error?.message || String(error) }
      finally { this.candidateLoading[orderId] = false }
    },
    apply(data) {
      if (!data?.orderId || !ORDER_STATES.includes(data.orderStatus) || !Number.isSafeInteger(data.stateVersion)) return false
      const old = this.byId[data.orderId]
      if (old && data.stateVersion <= old.stateVersion) return false
      // 配送品类自建单后不再变化：任何生产端漏带该字段时，都用上一帧的值兜住，避免界面把已显示的品类退回「未记录」。
      const next = { ...old, ...data,
        goodsType: data.goodsType ?? old?.goodsType ?? null,
        safetyReview: data.safetyReview == null ? old?.safetyReview ?? null : data.safetyReview }
      this.byId[data.orderId] = next
      if (!old || old.stateVersion !== next.stateVersion) {
        this.timeline[data.orderId] ??= []
        this.timeline[data.orderId].push({ status: next.orderStatus, ts: next.eventTs, version: next.stateVersion, handoverPhase: next.handoverPhase })
      }
      return true
    },
    snapshot(payload) {
      if (!Array.isArray(payload.orders)) return
      const ids = new Set(payload.orders.map(order => order.orderId))
      for (const id of Object.keys(this.byId)) {
        if (!ids.has(id)) { delete this.byId[id]; delete this.timeline[id] }
      }
      for (const order of payload.orders) this.apply(normalizeOrder(order))
      if (payload.dispatchGate) this.gate = { ...payload.dispatchGate, gateStatus: displayEnum(payload.dispatchGate.gateStatus), gateSources: payload.dispatchGate.gateSources?.map(displayEnum) || [] }
    },
    applyRendezvous(data) {
      if (!data) { this.rendezvous = null; this.previousRendezvous = null; return }
      const current = this.rendezvous
      const sameOrder = current?.orderId && current.orderId === data.orderId
      const moved = sameOrder && Number.isFinite(current.lon) && Number.isFinite(current.lat)
        && (Math.abs(current.lon - data.lon) > 0.00001 || Math.abs(current.lat - data.lat) > 0.00001)
      this.previousRendezvous = moved ? current : (sameOrder ? this.previousRendezvous : null)
      this.rendezvous = data
    },
    reset() { this.gate = { gateStatus: '未知', gateSources: [] }; this.byId = {}; this.timeline = {}; this.rendezvous = null; this.previousRendezvous = null; this.replaying = false }
  }
})

// 预测看台状态（阶段二 1 · 杨添爻）：实现与注释在 stores/prediction.js，
// 这里只做一次再导出，保持既有「全部 store 从 ../stores 取」的用法不变。
export { usePredictionStore } from './prediction.js'

// 规划航迹状态（阶段二 2 · 杨添爻）：实现与注释在 stores/planTrack.js，同样只做再导出。
export { usePlanTrackStore } from './planTrack.js'
