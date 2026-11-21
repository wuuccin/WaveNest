<script setup>
import { computed } from 'vue'
import { useOrderStore } from '../stores'

const props = defineProps({ orderId: { type: String, default: '' } })
const store = useOrderStore()

const current = computed(() => store.byId[props.orderId] || store.orders[0])
const entries = computed(() => (current.value ? store.timeline[current.value.orderId] || [] : []))

const RANK = { 已受理待唤醒: 1, 待调度: 2, 调度中: 2, 已派发: 3, 去程中: 4, 末段交接中: 5, 返航中: 7, 已完成: 8 }
const NODES = [
  { key: '下单', rank: 1 },
  { key: '货物已到巢', rank: 2 },
  { key: '已派发', rank: 3 },
  { key: '去程中', rank: 4 },
  { key: '末段交接中', rank: 5 },
  { key: '已送达', rank: 6 },
  { key: '返航中', rank: 7 },
  { key: '已完成', rank: 8 }
]

const dispatchIndex = computed(() => entries.value.findIndex(entry => entry.status === '已派发'))
const maxRank = computed(() => entries.value.reduce((best, entry) => Math.max(best, RANK[entry.status] || 0), 0))
const hasHistory = computed(() => entries.value.length >= 2)

const deliveredTs = computed(() => {
  const list = entries.value
  for (let index = 1; index < list.length; index += 1) {
    if (list[index].status === '返航中' && list[index - 1].status === '末段交接中') return list[index].ts
  }
  const finished = list.find(entry => entry.status === '已完成')
  return finished ? finished.ts : null
})

const safetyReturn = computed(() => {
  const list = entries.value
  for (let index = 1; index < list.length; index += 1) {
    if (list[index].status === '返航中' && RANK[list[index - 1].status] === 4) return true
  }
  return false
})

function timeOf(key) {
  const list = entries.value
  const firstOf = status => list.find(entry => entry.status === status)?.ts || null
  if (key === '下单') return list[0]?.ts || null
  if (key === '货物已到巢') {
    const end = dispatchIndex.value >= 0 ? dispatchIndex.value : list.length
    for (let index = end - 1; index >= 0; index -= 1) {
      if (list[index].status === '待调度') return list[index].ts
    }
    return null
  }
  if (key === '已派发') return dispatchIndex.value >= 0 ? list[dispatchIndex.value].ts : null
  if (key === '去程中') return dispatchIndex.value >= 0 ? (list.slice(dispatchIndex.value).find(entry => entry.status === '去程中')?.ts || null) : null
  if (key === '末段交接中') return firstOf('末段交接中')
  if (key === '已送达') return deliveredTs.value
  if (key === '返航中') return firstOf('返航中')
  if (key === '已完成') return firstOf('已完成')
  return null
}

const failed = computed(() => ['失败', '异常终止'].includes(current.value?.orderStatus))

const rows = computed(() => {
  const handoverSeen = entries.value.some(entry => entry.status === '末段交接中') || Boolean(deliveredTs.value) || maxRank.value >= 8
  const list = NODES.map(node => {
    let reached
    if (node.key === '末段交接中') reached = handoverSeen
    else if (node.key === '已送达') reached = Boolean(deliveredTs.value)
    else reached = maxRank.value >= node.rank
    return { key: node.key, ts: timeOf(node.key), done: reached, active: false, skipped: false, interrupted: false, note: '' }
  })
  if (failed.value) {
    const firstMissing = list.findIndex(item => !item.done)
    if (firstMissing >= 0) for (let index = firstMissing; index < list.length; index += 1) list[index].interrupted = true
    return list
  }
  let activeIndex = -1
  for (let index = list.length - 1; index >= 0; index -= 1) {
    if (list[index].done) {
      list[index].active = true
      activeIndex = index
      break
    }
  }
  if (hasHistory.value) {
    list.forEach((item, index) => { if (index < activeIndex && !item.done) item.skipped = true })
  }
  const returning = list.find(item => item.key === '返航中')
  if (returning?.done && !list.find(item => item.key === '末段交接中')?.done && safetyReturn.value) returning.note = '未交接，提前返航'
  return list
})

const doneCount = computed(() => rows.value.filter(item => item.done).length)
const summary = computed(() => (failed.value ? `已中断于${current.value?.orderStatus || '异常'}` : `${doneCount.value} / ${NODES.length} 步已完成`))
const timeText = ts => (ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '')
const rowClass = row => ({ done: row.done && !row.active, active: row.active, pending: !row.done && !row.skipped && !row.interrupted, skipped: row.skipped, interrupted: row.interrupted })
</script>

<template>
  <div class="mission-progress lifecycle">
    <div class="progress-head"><span>订单进展</span><strong>{{ summary }}</strong></div>
    <ol class="stage-list lifecycle-list">
      <li v-for="(row, index) in rows" :key="row.key" :class="rowClass(row)">
        <span class="stage-number">{{ index + 1 }}</span>
        <span>{{ row.key }}<small v-if="row.note" class="lifecycle-note">{{ row.note }}</small></span>
        <b v-if="row.done && row.ts">{{ timeText(row.ts) }}</b>
        <b v-else-if="row.skipped || row.interrupted" class="lifecycle-interrupted">未发生</b>
      </li>
    </ol>
  </div>
</template>
