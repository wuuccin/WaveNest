<script setup>
import { reactive, ref } from 'vue'
import { api } from '../api/client'
import { useOrderStore } from '../stores'
const props=defineProps({source:{type:String,required:true}})
const emit=defineEmits(['created'])
const orders=useOrderStore(),busy=ref(false),message=ref('')
const form=reactive({shipId:'江运 08',goods:'应急药品',weightKg:2,urgency:'普通',contact:'船员',note:''})
async function submit(){
  busy.value=true;message.value=''
  try{
    if(!Number.isFinite(Number(form.weightKg))||Number(form.weightKg)<=0||Number(form.weightKg)>30)throw new Error('重量应在 0-30 kg 之间')
    const body={...form,weightKg:Number(form.weightKg)}
    const order=props.source==='live'?await api.createOrder(body):{
      orderId:`LOCAL-${Date.now()}`,orderStatus:'待受理',eventTs:Date.now(),stateVersion:1,
      waitReason:null,wakeEtaTs:null,emergencyLandingPoint:null,handoverPhase:null,...body}
    if(props.source==='live')orders.snapshot(await api.orders());else orders.apply(order)
    message.value=`订单 ${order.orderId} 已${props.source==='live'?'由演示服务接收':'加入本地演示'}`;emit('created',message.value)
  }catch(e){message.value=e.message}
  finally{busy.value=false}
}
</script>
<template><form class="crew-form" @submit.prevent="submit"><div class="section-heading">发起物资订单 <span class="eyebrow">NEW ORDER</span></div><label>本船<input v-model.trim="form.shipId" required maxlength="40"/></label><div class="crew-form-row"><label>物资类型<input v-model.trim="form.goods" required maxlength="40"/></label><label>重量 · kg<input v-model.number="form.weightKg" required type="number" min="0.1" max="30" step="0.1"/></label></div><label>紧急程度<select v-model="form.urgency"><option>普通</option><option>紧急</option><option>特急</option></select></label><label>联系人<input v-model.trim="form.contact" required maxlength="40"/></label><label>备注<input v-model.trim="form.note" maxlength="120"/></label><button class="button-primary crew-button" type="submit" :disabled="busy">{{ busy?'提交中':'提交订单' }}</button><p v-if="message" class="action-feedback" role="status">{{ message }}</p></form></template>
