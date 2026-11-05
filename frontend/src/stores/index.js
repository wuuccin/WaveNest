import { defineStore } from 'pinia'
import { ORDER_STATES, normalizeOrder, displayEnum } from '../api/adapter'

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
export const useDroneStore = defineStore('drone', { state: () => ({ current: null, byId: {}, tracks: {} }), actions: { apply(data) {
  if (!data) { this.current = null; this.byId = {}; this.tracks = {}; return }
  if (!Number.isFinite(data.lon) || !Number.isFinite(data.lat)) return
  const track = this.tracks[data.uavId] ??= []
  if (!track.length || data.ts > track.at(-1).ts) track.push(data)
  if (track.length > 3000) track.shift()
  const currentFrame = { ...data, segments: data.segments || track.slice(1).map((point,i) => ({ phase:point.flightPhase, points:[track[i],point] })) }
  this.byId[data.uavId] = currentFrame
  this.current = currentFrame
} } })
export const useAlertStore = defineStore('alert', { state: () => ({ items: [] }), actions: { apply(data) { this.items.unshift(data); this.items = this.items.slice(0, 80) }, clear() { this.items = [] } } })
export const useEventStore = defineStore('event', { state: () => ({ items: [] }), actions: { add(type, data) { this.items.unshift({ type, data, ts: data?.eventTs || Date.now() }); this.items = this.items.slice(0, 120) }, clear() { this.items = [] } } })
export const useWeatherStore = defineStore('weather', { state: () => ({ current: null }), actions: { apply(data) { this.current = data } } })
export const useOrderStore = defineStore('order', {
  state: () => ({ byId: {}, timeline: {}, gate: { gateStatus: '正常', gateSources: [] }, rendezvous: null, previousRendezvous: null, replaying: false }),
  getters: { orders: state => Object.values(state.byId).sort((a,b) => b.eventTs - a.eventTs) },
  actions: {
    apply(data) {
      if (!data?.orderId || !ORDER_STATES.includes(data.orderStatus) || !Number.isSafeInteger(data.stateVersion)) return false
      const old = this.byId[data.orderId]
      if (old && data.stateVersion <= old.stateVersion) return false
      const next = { ...data }
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
