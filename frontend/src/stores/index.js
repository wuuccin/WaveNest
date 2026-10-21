import { defineStore } from 'pinia'
import { ORDER_STATES, normalizeOrder, displayEnum } from '../api/adapter'

export const useShipStore = defineStore('ship', { state: () => ({ current: null }), actions: { apply(data) { this.current = data } } })
export const useDroneStore = defineStore('drone', { state: () => ({ current: null, tracks: {} }), actions: { apply(data) {
  if (!data) { this.current = null; this.tracks = {}; return }
  if (!Number.isFinite(data.lon) || !Number.isFinite(data.lat)) return
  const track = this.tracks[data.uavId] ??= []
  if (!track.length || data.ts > track.at(-1).ts) track.push(data)
  if (track.length > 3000) track.shift()
  this.current = { ...data, segments: data.segments || track.slice(1).map((point,i) => ({ phase:point.flightPhase, points:[track[i],point] })) }
} } })
export const useAlertStore = defineStore('alert', { state: () => ({ items: [] }), actions: { apply(data) { this.items.unshift(data); this.items = this.items.slice(0, 80) }, clear() { this.items = [] } } })
export const useEventStore = defineStore('event', { state: () => ({ items: [] }), actions: { add(type, data) { this.items.unshift({ type, data, ts: data?.eventTs || Date.now() }); this.items = this.items.slice(0, 120) }, clear() { this.items = [] } } })
export const useWeatherStore = defineStore('weather', { state: () => ({ current: null }), actions: { apply(data) { this.current = data } } })
export const useOrderStore = defineStore('order', {
  state: () => ({ byId: {}, timeline: {}, gate: { gateStatus: '正常', gateSources: [] }, rendezvous: null, replaying: false }),
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
    reset() { this.gate = { gateStatus: '未知', gateSources: [] }; this.byId = {}; this.timeline = {}; this.rendezvous = null; this.replaying = false }
  }
})
