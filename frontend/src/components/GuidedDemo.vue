<script setup>
import { computed } from 'vue'
import CrewOrderForm from './CrewOrderForm.vue'
import MapView from './MapView.vue'
import OrderTimeline from './OrderTimeline.vue'
import EventStream from './EventStream.vue'
import { GUIDE_STAGES } from '../realtime/guided'
import { useOrderStore, useShipStore, useDroneStore } from '../stores'

const props = defineProps({
  source: { type: String, required: true }, guide: { type: Object, required: true }
})
const emit = defineEmits(['created', 'next', 'previous', 'retry', 'finish'])
const orders = useOrderStore(), ship = useShipStore(), drone = useDroneStore()
const selectedShip = computed(() => ship.byId[ship.selectedMmsi] || null)
const stages = computed(() => GUIDE_STAGES)
const stage = computed(() => stages.value[props.guide.index])
const order = computed(() => orders.byId[props.guide.orderId])
const battery = computed(() => Number.isFinite(drone.current?.battery) ? `${Math.round(drone.current.battery)}%` : '暂无数据')
</script>

<template>
  <section class="guided-shell" aria-label="分步演示">
    <div class="guided-intro">
      <div><span class="hero-kicker">亲手操作 · 逐步讲解</span><h2>一笔订单的仿真配送过程</h2><p>先创建订单，再逐步观察风速突变、气象超限、无人机返航、风况回落和重新派单。</p></div>
      <div class="guided-intro-actions"><span class="guided-source">{{ source === 'live' ? '后端回放记录' : '离线样例记录' }}</span><button class="guided-exit" @click="emit('finish')">退出演示</button></div>
    </div>
    <ol class="guided-rail" aria-label="演示流程">
      <li :class="{ current: !guide.orderId, done: !!guide.orderId }"><b>0</b><span>创建订单</span></li>
      <li v-for="(item, index) in stages" :key="index" :class="{ current: guide.ready && guide.index === index, done: guide.ready && guide.index > index }"><b>{{ index + 1 }}</b><span>{{ item.short }}</span></li>
    </ol>

    <div v-if="!guide.orderId" class="guided-start-layout">
      <div class="guided-form-card"><div class="guided-action-heading"><span class="guided-step-label">第 0 步 · 在这里填写</span><h3>创建配送订单</h3><p>填写目标船舶、物资、重量和紧急程度，然后点击“创建订单，开始演示”。</p></div><CrewOrderForm :source="source" guided @created="emit('created', $event)" /></div>
      <div class="guided-explain"><span class="guided-step-label">接下来会看到</span><h3>从下单到风况处置</h3><p>创建订单后依次查看预计会面、无人机去程、气象超限和返航。风况回落后，再观察闸门解除与重新派单。</p><div class="guided-note"><strong>演示时可以这样讲</strong><span>“风速突变触发气象超限。无人机返航依据安全处置结果；闸门解除后，订单才能重新派发。”</span></div><div class="guided-note"><strong>当前数据来源</strong><span>{{ source === 'live' ? '订单提交到后端；每一步以真实回放记录为准。' : '本页展示离线样例，不能代替后端联跑证据。' }}</span></div></div>
    </div>

    <div v-else-if="guide.loading" class="guided-wait" role="status"><span class="guided-spinner"></span><span class="guided-step-label">订单 {{ guide.orderId }} 已创建</span><h3>后端正在生成这笔订单的仿真记录</h3><p>已等待 {{ guide.waitSeconds }} 秒。记录准备好后会停在第一步，等你点击才继续。</p></div>
    <div v-else-if="guide.error" class="guided-wait" role="alert"><span class="guided-step-label">订单 {{ guide.orderId }}</span><h3>暂时无法开始逐步讲解</h3><p>{{ guide.error }}</p><button class="button-primary" @click="emit('retry')">重新读取记录</button><button class="button-quiet" @click="emit('finish')">返回总览</button></div>

    <div v-else-if="guide.ready" class="guided-stage-layout">
      <article class="guided-story">
        <div class="guided-story-main"><div class="guided-story-top"><span class="guided-step-label">第 {{ guide.index + 1 }} / {{ stages.length }} 步</span><span class="guided-order-id">订单 {{ guide.orderId }}</span></div><h3>{{ stage.title }}</h3><div class="guided-story-block"><span>这一步发生了什么</span><p>{{ stage.happens }}</p></div><div class="guided-story-block"><span>业务规则与演示边界</span><p>{{ stage.why }}</p></div></div>
        <div class="guided-next-action"><span class="guided-step-label">当前操作</span><strong>查看风况和无人机状态，再推进下一步</strong><div class="guided-controls"><button class="button-quiet" :disabled="guide.index === 0" @click="emit('previous')">← 上一步</button><button v-if="guide.index < stages.length - 1" class="button-primary" @click="emit('next')">下一步：{{ stages[guide.index + 1].short }} →</button><button v-else class="button-primary" @click="emit('finish')">流程讲完，返回总览 →</button></div></div>
        <details class="guided-observe"><summary>这一步看什么 · 展开讲解要点</summary><ul><li v-for="point in stage.observe" :key="point">{{ point }}</li></ul></details>
      </article>
      <div class="guided-visuals"><section class="guided-status-card"><div><span>订单状态</span><strong>{{ order?.orderStatus || '等待更新' }}</strong></div><div><span>无人机</span><strong>{{ drone.current?.uavId || '尚未起飞' }}</strong></div><div><span>剩余电量</span><strong>{{ battery }}</strong></div><div><span>船舶</span><strong>{{ selectedShip?.shipName || selectedShip?.mmsi || '等待轨迹' }}</strong></div></section><section class="map-panel guided-map-panel"><div class="panel-head"><div><h2>任务航线与气象处置</h2><p>观察原机去程、气象超限后的返航，以及回落后的重新派单</p></div></div><MapView focus-route /></section><details class="guided-history"><summary>查看订单进展与事件流</summary><div class="guided-history-grid"><OrderTimeline :order-id="guide.orderId" /><EventStream /></div></details></div>
    </div>
  </section>
</template>
