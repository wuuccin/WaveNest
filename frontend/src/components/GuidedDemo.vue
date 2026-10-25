<script setup>
import { computed } from 'vue'
import CrewOrderForm from './CrewOrderForm.vue'
import MapView from './MapView.vue'
import OrderTimeline from './OrderTimeline.vue'
import EventStream from './EventStream.vue'
import { GUIDE_STAGES, CONTINUE_STAGES } from '../realtime/guided'
import { useOrderStore, useShipStore, useDroneStore } from '../stores'

const props = defineProps({
  source: { type: String, required: true }, guide: { type: Object, required: true },
  comparison: { type: Object, required: true }, route: { type: Object, default: null }
})
const emit = defineEmits(['created', 'choose', 'next', 'previous', 'retry', 'finish'])
const orders = useOrderStore(), ship = useShipStore(), drone = useDroneStore()
const stages = computed(() => props.guide.branch === 'continue' ? CONTINUE_STAGES : GUIDE_STAGES)
const stage = computed(() => stages.value[props.guide.index])
const order = computed(() => orders.byId[props.guide.orderId])
const battery = computed(() => Number.isFinite(drone.current?.battery) ? `${Math.round(drone.current.battery)}%` : '暂无数据')
const awaitingChoice = computed(() => props.guide.index === 2 && !props.guide.branch)
</script>

<template>
  <section class="guided-shell" aria-label="分步演示">
    <div class="guided-intro">
      <div><span class="hero-kicker">亲手操作 · 逐步讲解</span><h2>一笔订单的仿真配送过程</h2><p>先创建订单；船舶变向后，由你选择原机继续配送，或放弃会面并带货返航。每一步都会停下来，等你操作。</p></div>
      <div class="guided-intro-actions"><span class="guided-source">{{ source === 'live' ? '后端返航记录 + 前端情境推演' : '离线样例 + 前端情境推演' }}</span><button class="guided-exit" @click="emit('finish')">退出演示</button></div>
    </div>
    <ol class="guided-rail" aria-label="演示流程">
      <li :class="{ current: !guide.orderId, done: !!guide.orderId }"><b>0</b><span>创建订单</span></li>
      <li v-for="(item, index) in stages" :key="index" :class="{ current: guide.ready && guide.index === index, done: guide.ready && guide.index > index }"><b>{{ index + 1 }}</b><span>{{ index >= 3 && !guide.branch ? '选择后展示' : item.short }}</span></li>
    </ol>

    <div v-if="!guide.orderId" class="guided-start-layout">
      <div class="guided-form-card"><div class="guided-action-heading"><span class="guided-step-label">第 0 步 · 在这里填写</span><h3>创建配送订单</h3><p>填写目标船舶、物资、重量和紧急程度，然后点击“创建订单，开始演示”。</p></div><CrewOrderForm :source="source" guided @created="emit('created', $event)" /></div>
      <div class="guided-explain"><span class="guided-step-label">接下来会看到</span><h3>从下单到变向决策</h3><p>创建订单后依次查看预测会面、无人机去程和船舶变向。到了变向步骤，你亲自选择处理方式，地图才会画出对应的后续航线。</p><div class="guided-note"><strong>演示时可以这样讲</strong><span>“船舶变向意味着要重新评估会面方案；原机能继续配送就继续，只有决定放弃本次会面才带货返航。”</span></div><div class="guided-note"><strong>当前数据来源</strong><span>{{ source === 'live' ? '订单提交到本机后端；返航分支来自后端仿真回放，继续配送分支是前端情境推演。' : '订单和返航样例保存在本地；继续配送分支是前端情境推演。' }}</span></div></div>
    </div>

    <div v-else-if="guide.loading" class="guided-wait" role="status"><span class="guided-spinner"></span><span class="guided-step-label">订单 {{ guide.orderId }} 已创建</span><h3>后端正在生成这笔订单的仿真记录</h3><p>已等待 {{ guide.waitSeconds }} 秒。记录准备好后会停在第一步，等你点击才继续。</p></div>
    <div v-else-if="guide.error" class="guided-wait" role="alert"><span class="guided-step-label">订单 {{ guide.orderId }}</span><h3>暂时无法开始逐步讲解</h3><p>{{ guide.error }}</p><button class="button-primary" @click="emit('retry')">重新读取记录</button><button class="button-quiet" @click="emit('finish')">返回总览</button></div>

    <div v-else-if="guide.ready" class="guided-stage-layout">
      <article class="guided-story">
        <div class="guided-story-main"><div class="guided-story-top"><span class="guided-step-label">第 {{ guide.index + 1 }} / {{ stages.length }} 步</span><span class="guided-order-id">订单 {{ guide.orderId }}</span></div><h3>{{ stage.title }}</h3><div class="guided-story-block"><span>这一步发生了什么</span><p>{{ stage.happens }}</p></div><div class="guided-story-block"><span>业务规则与演示边界</span><p>{{ stage.why }}</p></div></div>
        <div v-if="awaitingChoice" class="guided-decision" aria-label="重新评估后的处理选择"><strong>重新评估会面方案 · 请你选择</strong><p>先判断偏离是否持续，再评估原机是否能赶上新会面点。这里没有默认选择；点击后地图会显示对应航线。</p><div class="guided-decision-actions"><button class="guided-decision-option" @click="emit('choose', 'continue')"><b>① 原机继续配送 →</b><span>UAV-01 追向更新后的会面点；前端情境推演</span></button><button class="guided-decision-option return" @click="emit('choose', 'return')"><b>② 放弃本次会面，带货返航 →</b><span>UAV-01 回机巢，回收货物后重派；{{ source === 'live' ? '后端仿真记录' : '离线样例' }}</span></button></div><small>船舶变向不会自动使原机返航。只有决定放弃本次会面，才进入带货返航分支。</small></div>
        <div v-else class="guided-next-action"><span class="guided-step-label">当前操作</span><strong>{{ guide.branch === 'continue' ? '已选择原机继续配送 · 前端情境推演' : guide.branch === 'return' ? '已选择放弃会面 · 返航仿真记录' : '查看地图变化，再推进下一步' }}</strong><div class="guided-controls"><button class="button-quiet" :disabled="guide.index === 0" @click="emit('previous')">← 上一步{{ guide.index === 3 ? '，重新选择' : '' }}</button><button v-if="guide.index < stages.length - 1" class="button-primary" @click="emit('next')">下一步：{{ stages[guide.index + 1].short }} →</button><button v-else class="button-primary" @click="emit('finish')">流程讲完，返回总览 →</button></div></div>
        <div v-if="awaitingChoice" class="guided-controls"><button class="button-quiet" @click="emit('previous')">← 上一步</button></div>
        <details class="guided-observe"><summary>这一步看什么 · 展开讲解要点</summary><ul><li v-for="point in stage.observe" :key="point">{{ point }}</li></ul></details>
      </article>
      <div class="guided-visuals"><section class="guided-status-card"><div><span>订单状态</span><strong>{{ order?.orderStatus || '等待更新' }}</strong></div><div><span>无人机</span><strong>{{ drone.current?.uavId || '尚未起飞' }}</strong></div><div><span>剩余电量</span><strong>{{ battery }}</strong></div><div><span>船舶</span><strong>{{ ship.current?.shipName || '等待轨迹' }}</strong></div></section><section class="map-panel guided-map-panel"><div class="panel-head"><div><h2>{{ guide.index === 2 ? '变向前后对比 · 请选择下一步' : guide.branch === 'continue' ? '原机继续配送航线' : guide.branch === 'return' ? '带货返航航线' : '这一步的地图变化' }}</h2><p>{{ guide.index >= 2 ? '灰色为变向前预测，绿色为更新后的预测；点击选择后，粗线标出无人机下一段路线' : '看图标和航线标识，跟踪船舶、无人机与会面点' }}</p></div></div><MapView :comparison="guide.index >= 2 ? comparison : null" :branch-route="route" focus-route /></section><details class="guided-history"><summary>查看订单进展与事件流</summary><div class="guided-history-grid"><OrderTimeline :order-id="guide.orderId" /><EventStream /></div></details></div>
    </div>
  </section>
</template>
