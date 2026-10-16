import { defineStore } from 'pinia'
import { ORDER_STATES } from '../api/adapter'

export const useShipStore = defineStore('ship', { state: () => ({ current: null }), actions: { apply(data) { this.current = data } } })
export const useDroneStore = defineStore('drone', { state: () => ({ current: null }), actions: { apply(data) { this.current = data } } })
export const useAlertStore = defineStore('alert', { state: () => ({ items: [] }), actions: { apply(data) { this.items.unshift(data); this.items = this.items.slice(0, 80) }, clear() { this.items = [] } } })
export const useEventStore = defineStore('event', { state: () => ({ items: [] }), actions: { add(type, data) { this.items.unshift({ type, data, ts: data?.eventTs || Date.now() }); this.items = this.items.slice(0, 120) }, clear() { this.items = [] } } })
export const useWeatherStore = defineStore('weather', { state: () => ({ current: { windSpeedMs: 4.2, windDirDeg: 65 } }), actions: { apply(data) { this.current = data } } })
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
      if (!old || old.orderStatus !== next.orderStatus) {
        this.timeline[data.orderId] ??= []
        this.timeline[data.orderId].push({ status: next.orderStatus, ts: next.eventTs, version: next.stateVersion })
      }
      return true
    },
    snapshot(payload) {
      if (!Array.isArray(payload.orders)) return
      const ids = new Set(payload.orders.map(order => order.orderId))
      for (const id of Object.keys(this.byId)) {
        if (!ids.has(id)) { delete this.byId[id]; delete this.timeline[id] }
      }
      for (const order of payload.orders) this.apply(order)
      if (payload.dispatchGate) this.gate = payload.dispatchGate
    },
    reset() { this.byId = {}; this.timeline = {}; this.rendezvous = null; this.replaying = false }
  }
})
