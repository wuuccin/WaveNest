<script setup>
import { computed, watch } from 'vue'
import { api } from '../api/client.js'
import { useOrderStore } from '../stores/index.js'

const props = defineProps({ orderId: { type: String, default: '' }, source: { type: String, default: 'live' } })
const store = useOrderStore()
const rows = computed(() => store.candidateHistory[props.orderId] || [])
const loading = computed(() => Boolean(store.candidateLoading[props.orderId]))
const error = computed(() => store.candidateError[props.orderId] || '')
const order = computed(() => store.byId[props.orderId] || null)
const timeline = computed(() => store.timeline[props.orderId] || [])
const when = value => value == null ? '未产生' : new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })

watch(() => [props.orderId, props.source], ([orderId, source]) => { if (orderId && source === 'live') void store.loadCandidateHistory(orderId, api) }, { immediate: true })
</script>

<template>
  <section class="order-candidate-history" aria-label="订单详情与候选历史">
    <div class="panel-head compact"><div><h2>订单详情与候选历史</h2><p>{{ orderId || '请先选择订单' }}</p></div><a v-if="orderId" href="#dashboard" class="button-secondary">查看地图</a></div>
    <template v-if="orderId">
      <div v-if="order" class="history-order-facts"><span>状态：{{ order.orderStatus }}</span><span>货巢：{{ order.cargoNestId || '待指定' }}</span><span>等待：{{ order.waitReason || '无' }}</span><span>版本：{{ order.stateVersion }}</span></div>
      <p v-if="source !== 'live'" class="muted-note">实时服务模式提供已保存的候选历史。</p>
      <p v-else-if="loading" role="status">正在读取候选历史</p>
      <p v-else-if="error" role="alert">候选历史读取失败：{{ error }}</p>
      <p v-else-if="!rows.length" class="muted-note">这笔订单暂无已保存的候选记录。</p>
      <div v-else class="history-candidate-list">
        <article v-for="row in rows" :key="row.key" :class="{ selected: row.selected }">
          <div><strong>{{ row.droneId || '机号未记录' }}</strong><span>{{ row.selected ? '中选' : '落选' }}</span></div>
          <p>机型 {{ row.model || '未记录' }} · 机巢 {{ row.nestId || '未记录' }} · 距离 {{ row.distKm == null ? '未记录' : `${row.distKm.toFixed(2)} km` }}</p>
          <p>会面时刻 {{ when(row.meetTimeMs) }} · 取舍原因 {{ row.rejectReason || '未记录' }}</p>
          <small>择优键 {{ row.rankKey || '未记录' }} · 快照版本 {{ row.snapshotVersion || '未记录' }}</small>
        </article>
      </div>
      <div class="history-intervention"><h3>订单进展与干预记录</h3><p v-if="!timeline.length">当前会话尚无状态记录。</p><ol v-else><li v-for="entry in timeline" :key="entry.version">{{ when(entry.ts) }} · {{ entry.status }}</li></ol><p v-if="order?.safetyReview">在飞安全复核：{{ order.safetyReview.verdict }} · {{ order.safetyReview.opType || '操作未记录' }}</p><small>这里显示订单状态与复核结论。完整干预审计需后端提供按订单查询的接口。</small></div>
    </template>
  </section>
</template>
