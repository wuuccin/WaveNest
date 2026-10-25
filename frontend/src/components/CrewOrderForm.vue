<script setup>
import { reactive, ref } from 'vue'
import { api } from '../api/client'
import { useOrderStore } from '../stores'
const props=defineProps({source:{type:String,required:true},guided:{type:Boolean,default:false}})
const emit=defineEmits(['created'])
const orders=useOrderStore(),busy=ref(false),message=ref('')
const form=reactive({shipId:import.meta.env.VITE_SIM_SHIP_MMSI || '123456789',goods:'应急药品',weightKg:2,urgency:'普通',expectedTime:''})
async function submit(){
  busy.value=true;message.value=''
  try{
    if(!Number.isFinite(Number(form.weightKg))||Number(form.weightKg)<=0||Number(form.weightKg)>30)throw new Error('重量应在 0-30 kg 之间')
    if(!/^\d{9}$/.test(form.shipId))throw new Error('船舶 MMSI 应为 9 位数字')
    const body={shipMmsi:form.shipId,goodsWeight:Number(form.weightKg),goodsType:form.goods,priority:({普通:'NORMAL',紧急:'URGENT',特急:'EMERGENCY'})[form.urgency]}
    if(form.expectedTime){const expected=Date.parse(form.expectedTime);if(!Number.isFinite(expected)||expected<=Date.now())throw new Error('期望送达时间必须晚于当前时间');body.expectedTimeMs=expected}
    const order=props.source==='live'?await api.createOrder(body):{
      orderId:`LOCAL-${Date.now()}`,orderStatus:'待受理',eventTs:Date.now(),stateVersion:1,
      waitReason:null,wakeEtaTs:null,emergencyLandingPoint:null,handoverPhase:null,...body}
    if (props.source==='live' && order.accepted === false) throw new Error(order.message || order.reasonCode || '订单未受理')
    if(props.source==='live')orders.snapshot(await api.orders());else orders.apply(order)
    message.value=`订单 ${order.orderId} 已${props.source==='live'?'由服务接收':'加入本地演示'}`;emit('created',order)
  }catch(e){message.value=e.message}
  finally{busy.value=false}
}
</script>
<template><form class="crew-form" @submit.prevent="submit"><div class="section-heading">填写配送需求 <span class="eyebrow">NEW ORDER</span></div><p class="form-explainer">{{ source === 'live' ? '填写目标船和物资信息。提交后由后端完成基本校验并创建订单；启用演示服务时，后续配送状态为仿真记录。' : '填写目标船和物资信息。此模式在本地创建样例订单，不提交后端。' }}</p><label>目标船舶 MMSI（9 位数字）<input v-model.trim="form.shipId" required maxlength="9" inputmode="numeric" pattern="[0-9]{9}"/></label><div class="crew-form-row"><label>要配送的物资<input v-model.trim="form.goods" required maxlength="40"/></label><label>物资重量（kg）<input v-model.number="form.weightKg" required type="number" min="0.1" max="30" step="0.1"/></label></div><label>紧急程度<select v-model="form.urgency"><option>普通</option><option>紧急</option><option>特急</option></select></label><label>期望送达时间（可选）<input v-model="form.expectedTime" type="datetime-local"/></label><button class="button-primary crew-button" type="submit" :disabled="busy">{{ busy?'提交中':guided?'创建订单，开始演示 →':'提交配送订单 →' }}</button><p v-if="message" class="action-feedback" role="status">{{ message }}</p></form></template>
