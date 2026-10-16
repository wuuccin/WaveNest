<script setup>
import { computed } from 'vue'
import { useOrderStore } from '../stores'
import { waitText,wakeText } from '../api/adapter'
const store=useOrderStore()
const current=computed(()=>store.orders[0])
const entries=computed(()=>current.value ? store.timeline[current.value.orderId]||[] : [])
const time=ts=>new Date(ts).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})
</script>
<template><section class="timeline-panel"><div class="section-heading"><span>订单进展</span><span class="eyebrow">ORDER STATUS</span></div><template v-if="current"><div class="order-summary"><div><small>{{ current.orderId }}</small><strong>{{ current.orderStatus }}</strong></div><span class="status-indicator" :class="{'done':current.orderStatus==='已完成','warn':['失败','异常终止'].includes(current.orderStatus)}"></span></div><div v-if="current.orderStatus==='已受理待唤醒'" class="detail-text">{{ waitText(current) }} · {{ wakeText(current) }}</div><div v-if="current.orderStatus==='失败'" class="detail-text">已迫降，待人工回收 · {{ current.emergencyLandingPoint ? `${current.emergencyLandingPoint.lon}, ${current.emergencyLandingPoint.lat}` : '迫降位置待更新' }}</div><div v-if="current.orderStatus==='异常终止'" class="detail-text">货物灭失，任务终止</div><div v-if="current.handoverPhase" class="detail-text">末段阶段：{{ current.handoverPhase }}</div><ol class="timeline"><li v-for="entry in entries" :key="entry.version"><span class="timeline-marker"></span><span class="timeline-label">{{ entry.status }}</span><time>{{ time(entry.ts) }}</time></li></ol></template><div v-else class="empty-state">暂无订单记录</div></section></template>
