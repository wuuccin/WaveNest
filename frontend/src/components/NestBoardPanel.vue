<script setup>
import { computed } from 'vue'
import { useNestStore, useFleetStore, useDroneStore } from '../stores'

const nests = useNestStore()
const fleet = useFleetStore()
const telemetry = useDroneStore()

const nestNames = computed(() => {
  const map = {}
  for (const nest of nests.items) map[nest.nestId] = nest
  return map
})

// 机巢看板默认读机队台账（GET /api/drones），但台账里的电量不会随飞行消耗，
// 状态也不在飞行中实时更新，于是与当前任务卡、地图的实时遥测对不上。
// 这里把实时 drone_state 帧叠在台账之上：有遥测就用遥测的电量与飞行阶段，
// 没有遥测（例如离线演示、或该机尚未上报）再退回台账值。
const IDLE_PHASES = new Set(['空闲', '待命', '原巢'])

function effective(drone) {
  const live = telemetry.byId[drone.droneId]
  const batteryValue = Number(live?.battery ?? drone.battery)
  const atNest = live ? !live.activeOrderId : Boolean(drone.currentNestId)
  const idle = live ? (IDLE_PHASES.has(live.flightPhase) && !live.activeOrderId) : drone.status === '空闲'
  return {
    battery: Number.isFinite(batteryValue) ? `${batteryValue.toFixed(0)}%` : '--',
    status: live?.flightPhase || drone.status || '--',
    positionType: atNest ? '原巢' : '空中',
    atNest,
    idle
  }
}

const rows = computed(() => fleet.items.map(drone => ({
  droneId: drone.droneId,
  nestName: nestNames.value[drone.homeNestId]?.nestId || drone.homeNestId || '未登记',
  model: drone.model || '--',
  ...effective(drone)
})))

const parkedCount = computed(() => rows.value.filter(row => row.atNest).length)
const idleCount = computed(() => rows.value.filter(row => row.idle).length)

const nestLines = computed(() => (
  nests.items.map(nest => `${nest.nestId} 容量 ${nest.capacity ?? '未登记'} · 支持 ${nest.supportedModels?.join('、') || '未登记'}`)
))
</script>

<template>
  <section class="nest-board">
    <div class="section-heading"><span>机巢看板</span><span class="eyebrow">NEST BOARD</span></div>
    <p v-if="nests.error" class="action-feedback" role="status">机巢台账读取失败：{{ nests.error }}</p>
    <p v-else-if="!nests.loaded" class="empty-state">等待后端机巢数据</p>
    <template v-else>
      <p class="nest-summary">机巢 {{ nests.items.length }} 座 · 无人机 {{ rows.length }} 架 · 在巢 {{ parkedCount }} 架 · 空闲 {{ idleCount }} 架</p>
      <p class="nest-lines">{{ nestLines.join(' ｜ ') }}</p>
      <div class="nest-table-wrap">
        <table class="nest-table">
          <colgroup>
            <col style="width: 20%"><col style="width: 19%"><col style="width: 15%">
            <col style="width: 15%"><col style="width: 17%"><col style="width: 14%">
          </colgroup>
          <thead>
            <tr><th>机巢</th><th>机号</th><th>机型</th><th>位置</th><th>状态</th><th>电量</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.droneId" :class="{ away: !row.atNest }">
              <td>{{ row.nestName }}</td>
              <td class="drone-id">{{ row.droneId }}</td>
              <td>{{ row.model }}</td>
              <td>{{ row.positionType }}</td>
              <td class="drone-status" :class="{ success: row.idle }">{{ row.status }}</td>
              <td class="drone-battery">{{ row.battery }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="!fleet.items.length" class="nest-empty">暂没有登记无人机</p>
    </template>
  </section>
</template>
