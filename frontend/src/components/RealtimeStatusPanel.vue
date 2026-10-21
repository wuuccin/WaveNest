<script setup>
import { computed } from 'vue'
import { useAlertStore, useDroneStore, useOrderStore, useShipStore } from '../stores'

const ship = useShipStore()
const drone = useDroneStore()
const orders = useOrderStore()
const alerts = useAlertStore()

const shipPosition = computed(() => ship.current?.points?.[0] || ship.current)
const latestAlert = computed(() => alerts.items[0])
const coordinate = point => Number.isFinite(point?.lon) && Number.isFinite(point?.lat)
  ? `${point.lon.toFixed(4)}, ${point.lat.toFixed(4)}` : '等待位置帧'
</script>

<template>
  <section class="realtime-panel">
    <div class="section-heading">
      <span>实时运行快照</span><span class="eyebrow">BUSINESS FRAMES</span>
    </div>
    <div class="realtime-cards">
      <article>
        <span class="frame-tag">order_state</span>
        <strong>{{ orders.orders[0]?.orderId || '等待订单状态' }}</strong>
        <p>{{ orders.orders[0]?.orderStatus || '暂无订单' }}</p>
        <small>状态版本 {{ orders.orders[0]?.stateVersion ?? '--' }}</small>
      </article>
      <article>
        <span class="frame-tag ship">ship_traj</span>
        <strong>船舶预测</strong>
        <p>{{ coordinate(shipPosition) }}</p>
        <small>版本 {{ ship.current?.version ?? '--' }} · {{ ship.current?.points?.length ?? 0 }} 个预测点</small>
      </article>
      <article>
        <span class="frame-tag drone">drone_state</span>
        <strong>{{ drone.current?.uavId || '无人机机位' }}</strong>
        <p>{{ coordinate(drone.current) }}</p>
        <small>电量 {{ Number.isFinite(drone.current?.battery) ? `${drone.current.battery.toFixed(0)}%` : '--' }} · {{ drone.current?.flightPhase || '阶段待更新' }}</small>
      </article>
      <article>
        <span class="frame-tag meeting">rendezvous</span>
        <strong>会面点</strong>
        <p>{{ coordinate(orders.rendezvous) }}</p>
        <small>{{ orders.rendezvous?.locked ? '方案已锁定' : '等待派发定案' }}</small>
      </article>
      <article>
        <span class="frame-tag alarm">alarm</span>
        <strong>{{ latestAlert?.alarmType || '运行告警' }}</strong>
        <p>{{ latestAlert?.message || '当前无告警' }}</p>
        <small>{{ latestAlert?.level || '正常' }}</small>
      </article>
    </div>
  </section>
</template>
