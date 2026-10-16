<script setup>
import { computed } from 'vue'
import { useEventStore } from '../stores'
const store=useEventStore()
const labels={order_state:'订单状态',alarm:'运行告警',rendezvous:'会面点'}
const describe=item=>item.type==='order_state'?`${item.data.orderId} · ${item.data.orderStatus}`:item.type==='alarm'?item.data.message:`${item.data.orderId} · ${item.data.locked?'会面点已锁定':'会面点已更新'}`
const items=computed(()=>store.items.slice(0,7))
</script>
<template><section class="event-panel"><div class="section-heading"><span>事件流</span><span class="eyebrow">LIVE EVENTS</span></div><ul class="event-list"><li v-for="(item,i) in items" :key="`${item.ts}-${i}`"><span class="event-icon" :class="item.type"></span><div><small>{{ labels[item.type] }} <time>{{ new Date(item.ts).toLocaleTimeString('zh-CN',{hour12:false}) }}</time></small><p>{{ describe(item) }}</p></div></li></ul><div v-if="!items.length" class="empty-state">暂无事件</div></section></template>
