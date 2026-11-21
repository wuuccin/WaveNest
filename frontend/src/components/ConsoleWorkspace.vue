<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api/client'
import { useOrderStore, useDroneStore } from '../stores'
import { TERMINAL_STATES, ORDER_STATES, waitText, etaText, cargoTypeText } from '../api/adapter'

// 调度台按「列表页 + 订单详情页」两层组织：列表页只做查看与进入，所有处置动作都落在详情页，
// 全局闸门动作留在列表页的闸门状态带上，避免使用者把冻结整闸误当成冻结某一笔订单。
const props = defineProps({
  detailId: { type: String, default: '' },
  tasks: { type: Array, default: () => [] },
  source: { type: String, default: 'live' },
  busy: { type: Boolean, default: false },
  feedback: { type: String, default: '' }
})
const emit = defineEmits(['open', 'back', 'refresh', 'action'])

const store = useOrderStore()
const drone = useDroneStore()

const PRIORITY_LABEL = { NORMAL: '普通', URGENT: '紧急', EMERGENCY: '特急' }
const FLYING = ['已派发', '去程中', '末段交接中', '返航中']
const WAITING = ['已受理待唤醒', '待调度', '调度中']

const now = ref(Date.now())
let ticker = 0
onMounted(() => { ticker = window.setInterval(() => { now.value = Date.now() }, 20000) })
onUnmounted(() => { window.clearInterval(ticker) })

const orders = computed(() => store.orders)
const gate = computed(() => store.gate)
// 闸门只有明确处于非正常且已取到状态时才用告警色，避免离线演示把「未知」画成熔断。
const gateBlocked = computed(() => Boolean(gate.value.gateStatus) && gate.value.gateStatus !== '正常')
const detail = computed(() => props.detailId ? (store.byId[props.detailId] || null) : null)
const taskOf = orderId => props.tasks.find(task => task.orderId === orderId) || null
const droneIdOf = order => taskOf(order.orderId)?.droneId || ''
const priorityOf = order => taskOf(order.orderId)?.priority ?? order.priority ?? null
const priorityText = order => PRIORITY_LABEL[priorityOf(order)] || '未记录'

const minutesSince = ts => ts ? Math.max(0, Math.floor((now.value - ts) / 60000)) : null
const ageText = order => {
  const minutes = minutesSince(order.eventTs)
  if (minutes === null) return '未记录'
  return minutes < 1 ? '刚刚' : `${minutes} 分钟`
}
const overdueMinutes = order => {
  if (TERMINAL_STATES.has(order.orderStatus) || !order.etaTs || order.etaTs >= now.value) return 0
  return Math.floor((now.value - order.etaTs) / 60000)
}
const clock = ts => ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '—'
const dateClock = ts => ts ? new Date(ts).toLocaleString('zh-CN', { hour12: false }) : '未记录'

// 需要处置包含四种情形：拒绝或失败这类终态、已过预计送达、在飞安全复核给了终止结论、以及等货待人工放行。
const ABNORMAL_STATES = ['已拒绝', '失败', '异常终止']
const needsAttention = order => ABNORMAL_STATES.includes(order.orderStatus)
  || (!TERMINAL_STATES.has(order.orderStatus)
    && (overdueMinutes(order) > 0 || order.safetyReview?.verdict === '终止' || (order.orderStatus === '已受理待唤醒' && order.waitReason === 'WAIT_RESOURCE')))

const summaryCells = computed(() => {
  const all = orders.value
  const cell = (key, label, value, alert) => ({ key, label, value, alert })
  return [
    cell('accepted', '待受理', all.filter(order => order.orderStatus === '待受理').length),
    cell('waiting', '待调度', all.filter(order => WAITING.includes(order.orderStatus)).length),
    cell('flying', '在飞', all.filter(order => FLYING.includes(order.orderStatus)).length),
    cell('attention', '待处置', all.filter(needsAttention).length, true),
    cell('overdue', '超时', all.filter(order => overdueMinutes(order) > 0).length, true)
  ]
})

const updatedAt = computed(() => orders.value.reduce((latest, order) => Math.max(latest, order.eventTs || 0), 0))

const statusFilter = ref('全部')
const priorityFilter = ref('全部')
const sortKey = ref('urgency')
const keyword = ref('')

const sorted = computed(() => {
  const rows = orders.value.filter(order =>
    (statusFilter.value === '全部' || order.orderStatus === statusFilter.value)
    && (priorityFilter.value === '全部' || priorityOf(order) === priorityFilter.value)
    && (!keyword.value.trim() || [order.orderId, droneIdOf(order)].some(text => String(text || '').toLowerCase().includes(keyword.value.trim().toLowerCase()))))
  const byOrderId = (left, right) => String(left.orderId).localeCompare(String(right.orderId))
  const urgency = order => (overdueMinutes(order) > 0 ? 2 : 0) + (needsAttention(order) ? 1 : 0)
  return [...rows].sort((left, right) => {
    if (sortKey.value === 'urgency') return urgency(right) - urgency(left) || (left.eventTs || 0) - (right.eventTs || 0) || byOrderId(left, right)
    if (sortKey.value === 'age') return (left.eventTs || 0) - (right.eventTs || 0) || byOrderId(left, right)
    return byOrderId(left, right)
  })
})

const progressText = order => {
  if (order.safetyReview?.verdict === '终止') return `在飞安全复核已终止，任务停在${order.orderStatus}`
  if (overdueMinutes(order) > 0) return `${order.orderStatus}，预计 ${clock(order.etaTs)} 送达，已超时 ${overdueMinutes(order)} 分钟`
  if (order.waitReason) return waitText(order)
  return order.orderStatus
}

// 详情页：时间线把本会话记到的状态、当前状态、复核结论与本次页面操作合成一条时间轴。
const localActions = ref([])
watch(() => props.detailId, orderId => {
  if (orderId && props.source === 'live') void store.loadCandidateHistory(orderId, api)
}, { immediate: true })

const timeline = computed(() => {
  const order = detail.value
  if (!order) return []
  const rows = []
  const recorded = store.timeline[order.orderId] || []
  for (const entry of recorded) rows.push({ key: `version-${entry.version}`, ts: entry.ts, title: entry.status, actor: '系统自动' })
  if (!recorded.some(entry => entry.version === order.stateVersion)) {
    rows.push({ key: `version-${order.stateVersion}`, ts: order.eventTs, title: order.orderStatus, actor: '系统自动' })
  }
  if (order.safetyReview) {
    rows.push({
      key: 'safety-review',
      ts: order.safetyReview.eventTs || order.eventTs,
      title: `在飞安全复核结论 ${order.safetyReview.verdict}${order.safetyReview.opType ? ` · ${order.safetyReview.opType}` : ''}`,
      actor: '系统自动',
      danger: true
    })
  }
  for (const action of localActions.value.filter(item => item.orderId === order.orderId)) {
    rows.push({ key: action.key, ts: action.ts, title: `${action.action}${action.reason ? `，理由 ${action.reason}` : ''}`, actor: '本页记录', local: true })
  }
  return rows.sort((left, right) => (left.ts || 0) - (right.ts || 0))
})

const candidates = computed(() => store.candidateHistory[props.detailId] || [])
const candidateLoading = computed(() => Boolean(store.candidateLoading[props.detailId]))
const candidateError = computed(() => store.candidateError[props.detailId] || '')
const when = value => value == null ? '未产生' : new Date(value).toLocaleString('zh-CN', { hour12: false })

const detailDrone = computed(() => {
  const order = detail.value
  const droneId = order ? droneIdOf(order) : ''
  return droneId ? (drone.byId[droneId] || null) : null
})
const batteryText = computed(() => Number.isFinite(detailDrone.value?.battery) ? `${Math.round(detailDrone.value.battery)}%` : '未回传')

// 处置动作按作用域分两组，并用只有状态允许时才可点的规则代替原先九个按钮全亮。
const ORDER_ACTIONS = [
  { name: '调整会面点', scope: 'order', allow: FLYING, allowText: '订单在飞' },
  { name: '撤回未起飞订单', scope: 'order', allow: ['已派发', '待调度'], allowText: '订单已派发但未起飞' },
  { name: '取消订单', scope: 'order', allow: [...ORDER_STATES].filter(state => !TERMINAL_STATES.has(state)), allowText: '订单尚未结束', danger: true }
]
const SAFETY_ACTIONS = [
  { name: '紧急叫停', scope: 'safety', allow: FLYING, allowText: '无人机正在执行这笔订单', danger: true },
  { name: '人工接管', scope: 'safety', allow: FLYING, allowText: '无人机正在执行这笔订单' }
]

const actionState = action => {
  if (!detail.value) return { disabled: true, reason: '没有选中订单' }
  if (props.source === 'mock') return { disabled: true, reason: '离线演示不调用后端干预接口' }
  if (props.busy) return { disabled: true, reason: '正在等待后端确认' }
  if (TERMINAL_STATES.has(detail.value.orderStatus)) return { disabled: true, reason: `订单已处于${detail.value.orderStatus}` }
  if (!action.allow.includes(detail.value.orderStatus)) return { disabled: true, reason: `当前是${detail.value.orderStatus}，只在${action.allowText}时可用` }
  return { disabled: false, reason: '' }
}
// 同一组里不可用的动作往往同因，合并成一句话，避免一屏堆五行灰字。
const reasonNote = actions => {
  const disabled = actions.filter(item => actionState(item).reason)
  if (!disabled.length) return ''
  const reasons = [...new Set(disabled.map(item => actionState(item).reason))]
  if (reasons.length === 1) return `这一组当前都不可用：${reasons[0]}。`
  return disabled.map(item => `${item.name}不可用：${actionState(item).reason}`).join('；')
}

// 危险动作先确认并收集理由，理由只记在本页，随请求一起提交之前需要后端补字段。
const pending = ref(null)
const reason = ref('')
const ask = (action, requiresOrder) => { reason.value = ''; pending.value = { action, requiresOrder } }
const closeAsk = () => { pending.value = null; reason.value = '' }
const confirmAsk = () => {
  if (!pending.value || !reason.value.trim()) return
  const { action, requiresOrder } = pending.value
  const order = detail.value
  if (requiresOrder && order) {
    localActions.value.push({ key: `local-${Date.now()}`, ts: Date.now(), action, reason: reason.value.trim(), orderId: order.orderId })
  }
  emit('action', { action, requiresOrder })
  closeAsk()
}
const runOrderAction = action => {
  if (actionState(action).disabled) return
  if (action.danger) { ask(action.name, true); return }
  emit('action', { action: action.name, requiresOrder: true })
}
const runGlobalAction = (action, danger) => {
  if (props.source === 'mock' || props.busy) return
  if (danger) { ask(action, false); return }
  emit('action', { action, requiresOrder: false })
}
</script>

<template>
  <section v-if="!detail" class="console-list">
    <div class="console-summary" aria-label="订单汇总">
      <div v-for="cell in summaryCells" :key="cell.key" class="summary-cell" :class="{ alert: cell.alert && cell.value > 0 }">
        <strong>{{ cell.value }}</strong><span>{{ cell.label }}</span>
      </div>
      <div class="summary-meta">
        <span>订单最近变更 {{ updatedAt ? `${clock(updatedAt)}，${minutesSince(updatedAt)} 分钟前` : '尚未取到订单' }}</span>
        <button class="button-secondary" :disabled="busy" @click="emit('refresh')">刷新</button>
      </div>
    </div>

    <div class="console-gate" :class="{ blocked: gateBlocked }" aria-label="派单闸门状态">
      <span class="gate-dot"></span>
      <strong>派单闸门 {{ gate.gateStatus || '未知' }}</strong>
      <span class="gate-tag">{{ source === 'mock' ? '离线演示不提供闸门状态' : (gate.gateSources?.length ? gate.gateSources.join(' / ') : '没有冻结来源') }}</span>
      <div class="gate-actions">
        <button type="button" :disabled="source === 'mock' || busy" @click="runGlobalAction('冻结派单', true)">冻结派单</button>
        <button type="button" :disabled="source === 'mock' || busy" @click="runGlobalAction('解除冻结', false)">解除人工冻结</button>
        <button type="button" class="danger" :disabled="source === 'mock' || busy" @click="runGlobalAction('重置闸门', true)">重置闸门</button>
      </div>
    </div>
    <p class="console-note">这三个动作作用于整个派单闸门，不会只影响某一笔订单，所以只放在列表页。</p>

    <section class="console-queue">
      <div class="panel-head"><div><h2>订单队列</h2><p>点一行进入这笔订单的详情，所有处置动作都在详情页里。</p></div><span class="count-badge">{{ sorted.length }} 条</span></div>
      <div class="order-filters">
        <label>订单状态<select v-model="statusFilter"><option>全部</option><option v-for="status in ORDER_STATES" :key="status">{{ status }}</option></select></label>
        <label>优先级<select v-model="priorityFilter"><option>全部</option><option value="NORMAL">普通</option><option value="URGENT">紧急</option><option value="EMERGENCY">特急</option></select></label>
        <label>排序<select v-model="sortKey"><option value="urgency">紧迫程度</option><option value="age">等待时长</option><option value="orderId">订单号</option></select></label>
        <label>搜索<input v-model="keyword" type="search" placeholder="订单号或机号"></label>
      </div>
      <div v-if="sorted.length" class="order-table-wrap">
        <table class="order-table console-table">
          <thead><tr><th>订单</th><th>优先级</th><th>状态</th><th>执行机号</th><th>最近进展</th><th>已停留</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in sorted" :key="item.orderId" :class="{ alert: needsAttention(item) }" @click="emit('open', item.orderId)">
              <td><strong>{{ item.orderId }}</strong><small>版本 {{ item.stateVersion }}</small></td>
              <td><span class="priority-chip" :class="(priorityOf(item) || '').toLowerCase()">{{ priorityText(item) }}</span></td>
              <td><span class="table-state" :class="{ done: item.orderStatus === '已完成', warn: item.orderStatus === '已拒绝', danger: ['失败', '异常终止'].includes(item.orderStatus) }">{{ item.orderStatus }}</span></td>
              <td>{{ droneIdOf(item) || '未派机' }}</td>
              <td class="progress-cell">{{ progressText(item) }}</td>
              <td :class="{ overdue: overdueMinutes(item) > 0 }">{{ ageText(item) }}</td>
              <td><button type="button" class="row-enter" @click.stop="emit('open', item.orderId)">{{ TERMINAL_STATES.has(item.orderStatus) ? '查看' : '进入处置' }}</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-block"><strong>当前筛选没有订单</strong><span>调整状态或优先级筛选，或去船员入口下单。</span><a class="button-secondary" href="#crew">去下单</a></div>
      <p class="console-note">行内只放一个进入详情的入口，不摆处置按钮，避免在队列里误触取消或叫停。</p>
    </section>
  </section>

  <section v-else class="console-detail">
    <div class="console-back">
      <button type="button" class="button-secondary" @click="emit('back')">返回订单队列</button>
      <span class="state-chip" :class="{ wait: detail.orderStatus === '已拒绝', danger: ['失败', '异常终止'].includes(detail.orderStatus) }">{{ detail.orderStatus }}</span>
      <span class="mono" style="margin-left:auto">{{ detail.orderId }}</span>
    </div>

    <div class="console-overview" aria-label="订单概览">
      <div><span>货物类型</span><strong>{{ cargoTypeText(detail) }}</strong></div>
      <div><span>执行机号与机型</span><strong>{{ droneIdOf(detail) || '未派机' }}<template v-if="detailDrone?.model"> · {{ detailDrone.model }}</template></strong></div>
      <div><span>货物所在巢</span><strong>{{ detail.cargoNestId || '未指定' }}</strong></div>
      <div><span>优先级</span><strong :class="{ danger: priorityOf(detail) === 'EMERGENCY' }">{{ priorityText(detail) }}</strong></div>
      <div><span>预计送达</span><strong :class="{ danger: overdueMinutes(detail) > 0 }">{{ etaText(detail) }}<template v-if="overdueMinutes(detail) > 0"> · 已超时 {{ overdueMinutes(detail) }} 分钟</template></strong></div>
      <div><span>已停留</span><strong :class="{ danger: overdueMinutes(detail) > 0 }">{{ ageText(detail) }}</strong></div>
    </div>

    <div class="console-detail-grid">
      <div class="console-detail-main">
        <section class="console-panel">
          <div class="panel-head compact"><div><h2>订单进展与干预记录</h2><p>按时间正序，本页记录只在本浏览器里可见</p></div></div>
          <ol class="console-timeline">
            <li v-for="entry in timeline" :key="entry.key" :class="{ danger: entry.danger, local: entry.local }">
              <time>{{ dateClock(entry.ts) }}</time>
              <span>{{ entry.title }}</span>
              <small>{{ entry.actor }}</small>
            </li>
          </ol>
          <p v-if="!timeline.length" class="muted-note">暂无进展记录。</p>
          <p class="muted-note">完整干预审计需要后端按订单提供带操作人与理由的查询接口，当前请求体里还没有这两个字段。</p>
        </section>

        <details class="console-panel console-candidates">
          <summary><span>候选历史</span><small>{{ candidates.length }} 条</small></summary>
          <p v-if="source !== 'live'" class="muted-note">实时服务模式提供已保存的候选历史。</p>
          <p v-else-if="candidateLoading" role="status">正在读取候选历史</p>
          <p v-else-if="candidateError" class="action-feedback" role="alert">候选历史读取失败：{{ candidateError }}</p>
          <p v-else-if="!candidates.length" class="muted-note">这笔订单暂无已保存的候选记录。</p>
          <div v-else class="candidate-table-wrap">
            <table class="console-table">
              <thead><tr><th>机号</th><th>机型</th><th>机巢</th><th>距离</th><th>会面时刻</th><th>取舍原因</th></tr></thead>
              <tbody>
                <tr v-for="row in candidates" :key="row.key">
                  <td><strong>{{ row.droneId || '未记录' }}</strong></td>
                  <td>{{ row.model || '未记录' }}</td>
                  <td>{{ row.nestId || '未记录' }}</td>
                  <td>{{ row.distKm == null ? '未记录' : `${row.distKm.toFixed(2)} 千米` }}</td>
                  <td>{{ when(row.meetTimeMs) }}</td>
                  <td :class="{ 'danger-text': !row.selected && !row.rejectReason }">{{ row.selected ? `中选${row.rankKey ? `，${row.rankKey}` : ''}` : (row.rejectReason || '未记录') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </div>

      <aside class="console-detail-side">
        <section class="console-panel console-actions">
          <div class="panel-head compact"><div><h2>人工干预</h2><p>对 {{ detail.orderId }}</p></div></div>
          <div class="action-group">
            <div class="action-group-head"><span class="group-tag">订单状态类</span>作用于这一笔订单</div>
            <button v-for="action in ORDER_ACTIONS" :key="action.name" type="button" :class="{ danger: action.danger }" :disabled="actionState(action).disabled" @click="runOrderAction(action)">{{ action.name }}</button>
            <p v-if="reasonNote(ORDER_ACTIONS)" class="action-why">{{ reasonNote(ORDER_ACTIONS) }}</p>
          </div>
          <div class="action-group">
            <div class="action-group-head"><span class="group-tag danger">飞行安全类</span>作用于在飞无人机</div>
            <button v-for="action in SAFETY_ACTIONS" :key="action.name" type="button" :class="{ danger: action.danger }" :disabled="actionState(action).disabled" @click="runOrderAction(action)">{{ action.name }}</button>
            <p v-if="reasonNote(SAFETY_ACTIONS)" class="action-why">{{ reasonNote(SAFETY_ACTIONS) }}</p>
          </div>
          <p v-if="feedback" class="action-feedback" role="status">{{ feedback }}</p>
        </section>

        <section class="console-panel console-situation">
          <div class="panel-head compact"><div><h2>这一单的态势</h2><p>只读</p></div></div>
          <div class="situation-grid">
            <div><span>飞行阶段</span><strong>{{ detailDrone?.flightPhase || '未回传' }}</strong></div>
            <div><span>剩余电量</span><strong>{{ batteryText }}</strong></div>
            <div><span>最近上报</span><strong>{{ detailDrone?.ts ? `${minutesSince(detailDrone.ts)} 分钟前` : '未回传' }}</strong></div>
            <div><span>货物所在巢</span><strong>{{ detail.cargoNestId || '未指定' }}</strong></div>
          </div>
          <a class="button-secondary" href="#dashboard">到运行总览看地图</a>
        </section>
      </aside>
    </div>
  </section>

  <div v-if="pending" class="confirm-mask" role="dialog" aria-modal="true" :aria-label="`确认${pending.action}`">
    <div class="confirm-box">
      <h3>确认{{ pending.action }}</h3>
      <p v-if="pending.requiresOrder && detail">订单 {{ detail.orderId }} 会立即受到这次操作影响，操作结果以服务端返回为准，且不可撤销。</p>
      <p v-else>这是对派单闸门的全局操作，会影响所有等待派单的订单。</p>
      <label>操作理由<input v-model="reason" type="text" placeholder="必填，例如 货物破损需要就地终止"></label>
      <div class="confirm-actions">
        <button type="button" class="button-secondary" @click="closeAsk">返回</button>
        <button type="button" class="button-danger" :disabled="!reason.trim() || busy" @click="confirmAsk">确认{{ pending.action }}</button>
      </div>
    </div>
  </div>
</template>
