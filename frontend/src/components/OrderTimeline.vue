<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import { useOrderStore } from '../stores'
import { waitText,wakeText,etaText } from '../api/adapter'
const store=useOrderStore()
const props=defineProps({ orderId: { type: String, default: '' } })
const current=computed(()=>store.byId[props.orderId] || store.orders[0])
const entries=computed(()=>current.value ? store.timeline[current.value.orderId]||[] : [])
const time=ts=>new Date(ts).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})
const cargoBusy=ref(false),cargoMessage=ref('')
// 只有「已受理待唤醒且等待原因为等货、订单上又确实有货巢」时才让操作者登记：
// 没有货巢就没有比对基准，登记必然被拒，摆在界面上只会误导。
const cargoRegistrable=computed(()=>Boolean(current.value)&&current.value.orderStatus==='已受理待唤醒'&&current.value.waitReason==='WAIT_CARGO'&&Boolean(current.value.cargoNestId))
async function registerCargoReady(){
  if(!current.value||cargoBusy.value)return
  cargoBusy.value=true;cargoMessage.value=''
  try{
    const response=await api.cargoReady(current.value.orderId,current.value.cargoNestId)
    if(response.accepted){
      cargoMessage.value=response.changed?`货物已登记到巢 ${current.value.cargoNestId}`:'货物已是就绪状态，本次未重复登记'
      store.snapshot(await api.orders())
    }else{
      cargoMessage.value=response.message||response.reasonCode||'登记被拒绝'
    }
  }catch(e){cargoMessage.value=e.message}
  finally{cargoBusy.value=false}
}
</script>
<template><section class="timeline-panel"><div class="section-heading"><span>订单进展</span><span class="eyebrow">ORDER STATUS</span></div><template v-if="current"><div class="order-summary"><div><small>{{ current.orderId }}</small><strong>{{ current.orderStatus }}</strong></div><span class="status-indicator" :class="{'done':current.orderStatus==='已完成','warn':['失败','异常终止'].includes(current.orderStatus)}"></span></div><div class="detail-text">预计送达：{{ etaText(current) }}</div><div class="detail-text">货物所在巢：{{ current.cargoNestId || '未指定货巢' }}</div><div v-if="current.orderStatus==='已受理待唤醒'" class="detail-text">{{ waitText(current) }} · {{ wakeText(current) }}</div><div v-if="cargoRegistrable" class="detail-text">货物实际到巢后登记，登记的巢必须与订单货巢一致：<button class="button-primary crew-button" type="button" :disabled="cargoBusy" @click="registerCargoReady">{{ cargoBusy?'登记中':'登记货物到巢' }}</button></div><p v-if="cargoMessage" class="action-feedback" role="status">{{ cargoMessage }}</p><div v-if="current.orderStatus==='失败'" class="detail-text">已迫降，待人工回收 · {{ current.emergencyLandingPoint ? `${current.emergencyLandingPoint.lon}, ${current.emergencyLandingPoint.lat}` : '迫降位置待更新' }}</div><div v-if="current.orderStatus==='异常终止'" class="detail-text">货物灭失，任务终止</div><div v-if="current.handoverPhase" class="detail-text">末段阶段：{{ current.handoverPhase }}</div><ol class="timeline"><li v-for="entry in entries" :key="entry.version"><span class="timeline-marker"></span><span class="timeline-label">{{ entry.status }}</span><time>{{ time(entry.ts) }}</time></li></ol></template><div v-else class="empty-state">暂无订单记录</div></section></template>
