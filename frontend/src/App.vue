<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import MapView from './components/MapView.vue'
import OrderTimeline from './components/OrderTimeline.vue'
import EventStream from './components/EventStream.vue'
import CrewOrderForm from './components/CrewOrderForm.vue'
import GuidedDemo from './components/GuidedDemo.vue'
import { createRealtime } from './realtime'
import { DEMO_STAGES } from './realtime/demo'
import { normalizeFrame } from './api/adapter'
import { SCENES } from './realtime/mock'
import { GUIDE_STAGES, buildLiveGuideSteps, buildOfflineGuideSteps, buildContinueGuideSteps } from './realtime/guided'
import { buildReplaySteps, droneIdForOrder, normalizeReplayTasks } from './replay/model'
import { api } from './api/client'
import { useOrderStore, useShipStore, useDroneStore, useAlertStore, useEventStore } from './stores'

const route = ref(location.hash.slice(1) || 'dashboard')
const sceneIndex = ref(0)
const replayIndex = ref(0)
const busy = ref(false)
const feedback = ref('')
const savedSource = localStorage.getItem('wavenest-source')
const source = ref(savedSource === 'mock' || savedSource === 'live' ? savedSource : (import.meta.env.VITE_DATA_SOURCE === 'mock' ? 'mock' : 'live'))
const replaySteps = ref([])
const returnGuideSteps = ref([])
const taskList = ref([])
const selectedTask = ref('')
const recordCount = ref(0)
const selectedOrderId = ref('')
const consoleFocus = ref('orders')
const crewMapOpen = ref(false)
const screenRecording = ref(false)
const guide = reactive({ orderId: '', loading: false, ready: false, error: '', index: 0, waitSeconds: 0, branch: '' })
let guideToken = 0
let screenRecorder
let screenStream

const realtime = createRealtime()
const orders = useOrderStore()
const ship = useShipStore()
const drone = useDroneStore()
const alerts = useAlertStore()
const events = useEventStore()
const connection = realtime.status

const view = computed(() => ['dashboard', 'console', 'crew', 'replay', 'guide'].includes(route.value) ? route.value : 'dashboard')
const pageTitle = computed(() => ({ dashboard: '运行总览', console: '调度台', crew: '船员下单', replay: '任务回放', guide: '分步演示' })[view.value])
const pageDescription = computed(() => ({
  dashboard: '实时查看船舶、无人机、会面点和任务状态。',
  console: '查看全部订单，并执行需要后端确认的调度操作。',
  crew: '提交一笔物资订单，跟踪它从受理到完成的全过程。',
  replay: '按历史帧查看一条任务的轨迹、告警和状态变化。',
  guide: '先创建订单，船舶变向后选择继续配送或放弃会面，并逐步查看对应航线。'
})[view.value])
const current = computed(() => {
  if (selectedOrderId.value && orders.byId[selectedOrderId.value]) return orders.byId[selectedOrderId.value]
  return orders.orders[0]
})
const guideComparison = computed(() => {
  const openingFrames = returnGuideSteps.value[0]?.frames || []
  return {
    ship: openingFrames.find(([type]) => type === 'ship_traj')?.[1] || null,
    meeting: openingFrames.find(([type]) => type === 'rendezvous')?.[1] || null
  }
})
const activeCount = computed(() => orders.orders.filter(order => !['已完成', '已拒绝', '失败', '异常终止'].includes(order.orderStatus)).length)
const guideRoute = computed(() => {
  if (!guide.branch || guide.index < 3) return null
  const opening = returnGuideSteps.value.slice(0, 3).flatMap(step => step.frames)
  const start = opening.filter(([type]) => type === 'drone_state').at(-1)?.[1]
  const meeting = opening.filter(([type]) => type === 'rendezvous').at(-1)?.[1]
  if (!start || !meeting) return null
  const first = { lon: start.lon, lat: start.lat }
  if (guide.branch === 'continue') return { type: 'continue', points: [first, { lon: meeting.lon, lat: meeting.lat }] }
  const firstDrone = returnGuideSteps.value[1]?.frames.find(([type]) => type === 'drone_state')?.[1]
  const nest = firstDrone?.segments?.[0]?.points?.[0] || { lon: first.lon - .025, lat: first.lat - .015 }
  return { type: 'return', points: [first, nest] }
})
const completedCount = computed(() => orders.orders.filter(order => order.orderStatus === '已完成').length)
const stageIndex = computed(() => source.value === 'mock' ? sceneIndex.value : 0)
const stageTitle = computed(() => stageIndex.value > 0 ? DEMO_STAGES[stageIndex.value - 1] : '等待开始')
const showDemoProgress = computed(() => source.value === 'mock' && sceneIndex.value > 0)
const phase = computed(() => drone.current?.flightPhase || '待命')
const battery = computed(() => Number.isFinite(drone.current?.battery) ? Math.round(drone.current.battery) : null)
const meetingLabel = computed(() => {
  const point = orders.rendezvous
  return point && Number.isFinite(point.lon) && Number.isFinite(point.lat) ? `${point.lon.toFixed(3)}°E / ${point.lat.toFixed(3)}°N` : '等待计算'
})
const isTerminal = computed(() => ['已完成', '已拒绝', '失败', '异常终止'].includes(current.value?.orderStatus))
const progressPercent = computed(() => `${Math.min(100, Math.max(0, Math.round(stageIndex.value / DEMO_STAGES.length * 100)))}%`)
const clock = ref(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
let clockTimer

const onHash = () => {
  const next = location.hash.slice(1) || 'dashboard'
  if (route.value === 'guide' && next !== 'guide') closeGuideSession(next !== 'replay')
  route.value = next
}
const go = target => { location.hash = `#${target}` }
const openConsole = focus => { consoleFocus.value = focus; go('console') }
const stageClass = index => ({
  done: index < stageIndex.value,
  active: index === stageIndex.value - 1,
  pending: index >= stageIndex.value
})
const selectOrder = orderId => { selectedOrderId.value = orderId }

onMounted(() => {
  window.addEventListener('hashchange', onHash)
  clockTimer = setInterval(() => { clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }, 1000)
  source.value === 'live' ? realtime.connect() : realtime.resetMock()
})

onUnmounted(() => {
  guideToken++
  window.removeEventListener('hashchange', onHash)
  clearInterval(clockTimer)
  realtime.close()
  stopScreenRecording()
})

async function startScreenRecording() {
  if (screenRecording.value) return
  if (!navigator.mediaDevices?.getDisplayMedia || !window.MediaRecorder) {
    feedback.value = '当前浏览器不支持屏幕录制'
    return
  }
  try {
    const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })
    const mimeType = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].find(type => MediaRecorder.isTypeSupported(type))
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)
    const chunks = []
    recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data) }
    recorder.onstop = () => {
      screenRecording.value = false
      stream.getTracks().forEach(track => track.stop())
      if (screenRecorder === recorder) { screenRecorder = null; screenStream = null }
      if (!chunks.length) { feedback.value = '没有生成录制文件'; return }
      const url = URL.createObjectURL(new Blob(chunks, { type: recorder.mimeType || 'video/webm' }))
      const link = document.createElement('a')
      link.href = url
      link.download = `wavenest-screen-${Date.now()}.webm`
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 60000)
      feedback.value = '录制文件已下载'
    }
    stream.getVideoTracks()[0]?.addEventListener('ended', () => stopScreenRecording(), { once: true })
    screenStream = stream
    screenRecorder = recorder
    recorder.start(1000)
    screenRecording.value = true
    feedback.value = '正在录制当前页面'
  } catch (error) {
    screenStream?.getTracks().forEach(track => track.stop())
    screenStream = null
    screenRecorder = null
    feedback.value = error.name === 'NotAllowedError' ? '已取消录制' : `录制启动失败：${error.message}`
  }
}

function stopScreenRecording() {
  if (screenRecorder?.state === 'recording') screenRecorder.stop()
  else screenStream?.getTracks().forEach(track => track.stop())
}

function selectSource(next) {
  if (source.value === next) return
  guideToken++
  Object.assign(guide, { orderId: '', loading: false, ready: false, error: '', index: 0, waitSeconds: 0, branch: '' })
  returnGuideSteps.value = []
  source.value = next
  localStorage.setItem('wavenest-source', next)
  feedback.value = ''
  sceneIndex.value = 0
  selectedOrderId.value = ''
  orders.reset()
  alerts.clear()
  events.clear()
  ship.apply(null)
  drone.apply(null)
  next === 'live' ? realtime.connect() : realtime.resetMock()
}

async function onOrderCreated() {
  if (source.value !== 'live') return
  try {
    const fresh = await api.orders()
    orders.snapshot(fresh)
    selectedOrderId.value = orders.orders[0]?.orderId || ''
  } catch (error) {
    feedback.value = `订单刷新失败：${error.message}`
  }
}

function startGuide() {
  guideToken++
  if (orders.replaying) {
    orders.replaying = false
    source.value === 'live' ? realtime.connect() : realtime.resetMock()
  }
  Object.assign(guide, { orderId: '', loading: false, ready: false, error: '', index: 0, waitSeconds: 0, branch: '' })
  returnGuideSteps.value = []
  feedback.value = ''
  go('guide')
}

function closeGuideSession(restore = true) {
  guideToken++
  guide.loading = false
  guide.ready = false
  if (restore && orders.replaying) {
    orders.replaying = false
    source.value === 'live' ? realtime.connect() : realtime.resetMock()
  }
}

function finishGuide() {
  closeGuideSession()
  go('dashboard')
}

function showGuideStage(index) {
  if (index > 2 && !guide.branch) return
  guide.index = Math.max(0, Math.min(GUIDE_STAGES.length - 1, index))
  showReplay(guide.index)
}

function chooseGuideBranch(branch) {
  if (guide.index !== 2 || !['continue', 'return'].includes(branch)) return
  try {
    replaySteps.value = branch === 'continue'
      ? buildContinueGuideSteps(returnGuideSteps.value, guide.orderId)
      : returnGuideSteps.value
    guide.branch = branch
    showGuideStage(3)
  } catch (error) { guide.error = error.message }
}

function previousGuideStage() {
  if (guide.index === 3) {
    guide.branch = ''
    replaySteps.value = returnGuideSteps.value
  }
  showGuideStage(guide.index - 1)
}

async function onGuideOrderCreated(order) {
  if (!order?.orderId) { guide.error = '没有收到订单编号，请重新提交'; return }
  guide.orderId = order.orderId
  selectedOrderId.value = order.orderId
  if (source.value === 'mock') {
    returnGuideSteps.value = buildOfflineGuideSteps(order.orderId)
    replaySteps.value = returnGuideSteps.value
    guide.ready = true
    showGuideStage(0)
    return
  }
  await loadGuidedHistory()
}

async function loadGuidedHistory() {
  const token = ++guideToken
  guide.loading = true
  guide.ready = false
  guide.error = ''
  guide.waitSeconds = 0
  let lastError = ''
  for (let attempt = 0; attempt < 22; attempt += 1) {
    if (token !== guideToken) return
    try {
      const tasks = normalizeReplayTasks(await api.tasks())
      const droneId = droneIdForOrder(tasks, guide.orderId)
      if (droneId) {
        const history = await api.replay(droneId)
        if (history.frames?.some(frame => frame.type === 'order_state' && ['FINISHED', '已完成'].includes(frame.data?.orderStatus))) {
          returnGuideSteps.value = buildLiveGuideSteps(history.frames)
          replaySteps.value = returnGuideSteps.value
          if (token !== guideToken) return
          guide.loading = false
          guide.ready = true
          showGuideStage(0)
          return
        }
      }
    } catch (error) { lastError = error.message }
    await new Promise(resolve => setTimeout(resolve, 1000))
    guide.waitSeconds = attempt + 1
  }
  if (token !== guideToken) return
  guide.loading = false
  guide.error = `后端尚未返回完整演示记录。订单 ${guide.orderId} 已提交，可稍后重试；${lastError || '也可以在调度台查看当前状态'}。`
}

const actions = ['调整会面点', '撤回未起飞订单', '取消订单', '冻结派单', '紧急叫停', '人工接管']
async function intervene(action) {
  if (busy.value) return
  busy.value = true
  feedback.value = '正在等待后端确认'
  try {
    const result = await api.intervene({ action, orderId: current.value?.orderId })
    if (result.confirmed) {
      const fresh = await api.orders()
      orders.snapshot(fresh)
      feedback.value = `${action}已确认`
    } else feedback.value = result.message || '后端未确认操作'
  } catch (error) {
    feedback.value = `操作未确认：${error.message}`
    try { orders.snapshot(await api.orders()) } catch { /* preserve the last visible snapshot */ }
  } finally {
    busy.value = false
  }
}

const replayFrames = computed(() => replaySteps.value.slice(0, replayIndex.value + 1).flatMap(step => step.frames))
function showReplay(index) {
  replayIndex.value = Number(index)
  orders.reset()
  alerts.clear()
  events.clear()
  ship.apply(null)
  drone.apply(null)
  for (const [type, data] of replayFrames.value) realtime.emit(type, data, true)
  orders.replaying = true
}

function exitReplay() {
  orders.replaying = false
  if (source.value === 'live') realtime.connect()
  else {
    const count = sceneIndex.value
    realtime.resetMock()
    sceneIndex.value = 0
    for (let index = 0; index < count; index += 1) realtime.nextMock()
  }
  go('dashboard')
}

async function loadReplay() {
  feedback.value = ''
  recordCount.value = 0
  replaySteps.value = []
  try {
    const orderId = selectedTask.value
    if (!orderId) throw new Error('暂无可回放任务，请先完成一次演示')
    const droneId = droneIdForOrder(taskList.value, orderId)
    if (!droneId) throw new Error('该订单尚未分配无人机')
    const history = await api.replay(droneId)
    const frames = Array.isArray(history?.frames) ? history.frames : []
    recordCount.value = frames.length
    replaySteps.value = buildReplaySteps(frames)
    if (!replaySteps.value.length) throw new Error('该任务暂无飞行记录')
    showReplay(0)
  } catch (error) {
    feedback.value = error.status === 404 ? '该任务暂无飞行记录' : `回放读取失败：${error.message}`
    replaySteps.value = []
  }
}

async function enterReplay() {
  if (source.value === 'mock') {
    replaySteps.value = SCENES
    showReplay(0)
  } else {
    try {
      taskList.value = normalizeReplayTasks(await api.tasks())
      selectedTask.value = taskList.value.find(task => task.orderId === current.value?.orderId)?.orderId || taskList.value[0]?.orderId || ''
    } catch (error) {
      feedback.value = `任务列表读取失败：${error.message}`
    }
    await loadReplay()
  }
  go('replay')
}

function exportRecording() {
  const offline = source.value === 'mock'
  const frames = offline ? SCENES.flatMap(scene => scene.frames.map(([type, data]) => ({ type, data }))) : realtime.recording
  if (!frames.length) { feedback.value = '当前没有可导出的帧'; return }
  const packageData = { format: 'wavenest.frames.v1', source: offline ? 'offline-demo' : 'live', frames }
  if (offline) packageData.steps = SCENES.map(scene => ({ title: scene.title, frames: scene.frames.map(([type, data]) => ({ type, data })) }))
  const url = URL.createObjectURL(new Blob([JSON.stringify(packageData, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = url
  link.download = offline ? 'wavenest-offline-demo.json' : `wavenest-${Date.now()}.json`
  link.click()
  URL.revokeObjectURL(url)
  feedback.value = '回放数据已导出'
}

async function importRecording(event) {
  try {
    const content = JSON.parse(await event.target.files[0].text())
    if (!Array.isArray(content.frames) || !content.frames.length) throw new Error('文件中没有历史帧')
    const allowed = ['order_state', 'rendezvous', 'drone_state', 'ship_traj', 'alarm']
    const rawSteps = Array.isArray(content.steps) && content.steps.length ? content.steps : content.frames.map(frame => ({ title: frame.type, frames: [frame] }))
    const steps = rawSteps.map(step => ({
      title: typeof step.title === 'string' ? step.title : '历史片段',
      frames: step.frames.map(frame => {
        const item = Array.isArray(frame) ? { type: frame[0], data: frame[1] } : frame
        if (!allowed.includes(item.type)) throw new Error(`不支持的帧类型：${item.type}`)
        return [item.type, normalizeFrame(item.type, item.data)]
      })
    }))
    replaySteps.value = steps
    showReplay(0)
    go('replay')
    feedback.value = '已载入回放文件'
  } catch (error) {
    feedback.value = `导入失败：${error.message}`
  } finally {
    event.target.value = ''
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <a class="brand" href="#dashboard" aria-label="返回运行总览">
        <span class="brand-mark">W</span>
        <span><strong>WaveNest</strong><small>沿江智能配送监控平台</small></span>
      </a>
      <nav class="main-nav" aria-label="主导航">
        <a href="#dashboard" :class="{ selected: view === 'dashboard' }">运行总览</a>
        <a href="#console" :class="{ selected: view === 'console' }">调度台</a>
        <a href="#crew" :class="{ selected: view === 'crew' }">船员下单</a>
        <a href="#guide" :class="{ selected: view === 'guide' }" @click.prevent="startGuide">分步演示</a>
        <a href="#replay" :class="{ selected: view === 'replay' }" @click.prevent="enterReplay">任务回放</a>
      </nav>
      <div class="header-status"><span class="status-dot" :class="{ warning: source === 'mock' || connection.connection === 'REST 轮询中' }"></span><span>{{ source === 'mock' ? '离线演示' : connection.connection }}</span><time>{{ clock }}</time></div>
    </header>

    <main class="app-main">
      <section class="page-heading">
        <div><p class="breadcrumb">WAVENEST / {{ pageTitle }}</p><h1>{{ pageTitle }}</h1><p class="page-description">{{ pageDescription }}</p></div>
        <div class="heading-actions"><div class="mode-switch" role="group" aria-label="数据来源"><span>数据来源</span><button :class="{ active: source === 'live' }" @click="selectSource('live')">实时服务</button><button :class="{ active: source === 'mock' }" @click="selectSource('mock')">离线演示</button></div></div>
      </section>

      <div v-if="connection.error || feedback" class="system-notice" role="status"><span class="notice-mark">!</span><span>{{ feedback || connection.error }}</span></div>
      <section v-if="['console', 'crew', 'replay'].includes(view)" class="page-guide" aria-label="页面使用说明">
        <strong>{{ view === 'console' ? '调度台怎么用' : view === 'crew' ? '下单后会发生什么' : '怎样查看历史任务' }}</strong>
        <div v-if="view === 'console'"><span><b>1</b>在订单队列里选一笔订单</span><span><b>2</b>查看状态和派单闸门</span><span><b>3</b>有异常时使用右侧人工干预</span></div>
        <div v-else-if="view === 'crew'"><span><b>1</b>填写目标船和物资</span><span><b>2</b>提交订单，等待后端受理</span><span><b>3</b>在右侧跟踪订单状态</span></div>
        <div v-else><span><b>1</b>选择历史任务</span><span><b>2</b>拖动进度条查看每一帧</span><span><b>3</b>对照地图和订单进展复盘</span></div>
      </section>

      <template v-if="view === 'dashboard'">
        <section class="operations-hero" aria-label="配送调度入口">
          <div class="operations-hero-copy"><span class="hero-kicker">无人机配送调度工作台</span><h2>从一笔订单开始，跟踪到交接完成</h2><p>船员提交物资需求后，可在这里查看订单、飞行位置和异常。分步演示会在船舶变向时让你选择后续处理方式。</p><div class="hero-buttons"><button class="hero-main-action" @click="go('crew')">＋ 新建配送订单 <span>船员入口</span></button><button class="hero-side-action" @click="startGuide">从下单开始分步演示 →</button></div></div>
          <div class="hero-live-card"><span class="hero-live-label">当前系统</span><strong>{{ source === 'mock' ? '离线演示' : connection.connection }}</strong><p>{{ source === 'mock' ? '正在使用本地样例数据，适合讲解流程。' : '订单、位置和告警来自本机后端服务。' }}</p><div><span>进行中订单</span><b>{{ activeCount }}</b></div><div><span>待关注告警</span><b :class="{ 'value-alert': alerts.items.length }">{{ alerts.items.length }}</b></div></div>
        </section>
        <section class="workflow-grid" aria-label="主要功能">
          <button class="workflow-card" @click="go('crew')"><span class="workflow-number">01</span><span class="workflow-icon">＋</span><strong>船员下单</strong><small>填写船舶、物资和紧急程度；提交后查看是否受理。</small><em>去创建订单 →</em></button>
          <button class="workflow-card" @click="openConsole('orders')"><span class="workflow-number">02</span><span class="workflow-icon">≡</span><strong>订单调度</strong><small>查看订单队列、当前状态和派单闸门，选择需要处理的订单。</small><em>查看 {{ orders.orders.length }} 条订单 →</em></button>
          <button class="workflow-card" @click="openConsole('intervene')"><span class="workflow-number">03</span><span class="workflow-icon">!</span><strong>异常处置</strong><small>收到告警后，在调度台按订单执行冻结、取消或人工接管。</small><em>查看告警与干预 →</em></button>
          <button class="workflow-card" @click="enterReplay"><span class="workflow-number">04</span><span class="workflow-icon">↺</span><strong>任务回放</strong><small>选择历史任务，按时间查看轨迹、交接和状态变化。</small><em>查看历史记录 →</em></button>
        </section>
        <div class="dashboard-section-title"><div><span>实时态势</span><h2>现在运行到哪里</h2></div><p>地图和指标随后端数据更新；点击上方入口执行具体操作。</p></div>
        <section class="metric-strip" aria-label="运行指标"><div><span>当前订单</span><strong>{{ orders.orders.length }}</strong><small>{{ activeCount }} 条进行中</small></div><div><span>已完成任务</span><strong>{{ completedCount }}</strong><small>按后端订单状态统计</small></div><div><span>无人机电量</span><strong>{{ battery === null ? '--' : `${battery}%` }}</strong><small>{{ drone.current?.uavId || '暂无遥测' }}</small></div><div><span>运行告警</span><strong :class="{ 'value-alert': alerts.items.length }">{{ alerts.items.length }}</strong><small>{{ alerts.items.length ? '请查看事件流' : '当前无告警' }}</small></div></section>
        <section class="dashboard-layout">
          <section class="map-panel"><div class="panel-head"><div><h2>任务地图</h2><p>船舶预测轨迹、无人机位置与会面点</p></div><div class="map-legend"><span><i class="legend-line ship"></i>船舶</span><span><i class="legend-dot drone"></i>无人机</span><span><i class="legend-target"></i>会面点</span></div></div><MapView/><div class="map-summary"><div><span>当前船舶</span><strong>{{ ship.current?.shipName || '江运 08' }}</strong></div><div><span>飞行阶段</span><strong>{{ phase }}</strong></div><div><span>会面点</span><strong>{{ meetingLabel }}</strong></div><div><span>数据来源</span><strong>{{ source === 'live' ? '后端实时数据' : '本地演示数据' }}</strong></div></div></section>
          <aside class="mission-column"><section class="mission-panel"><div class="panel-head compact"><div><h2>当前任务</h2><p>{{ current?.orderId || '暂无订单' }}</p></div><span class="state-chip" :class="{ success: isTerminal && current?.orderStatus === '已完成', danger: ['失败', '异常终止'].includes(current?.orderStatus) }">{{ current?.orderStatus || '等待任务' }}</span></div><template v-if="showDemoProgress"><div class="mission-progress"><div class="progress-head"><span>演示步骤</span><strong>{{ stageIndex }} / {{ DEMO_STAGES.length }}</strong></div><div class="progress-track"><span :style="{ width: progressPercent }"></span></div></div><ol class="stage-list"><li v-for="(stage, index) in DEMO_STAGES" :key="stage" :class="stageClass(index)"><span class="stage-number">{{ index + 1 }}</span><span>{{ stage }}</span><b v-if="index < stageIndex">完成</b><b v-else-if="index === stageIndex - 1">进行中</b></li></ol></template><div v-else class="live-order-summary"><span>后端订单状态</span><strong>{{ current?.orderStatus || '暂无订单' }}</strong><p>{{ current ? '完整状态变化请到调度台查看。' : '从“船员下单”创建订单，或开始完整演示。' }}</p></div><div class="mission-facts"><div><span>无人机</span><strong>{{ drone.current?.uavId || '待分配' }}</strong></div><div><span>电量</span><strong>{{ battery === null ? '--' : `${battery}%` }}</strong></div><div><span>会面点</span><strong>{{ orders.rendezvous?.locked ? '已锁定' : '尚未锁定' }}</strong></div><div><span>{{ showDemoProgress ? '演示阶段' : '订单状态' }}</span><strong>{{ showDemoProgress ? stageTitle : (current?.orderStatus || '待开始') }}</strong></div></div><button class="button-secondary full-button" @click="go('console')">打开调度台</button></section><details class="dashboard-events"><summary>查看最新事件</summary><EventStream /></details></aside>
        </section>
        <section class="demo-panel"><div class="demo-copy"><p class="section-kicker">讲解式仿真</p><h2>先亲手下单，再选择变向后的处理</h2><p>船舶变向后，先重新评估会面方案。你可以选择原机继续配送，或放弃本次会面并带货返航；两种选择会显示不同航线，每一步都由你手动推进。</p></div><div class="demo-actions"><button class="button-primary" @click="startGuide">开始分步演示 →</button><button class="button-quiet" @click="enterReplay">查看已有任务回放</button></div><details class="demo-tools"><summary>录屏、导出与导入回放</summary><div><span>演示流程：下单 → 预计会面 → 去程 → 船舶变向 → 选择处理方式 → 查看对应航线与后续步骤</span><button class="text-button" @click="screenRecording ? stopScreenRecording() : startScreenRecording()">{{ screenRecording ? '停止录屏' : '录制当前页面' }}</button><button class="text-button" @click="exportRecording">导出回放</button><label class="text-button">导入回放<input type="file" accept="application/json" @change="importRecording"></label></div></details></section>
      </template>

      <template v-else-if="view === 'console'"><section class="workspace-grid" :class="{ 'focus-intervene': consoleFocus === 'intervene' }"><section class="table-panel"><div class="panel-head"><div><h2>订单队列</h2><p>点击订单查看详情，操作结果以服务端确认信息为准。</p></div><span class="count-badge">{{ orders.orders.length }} 条</span></div><div v-if="orders.orders.length" class="order-table-wrap"><table class="order-table"><thead><tr><th>订单</th><th>状态</th><th>等待原因</th><th>更新时间</th></tr></thead><tbody><tr v-for="item in orders.orders" :key="item.orderId" :class="{ selected: current?.orderId === item.orderId }" @click="selectOrder(item.orderId)"><td><strong>{{ item.orderId }}</strong><small>版本 {{ item.stateVersion }}</small></td><td><span class="table-state" :class="{ done: item.orderStatus === '已完成', danger: ['失败', '异常终止'].includes(item.orderStatus) }">{{ item.orderStatus }}</span></td><td>{{ item.orderStatus === '已受理待唤醒' ? (item.waitReason || '等待调度更新') : '—' }}</td><td>{{ new Date(item.eventTs).toLocaleTimeString('zh-CN', { hour12: false }) }}</td></tr></tbody></table></div><div v-else class="empty-block"><strong>还没有订单</strong><span>去“船员下单”提交第一笔任务。</span><button class="button-secondary" @click="go('crew')">去下单</button></div></section><aside class="control-column"><section class="gate-panel"><div class="panel-head compact"><div><h2>派单闸门</h2><p>调度系统当前可用性</p></div><span class="state-chip" :class="{ danger: orders.gate.gateStatus === '熔断中' }">{{ orders.gate.gateStatus }}</span></div><div class="gate-source">{{ orders.gate.gateSources?.length ? orders.gate.gateSources.join(' / ') : '没有冻结来源' }}</div></section><section class="intervene-panel"><div class="panel-head compact"><div><h2>人工干预</h2><p>当前对象：{{ current?.orderId || '未选择订单' }}</p></div></div><div class="intervene-grid"><button v-for="action in actions" :key="action" :disabled="busy || source === 'mock' || !current" @click="intervene(action)">{{ action }}</button><button :disabled="busy || source === 'mock'" @click="intervene('解除冻结')">解除人工冻结</button></div><p class="muted-note">离线演示只展示画面，实时服务才会调用后端干预接口。</p></section><OrderTimeline :order-id="current?.orderId || ''" /></aside></section></template>

      <template v-else-if="view === 'crew'"><section class="crew-workspace"><section class="form-panel"><div class="panel-head"><div><h2>新建物资订单</h2><p>提交后会进入后端订单队列，实时服务会返回受理结果。</p></div><span class="form-status">{{ source === 'live' ? '实时提交' : '本地演示' }}</span></div><CrewOrderForm :source="source" @created="onOrderCreated" /></section><section class="crew-status-panel"><div class="panel-head"><div><h2>订单反馈</h2><p>当前选择的订单会在这里持续更新。</p></div><button class="text-button" @click="go('dashboard')">返回总览</button></div><OrderTimeline/><details class="crew-map-details" @toggle="crewMapOpen = $event.target.open"><summary>展开任务地图</summary><MapView v-if="crewMapOpen"/></details></section></section></template>

      <template v-else-if="view === 'replay'"><section class="replay-workspace"><section class="map-panel replay-map-panel"><div class="panel-head"><div><h2>历史任务地图</h2><p>{{ selectedTask || '请选择一条任务' }}</p></div><span class="count-badge">{{ recordCount }} 条记录</span></div><MapView/></section><aside class="replay-panel"><div class="panel-head compact"><div><h2>回放控制</h2><p>拖动时间线查看每一帧</p></div><button class="button-quiet" @click="exitReplay">退出回放</button></div><label class="field-label">任务<select v-model="selectedTask" aria-label="回放任务" @change="loadReplay"><option value="">请选择任务</option><option v-for="task in taskList" :key="task.orderId" :value="task.orderId">{{ task.orderId }} · {{ task.droneId || '未派机' }}</option></select></label><div v-if="replaySteps.length" class="replay-current"><span>当前帧</span><strong>{{ replaySteps[replayIndex]?.title }}</strong><small>{{ replayIndex + 1 }} / {{ replaySteps.length }}</small></div><input class="replay-range" type="range" min="0" :max="Math.max(0, replaySteps.length - 1)" :disabled="!replaySteps.length" step="1" :value="replayIndex" @input="showReplay($event.target.value)" aria-label="回放进度"><div class="replay-ticks"><span>开始</span><span>最新记录</span></div><ol class="replay-list"><li v-for="(step, index) in replaySteps" :key="`${step.title}-${index}`" :class="{ active: index === replayIndex }" @click="showReplay(index)"><span>{{ index + 1 }}</span><strong>{{ step.title }}</strong></li></ol><details class="replay-history"><summary>展开订单进展</summary><OrderTimeline/></details></aside></section></template>
      <GuidedDemo v-else :source="source" :guide="guide" :comparison="guideComparison" :route="guideRoute" @created="onGuideOrderCreated" @choose="chooseGuideBranch" @next="showGuideStage(guide.index + 1)" @previous="previousGuideStage" @retry="loadGuidedHistory" @finish="finishGuide" />
    </main>
    <footer><span>WaveNest · 沿江智能配送监控平台</span><span>{{ source === 'live' ? '数据来自后端实时服务' : '当前为离线演示数据' }}</span></footer>
  </div>
</template>
