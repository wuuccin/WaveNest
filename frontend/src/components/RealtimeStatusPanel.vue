<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { channelStats } from '../realtime'

// 七类实时输入的中文名。面板答的是「这类帧还在不在来」，不重复业务摘要。
const CHANNELS = [
  ['order_state', '订单状态'],
  ['ship_traj', '船舶轨迹'],
  ['drone_state', '无人机状态'],
  ['rendezvous', '会面方案'],
  ['alarm', '告警'],
  ['plan_track', '规划航迹'],
  ['weather', '气象'],
]

const now = ref(Date.now())
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))

const ageText = age => {
  if (age === null) return '未收到'
  if (age < 1500) return '刚收到'
  if (age < 60000) return `${Math.round(age / 1000)} 秒前`
  return `${Math.round(age / 60000)} 分钟前`
}

const rows = computed(() => CHANNELS.map(([type, label]) => {
  const stat = channelStats[type]
  const at = stat?.at || 0
  const age = at ? now.value - at : null
  // 从未收到只是「这一类现在没有业务」，不算故障；收到过又断掉才算。
  const level = age === null ? 'never' : (age < 5000 ? 'ok' : (age < 30000 ? 'stale' : 'down'))
  const rate = stat?.times?.length ? (stat.times.length / 5).toFixed(1) : '0.0'
  const meta = stat ? `每秒 ${rate} 条 · 累计 ${stat.total} 条` : '—'
  return { type, label, level, ageText: ageText(age), meta }
}))

const summary = computed(() => {
  const ok = rows.value.filter(row => row.level === 'ok').length
  const quiet = rows.value.filter(row => row.level === 'stale' || row.level === 'down').length
  const never = rows.value.filter(row => row.level === 'never').length
  if (!ok && !quiet) return '七类实时帧都没有到达，请检查后端与实时连接'
  const parts = [`${rows.value.length} 类实时帧中 ${ok} 类正在到达`]
  if (quiet) parts.push(`${quiet} 类超过 5 秒没有新帧`)
  if (never) parts.push(`${never} 类本轮没有业务`)
  return parts.join('，')
})
</script>

<template>
  <section class="realtime-panel" aria-label="实时通道健康度">
    <div class="section-heading">
      <span>实时通道</span><span class="eyebrow">CHANNEL HEALTH</span>
    </div>
    <p class="channel-summary">{{ summary }}</p>
    <p class="channel-note">船舶轨迹、无人机状态与气象每拍都有；订单状态、会面方案、告警、规划航迹只在事件发生的那一下来一条。</p>
    <div class="channel-grid">
      <div v-for="row in rows" :key="row.type" class="channel-row" :class="row.level">
        <i class="channel-dot" :class="row.level" aria-hidden="true"></i>
        <strong>{{ row.label }}</strong>
        <span>{{ row.ageText }}</span>
        <small>{{ row.meta }}</small>
      </div>
    </div>
  </section>
</template>
