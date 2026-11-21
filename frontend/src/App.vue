<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import MapView from './components/MapView.vue'
import OrderTimeline from './components/OrderTimeline.vue'
import OrderLifecycle from './components/OrderLifecycle.vue'
import EventStream from './components/EventStream.vue'
import CrewOrderForm from './components/CrewOrderForm.vue'
import GuidedDemo from './components/GuidedDemo.vue'
import PredictionPanel from './components/PredictionPanel.vue'
import PlanTrackPanel from './components/PlanTrackPanel.vue'
import StatsPanel from './components/StatsPanel.vue'
import RealtimeStatusPanel from './components/RealtimeStatusPanel.vue'
import NestBoardPanel from './components/NestBoardPanel.vue'
import ConsoleWorkspace from './components/ConsoleWorkspace.vue'
import WeatherScalePanel from './components/WeatherScalePanel.vue'
import WeatherInjectPanel from './components/WeatherInjectPanel.vue'
import UavInjectPanel from './components/UavInjectPanel.vue'
import { createRealtime } from './realtime'
import { DEMO_STAGES, createDemoRunner } from './realtime/demo'
import { normalizeFrame } from './api/adapter'
import { SCENES } from './realtime/mock'
import { candidateAssessment } from './realtime/candidateAssessment'
import { GUIDE_STAGES, buildLiveGuideSteps, buildOfflineGuideSteps } from './realtime/guided'
import { buildReplaySteps, droneIdForOrder, normalizeReplayTasks } from './replay/model'
import { api } from './api/client'
import { useOrderStore, useShipStore, useDroneStore, useAlertStore, useEventStore, useNestStore, useFleetStore, useAirspaceStore, usePlanTrackStore, useWeatherStore } from './stores'

const route = ref(location.hash.slice(1) || 'dashboard')
const sceneIndex = ref(0)
const replayIndex = ref(0)
const busy = ref(false)
const feedback = ref('')
// 提示条按语气分色：失败与未确认走危险色，等待与进行中走提示色，其余视为成功。
const NOTICE_DANGER = /失败|未确认|未完成|未生成|不支持|不可|取消|错误/
const NOTICE_BUSY = /等待|正在|进行中/
const noticeTone = computed(() => {
  if (connection.error) return 'danger'
  const text = feedback.value || ''
  if (!text) return 'warn'
  if (NOTICE_DANGER.test(text)) return 'danger'
  if (NOTICE_BUSY.test(text)) return 'warn'
  return 'ok'
})
const savedSource = localStorage.getItem('wavenest-source')
const source = ref(savedSource === 'mock' || savedSource === 'live' ? savedSource : (import.meta.env.VITE_DATA_SOURCE === 'mock' ? 'mock' : 'live'))
const replaySteps = ref([])
const returnGuideSteps = ref([])
const taskList = ref([])
const selectedTask = ref('')
const recordCount = ref(0)
const selectedOrderId = ref('')
const crewMapOpen = ref(false)
const screenRecording = ref(false)
const guide = reactive({ orderId: '', goodsType: '', loading: false, ready: false, error: '', index: 0, waitSeconds: 0, branch: '' })
let guideToken = 0
let screenRecorder
let screenStream

const realtime = createRealtime()
const orders = useOrderStore()
const ship = useShipStore()
const selectedShip = computed(() => ship.byId[ship.selectedMmsi] || null)
const drone = useDroneStore()
const alerts = useAlertStore()
const events = useEventStore()
const nests = useNestStore()
const fleet = useFleetStore()
const airspace = useAirspaceStore()
const planTrack = usePlanTrackStore()
const weather = useWeatherStore()
const connection = realtime.status
const demoRunner = createDemoRunner({ api, subscribe: realtime.subscribe, connected: () => connection.connection === 'WebSocket 已连接' })
const demoState = demoRunner.state
watch(() => demoState.orderId, orderId => { if (orderId) selectedOrderId.value = orderId })

// 调度台分成列表路由 #console 与详情路由 #console/订单号，其余页面仍是一段式哈希。
const routeParts = computed(() => route.value.split('/'))
const view = computed(() => ['dashboard', 'console', 'crew', 'replay', 'guide'].includes(routeParts.value[0]) ? routeParts.value[0] : 'dashboard')
const consoleOrderId = computed(() => routeParts.value[0] === 'console' ? (routeParts.value[1] || '') : '')
const pageTitle = computed(() => ({ dashboard: '运行总览', console: '调度台', crew: '船员下单', replay: '任务回放', guide: '分步演示' })[view.value])
const pageDescription = computed(() => ({
  dashboard: '实时查看船舶、无人机、会面点和任务状态。',
  console: '查看全部订单，并执行需要后端确认的调度操作。',
  crew: '提交一笔物资订单，跟踪它从受理到完成的全过程。',
  replay: '按历史帧查看一条任务的轨迹、告警和状态变化。',
  guide: '先创建订单，再逐步查看风速突变、无人机返航、风况回落和重新派单。'
})[view.value])
const current = computed(() => {
  if (selectedOrderId.value && orders.byId[selectedOrderId.value]) return orders.byId[selectedOrderId.value]
  return orders.orders[0]
})
const currentTask = computed(() => taskList.value.find(task => task.orderId === current.value?.orderId) || null)
const assignedDrone = computed(() => currentTask.value?.droneId ? (drone.byId[currentTask.value.droneId] || null) : null)
const missionDrone = computed(() => currentTask.value?.droneId ? assignedDrone.value : drone.current)
const assignedNestId = computed(() => {
  const assignedDroneId = currentTask.value?.droneId
    || (missionDrone.value?.activeOrderId === current.value?.orderId ? missionDrone.value?.uavId : null)
  const member = fleet.items.find(item => item.droneId === assignedDroneId)
  return member?.currentNestId || member?.homeNestId || null
})
const candidate = computed(() => candidateAssessment(orders.rendezvous, assignedNestId.value))
const activeCount = computed(() => orders.orders.filter(order => !['已完成', '已拒绝', '失败', '异常终止'].includes(order.orderStatus)).length)
const completedCount = computed(() => orders.orders.filter(order => order.orderStatus === '已完成').length)
const stageIndex = computed(() => source.value === 'mock' ? 0 : demoState.step)
const showDemoProgress = computed(() => source.value === 'live' && demoState.step > 0)
const phase = computed(() => missionDrone.value?.flightPhase || '待命')
const battery = computed(() => Number.isFinite(missionDrone.value?.battery) ? Math.round(missionDrone.value.battery) : null)
const uncertaintyLabel = computed(() => {
  const cov = selectedShip.value?.cov
  return Number.isFinite(cov?.sigmaAlong) && Number.isFinite(cov?.sigmaCross)
    ? `±${Math.round(2 * cov.sigmaAlong)} m / ±${Math.round(2 * cov.sigmaCross)} m`
    : '等待预测帧'
})
const meetingLabel = computed(() => {
  if (!current.value) return '暂无订单'
  const point = orders.rendezvous
  return point && Number.isFinite(point.lon) && Number.isFinite(point.lat) ? `${point.lon.toFixed(3)}°E / ${point.lat.toFixed(3)}°N` : '正在计算'
})
const isTerminal = computed(() => ['已完成', '已拒绝', '失败', '异常终止'].includes(current.value?.orderStatus))
const deliveryDroneId = computed(() => demoState.replacementDroneId || Object.values(drone.byId).find(item => item.activeOrderId === demoState.orderId && item.uavId !== demoState.originalDroneId)?.uavId || (currentTask.value?.droneId !== demoState.originalDroneId ? currentTask.value?.droneId : null))
const canConfirmRecycle = computed(() => source.value === 'live' && demoState.running && demoState.step === 6 && !demoState.recycleConfirmed && current.value?.orderId === demoState.orderId && Boolean(current.value?.cargoNestId) && Boolean(demoState.originalDroneId))
const canConfirmDelivery = computed(() => source.value === 'live' && demoState.running && demoState.step === 7 && !demoState.deliveryConfirmed && current.value?.orderId === demoState.orderId && current.value?.orderStatus === '末段交接中' && Boolean(deliveryDroneId.value))
const progressPercent = computed(() => `${Math.min(100, Math.max(0, Math.round((demoState.completed ? DEMO_STAGES.length : stageIndex.value - 1) / DEMO_STAGES.length * 100)))}%`)
const clock = ref(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
let clockTimer
let nestRequestToken = 0
let staticRefreshTimer

const onHash = () => {
  const next = location.hash.slice(1) || 'dashboard'
  if (route.value === 'guide' && next !== 'guide') closeGuideSession(next !== 'replay')
  route.value = next
}
const go = target => { location.hash = `#${target}` }
const openConsole = () => { go('console') }
const stageClass = index => ({
  done: demoState.completed ? index < stageIndex.value : index < stageIndex.value - 1,
  active: !demoState.completed && index === stageIndex.value - 1,
  pending: index >= stageIndex.value
})
async function resetDemoEnvironment() {
  demoRunner.cancel()
  try {
    await Promise.all([api.resetShip(), api.resetWeather(), api.clearUavInjections()])
    weather.applyStatus(await api.weatherStatus())
    feedback.value = '船舶航向和风况已复位'
  } catch (error) { feedback.value = `仿真复位失败：${error.message}` }
}
const selectOrder = orderId => { selectedOrderId.value = orderId }
const openOrderDetail = orderId => { selectedOrderId.value = orderId; location.hash = `#console/${orderId}` }
const backToQueue = () => { location.hash = '#console' }
const onConsoleAction = payload => intervene(payload.action, payload.requiresOrder)
async function refreshOrders() {
  if (busy.value) return
  busy.value = true
  try {
    await refreshMonitorTasks()
    orders.snapshot(await api.orders())
    feedback.value = '订单已刷新'
  } catch (error) {
    feedback.value = `订单刷新失败：${error.message}`
  } finally { busy.value = false }
}

onMounted(() => {
  window.addEventListener('hashchange', onHash)
  clockTimer = setInterval(() => { clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }, 1000)
  if (source.value === 'live') {
    realtime.connect()
    void refreshMonitorTasks().catch(error => { feedback.value = `任务列表读取失败：${error.message}` })
    void refreshStaticMapData()
    staticRefreshTimer = setInterval(() => { void refreshStaticMapData() }, 5000)
  } else realtime.resetMock()
})

onUnmounted(() => {
  demoRunner.cancel()
  guideToken++
  nestRequestToken++
  window.removeEventListener('hashchange', onHash)
  clearInterval(clockTimer)
  clearInterval(staticRefreshTimer)
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
  demoRunner.cancel()
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
  if (next === 'live') {
    realtime.connect()
    void refreshMonitorTasks().catch(error => { feedback.value = `任务列表读取失败：${error.message}` })
    void refreshStaticMapData()
    clearInterval(staticRefreshTimer)
    staticRefreshTimer = setInterval(() => { void refreshStaticMapData() }, 5000)
  } else {
    nests.reset()
    fleet.reset()
    airspace.reset()
    clearInterval(staticRefreshTimer)
    realtime.resetMock()
  }
}

async function refreshStaticMapData() {
  const requestToken = ++nestRequestToken
  try {
    const [nestRows, droneRows, airspaceRows] = await Promise.all([api.nests(), api.drones(), api.airspace()])
    if (requestToken === nestRequestToken && source.value === 'live') {
      nests.apply(nestRows)
      fleet.apply(droneRows)
      airspace.apply(airspaceRows)
    }
  } catch (error) {
    if (requestToken === nestRequestToken && source.value === 'live') {
      nests.fail(error)
      fleet.fail(error)
      airspace.fail(error)
    }
  }
}

async function refreshMonitorTasks() {
  taskList.value = normalizeReplayTasks(await api.tasks())
  return taskList.value
}

async function onOrderCreated() {
  if (source.value !== 'live') return
  try {
    const fresh = await api.orders()
    orders.snapshot(fresh)
    selectedOrderId.value = orders.orders[0]?.orderId || ''
    await refreshMonitorTasks()
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
  guide.index = Math.max(0, Math.min(GUIDE_STAGES.length - 1, index))
  showReplay(guide.index)
}

function previousGuideStage() {
  showGuideStage(guide.index - 1)
}

async function onGuideOrderCreated(order) {
  if (!order?.orderId) { guide.error = '没有收到订单编号，请重新提交'; return }
  guide.orderId = order.orderId
  guide.goodsType = order.goodsType || ''
  selectedOrderId.value = order.orderId
  if (source.value === 'mock') {
    returnGuideSteps.value = buildOfflineGuideSteps(order.orderId, guide.goodsType)
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
      const tasks = await refreshMonitorTasks()
      const droneId = droneIdForOrder(tasks, guide.orderId)
      if (droneId) {
        const history = await api.replay(guide.orderId)
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
const resetGate = () => intervene('重置闸门', false)
async function intervene(action, requiresOrder = true) {
  if (busy.value) return
  busy.value = true
  feedback.value = '正在等待后端确认'
  try {
    const result = await api.intervene(requiresOrder ? { action, orderId: current.value?.orderId } : { action })
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

async function confirmRecycle() {
  if (busy.value || !canConfirmRecycle.value) return
  busy.value = true
  try {
    const orderId = current.value.orderId
    await api.confirmRecycle({ orderId, goodsId: `G-${orderId}-1`, uavId: demoState.originalDroneId, nestId: current.value.cargoNestId })
    demoRunner.confirmRecycle()
    feedback.value = '落巢回收确认已送达后端，正在等待货物回收与重新派单'
  } catch (error) { feedback.value = `落巢回收确认失败：${error.message}` }
  finally { busy.value = false }
}

async function confirmDelivery() {
  if (busy.value || !canConfirmDelivery.value) return
  busy.value = true
  try {
    await api.confirmDelivery({ orderId: current.value.orderId, uavId: deliveryDroneId.value })
    demoRunner.confirmDelivery()
    feedback.value = '投放回执已送达后端，正在等待订单完成'
  } catch (error) { feedback.value = `投放回执发送失败：${error.message}` }
  finally { busy.value = false }
}

async function releaseResource() {
  if (busy.value || source.value !== 'live' || current.value?.waitReason !== 'WAIT_RESOURCE') return
  busy.value = true
  try {
    const orderId = current.value.orderId
    await api.releaseResource(orderId)
    orders.snapshot(await api.orders())
    feedback.value = `订单 ${orderId} 已由后端受控释放，等待重新调度`
  } catch (error) { feedback.value = `资源释放未完成：${error.message}` }
  finally { busy.value = false }
}

const replayFrames = computed(() => replaySteps.value.slice(0, replayIndex.value + 1).flatMap(step => step.frames))
function showReplay(index) {
  replayIndex.value = Number(index)
  orders.reset()
  alerts.clear()
  events.clear()
  ship.apply(null)
  drone.apply(null)
  planTrack.reset()
  for (const [type, data] of replayFrames.value) realtime.emit(type, data, true)
  orders.replaying = true
}

function exitReplay() {
  orders.replaying = false
  planTrack.reset()
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
    const history = await api.replay(orderId)
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
      await refreshMonitorTasks()
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
    const allowed = ['order_state', 'rendezvous', 'drone_state', 'ship_traj', 'alarm', 'plan_track']
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

      <div v-if="connection.error || feedback" class="system-notice" :class="noticeTone" role="status"><span class="notice-mark">{{ noticeTone === 'ok' ? '✓' : noticeTone === 'danger' ? '×' : '!' }}</span><span>{{ feedback || connection.error }}</span></div>
      <section v-if="['console', 'crew', 'replay'].includes(view)" class="page-guide" aria-label="页面使用说明">
        <strong>{{ view === 'console' ? '调度台怎么用' : view === 'crew' ? '下单后会发生什么' : '怎样查看历史任务' }}</strong>
        <div v-if="view === 'console'"><span><b>1</b>在订单队列里挑一笔待处置的订单</span><span><b>2</b>进详情页看时间线与候选</span><span><b>3</b>在详情页按作用域执行干预</span></div>
        <div v-else-if="view === 'crew'"><span><b>1</b>填写目标船和物资</span><span><b>2</b>提交订单，等待后端受理</span><span><b>3</b>在右侧跟踪订单状态</span></div>
        <div v-else><span><b>1</b>选择历史任务</span><span><b>2</b>拖动进度条查看每一帧</span><span><b>3</b>对照地图和订单进展复盘</span></div>
      </section>

      <template v-if="view === 'dashboard'">
        <section class="operations-hero" aria-label="配送调度入口">
          <div class="operations-hero-copy"><span class="hero-kicker">无人机配送调度工作台</span><h2>从一笔订单开始，跟踪到交接完成</h2><p>船员提交物资需求之后，在这里就能看到订单走到哪一步、飞机现在在哪、路上发生了什么状况。一键演示会模拟一场突发大风，展示系统怎么让飞机返航，并在风停之后重新派机。</p><div class="hero-buttons"><button class="hero-main-action" @click="go('crew')">＋ 新建配送订单 <span>船员入口</span></button><button class="hero-side-action" @click="startGuide">从下单开始分步演示 →</button><button v-if="source === 'live'" class="hero-side-action" :disabled="demoState.running" @click="demoRunner.run()">{{ demoState.running ? '实时演示进行中' : '一键演示实时主线' }}</button></div><p v-if="demoState.error" role="alert">{{ demoState.error }}</p><p v-else-if="demoState.completed" role="status">后端已确认演示主线完成。</p></div>
          <div class="hero-live-card"><span class="hero-live-label">当前系统</span><strong>{{ source === 'mock' ? '离线演示' : connection.connection }}</strong><p>{{ source === 'mock' ? '正在使用本地样例数据，适合讲解流程。' : '订单、位置和告警来自本机后端服务。' }}</p><div><span>进行中订单</span><b>{{ activeCount }}</b></div><div><span>待关注告警</span><b :class="{ 'value-alert': alerts.items.length }">{{ alerts.items.length }}</b></div></div>
        </section>
        <details class="workflow-disclosure"><summary>四个常用入口</summary>
        <section class="workflow-grid" aria-label="主要功能">
          <button class="workflow-card" @click="go('crew')"><span class="workflow-number">01</span><span class="workflow-icon">＋</span><strong>船员下单</strong><small>填写船舶、物资和紧急程度；提交后查看是否受理。</small><em>去创建订单 →</em></button>
          <button class="workflow-card" @click="openConsole()"><span class="workflow-number">02</span><span class="workflow-icon">≡</span><strong>订单调度</strong><small>查看订单队列、当前状态和派单闸门，选择需要处理的订单。</small><em>查看 {{ orders.orders.length }} 条订单 →</em></button>
          <button class="workflow-card" @click="openConsole()"><span class="workflow-number">03</span><span class="workflow-icon">!</span><strong>异常处置</strong><small>收到告警后，在调度台按订单执行冻结、取消或人工接管。</small><em>查看告警与干预 →</em></button>
          <button class="workflow-card" @click="enterReplay"><span class="workflow-number">04</span><span class="workflow-icon">↺</span><strong>任务回放</strong><small>选择历史任务，按时间查看轨迹、交接和状态变化。</small><em>查看历史记录 →</em></button>
        </section>
        </details>
        <div class="dashboard-section-title"><div><span>实时态势</span><h2>现在运行到哪里</h2></div><p>地图和指标随后端数据更新；点击上方入口执行具体操作。</p></div>
        <section class="metric-strip" aria-label="运行指标"><div><span>当前订单</span><strong>{{ orders.orders.length }}</strong><small>{{ activeCount }} 条进行中</small></div><div><span>已完成任务</span><strong>{{ completedCount }}</strong><small>按后端订单状态统计</small></div><div><span>任务无人机电量</span><strong>{{ battery === null ? '--' : `${battery}%` }}</strong><small>{{ currentTask?.droneId || missionDrone?.uavId || '暂无遥测' }}</small></div><div><span>运行告警</span><strong :class="{ 'value-alert': alerts.items.length }">{{ alerts.items.length }}</strong><small>{{ alerts.items.length ? '请查看事件流' : '当前无告警' }}</small></div></section>
        <section class="overview-panels"><StatsPanel :source="source"/><RealtimeStatusPanel/><NestBoardPanel/></section>
        <details class="weather-disclosure"><summary>风况标尺：预警线 8.0、熔断线 14.0</summary><div class="weather-panels single" aria-label="风况标尺"><WeatherScalePanel :drone-id="currentTask?.droneId || ''"/></div></details>
        <section class="dashboard-layout">
          <section class="map-panel"><div class="panel-head"><div><h2>任务地图</h2><p>船舶实测轨迹与预测轨迹、可能出现的范围、飞机位置与会合点</p></div><div class="map-legend"><span><i class="legend-line ship"></i>船舶</span><span><i class="legend-dot drone"></i>无人机</span><span><i class="legend-target"></i>会面点</span><span v-if="nests.items.length"><i class="legend-nest">⌂</i>后端机巢</span></div></div><MapView :drone-id="currentTask?.droneId || ''" :order-id="current?.orderId || ''" :connection-error="source === 'live' ? connection.error : ''"/><div class="map-summary"><div><span>当前船舶</span><strong>{{ selectedShip?.shipName || selectedShip?.mmsi || '等待实时帧' }}</strong><select v-if="ship.ships.length > 1" :value="ship.selectedMmsi" aria-label="选择船舶" @change="ship.select($event.target.value)"><option v-for="vessel in ship.ships" :key="vessel.mmsi" :value="vessel.mmsi">{{ vessel.shipName || vessel.mmsi }} · {{ vessel.mmsi }}</option></select></div><div><span>飞行阶段</span><strong>{{ phase }}</strong></div><div><span>会面点</span><strong>{{ meetingLabel }}</strong></div><div><span>可能出现的范围（95% 把握）</span><strong>{{ uncertaintyLabel }}</strong></div></div></section>
<aside class="mission-column"><PredictionPanel :source="source"/><PlanTrackPanel :source="source"/><section class="mission-panel"><div class="panel-head compact"><div><h2>当前任务</h2><p>{{ current?.orderId || '暂无订单' }}</p></div><span class="state-chip" :class="{ success: isTerminal && current?.orderStatus === '已完成', danger: ['失败', '异常终止'].includes(current?.orderStatus) }">{{ current?.orderStatus || '等待任务' }}</span></div><OrderLifecycle :order-id="current?.orderId || ''"/><div class="mission-facts"><div><span>无人机</span><strong>{{ currentTask?.droneId || missionDrone?.uavId || '待分配' }}</strong></div><div><span>电量</span><strong>{{ battery === null ? '--' : `${battery}%` }}</strong></div><div><span>会面点</span><strong>{{ current?.orderId ? (orders.rendezvous?.locked ? '已确定' : '正在计算') : '暂无订单' }}</strong></div><div><span>订单状态</span><strong>{{ current?.orderStatus || '待开始' }}</strong></div></div><section v-if="candidate" class="candidate-panel" aria-label="候选评估"><div class="candidate-head"><div><span>备选方案</span><strong>共 {{ candidate.count }} 个备选 · 排除 {{ candidate.rejectedCount }} 个</strong></div><b v-if="candidate.chosenNestId">中选 {{ candidate.chosenNestId }}</b></div><div v-if="candidate.rows.length" class="candidate-cards"><article v-for="row in candidate.rows" :key="row.key" :class="{ selected: row.selected || row.nestId === candidate.chosenNestId, rejected: !row.feasible || row.reasons.length }"><span>{{ row.nestId || row.droneId || row.model || '候选' }}</span><strong>{{ row.distKm === null ? '距离待回传' : `${row.distKm.toFixed(2)} km` }}</strong><small>{{ row.selected || row.nestId === candidate.chosenNestId ? '已中选' : (!row.feasible || row.reasons.length ? '已落选' : '可用') }}</small><ul v-if="row.reasons.length" class="candidate-row-reasons"><li v-for="reason in row.reasons" :key="reason">{{ reason }}</li></ul></article></div><p v-if="candidate.occupancy" class="candidate-result">机位：{{ candidate.occupancy.slotId ? `${candidate.occupancy.objectId || '对象未记录'} · ${candidate.occupancy.status || '状态未知'}` : '占位未登记' }}</p><p v-if="candidate.stagger" class="candidate-result">{{ candidate.stagger.applied ? `错峰 +${candidate.stagger.shiftMs / 1000} 秒 · ${candidate.stagger.reason || ''}` : '没有排队等待' }}</p><ul v-if="!candidate.rows.length && candidate.reasons.length" class="candidate-reasons"><li v-for="reason in candidate.reasons" :key="reason">{{ reason }}</li></ul><p v-if="!candidate.rows.length && !candidate.reasons.length && !candidate.occupancy && !candidate.stagger" class="candidate-empty">本轮没有候选被淘汰。</p></section><div v-if="canConfirmRecycle || canConfirmDelivery" class="demo-confirm-actions"><button v-if="canConfirmRecycle" class="button-primary" :disabled="busy" @click="confirmRecycle">确认落巢回收</button><button v-if="canConfirmDelivery" class="button-primary" :disabled="busy" @click="confirmDelivery">确认投放回执</button></div><button class="button-secondary full-button" @click="go('console')">打开调度台</button></section><details class="dashboard-events" open><summary>最近发生的事与告警，最新的在最上面</summary><EventStream /></details></aside>
        </section>
        <section class="demo-panel"><div class="demo-copy"><p class="section-kicker">讲解式仿真</p><h2>从下单观察风速突变后的处置</h2><p>依次查看预计会面、无人机去程、气象超限、原机返航、风况回落和重新派单。每一步都由你手动推进。</p></div><div class="demo-actions"><button class="button-primary" @click="startGuide">开始分步演示 →</button><button class="button-quiet" @click="enterReplay">查看已有任务回放</button></div><details v-if="source === 'live'" class="demo-tools"><summary>演示工具：注入风速与无人机异常</summary><div class="inject-panels" aria-label="仿真注入"><WeatherInjectPanel :source="source"/><UavInjectPanel :source="source" :active-uav-id="currentTask?.droneId || ''"/></div></details>
        <details class="demo-tools"><summary>录屏、导出与导入回放</summary><div><span>演示流程：下单 → 预计会面 → 去程 → 风速突变 → 原机返航 → 风况回落 → 重新派单与交接</span><button class="text-button" @click="screenRecording ? stopScreenRecording() : startScreenRecording()">{{ screenRecording ? '停止录屏' : '录制当前页面' }}</button><button class="text-button" @click="exportRecording">导出回放</button><label class="text-button">导入回放<input type="file" accept="application/json" @change="importRecording"></label></div></details></section>
      </template>

<template v-else-if="view === 'console'"><ConsoleWorkspace :detail-id="consoleOrderId" :tasks="taskList" :source="source" :busy="busy" :feedback="feedback" @open="openOrderDetail" @back="backToQueue" @refresh="refreshOrders" @action="onConsoleAction" /></template>

      <template v-else-if="view === 'crew'"><section class="crew-workspace"><section class="form-panel"><div class="panel-head"><div><h2>新建物资订单</h2><p>提交后会进入后端订单队列，实时服务会返回受理结果。</p></div><span class="form-status">{{ source === 'live' ? '实时提交' : '本地演示' }}</span></div><CrewOrderForm :source="source" @created="onOrderCreated" /></section><section class="crew-status-panel"><div class="panel-head"><div><h2>订单反馈</h2><p>当前选择的订单会在这里持续更新。</p></div><button class="text-button" @click="go('dashboard')">返回总览</button></div><OrderTimeline/><details class="crew-map-details" @toggle="crewMapOpen = $event.target.open"><summary>展开任务地图</summary><MapView v-if="crewMapOpen" :drone-id="currentTask?.droneId || ''" :order-id="current?.orderId || ''" :connection-error="source === 'live' ? connection.error : ''"/></details></section></section></template>

      <template v-else-if="view === 'replay'"><section class="replay-workspace"><section class="map-panel replay-map-panel"><div class="panel-head"><div><h2>历史任务地图</h2><p>{{ selectedTask || '请选择一条任务' }}</p></div><span class="count-badge">{{ recordCount }} 条记录</span></div><MapView/></section><aside class="replay-panel"><div class="panel-head compact"><div><h2>回放控制</h2><p>拖动时间线查看每一帧</p></div><button class="button-quiet" @click="exitReplay">退出回放</button></div><label class="field-label">任务<select v-model="selectedTask" aria-label="回放任务" @change="loadReplay"><option value="">请选择任务</option><option v-for="task in taskList" :key="task.orderId" :value="task.orderId">{{ task.orderId }} · {{ task.droneId || '未派机' }}</option></select></label><div v-if="replaySteps.length" class="replay-current"><span>当前帧</span><strong>{{ replaySteps[replayIndex]?.title }}</strong><small>{{ replayIndex + 1 }} / {{ replaySteps.length }}</small></div><input class="replay-range" type="range" min="0" :max="Math.max(0, replaySteps.length - 1)" :disabled="!replaySteps.length" step="1" :value="replayIndex" @input="showReplay($event.target.value)" aria-label="回放进度"><div class="replay-ticks"><span>开始</span><span>最新记录</span></div><ol class="replay-list"><li v-for="(step, index) in replaySteps" :key="`${step.title}-${index}`" :class="{ active: index === replayIndex }" @click="showReplay(index)"><span>{{ index + 1 }}</span><strong>{{ step.title }}</strong></li></ol><details class="replay-history"><summary>展开订单进展</summary><OrderTimeline/></details></aside></section></template>
      <template v-else-if="view === 'guide'"><section v-if="showDemoProgress" class="demo-script-panel"><div class="section-heading"><span>一键演示脚本进度</span><span class="eyebrow">DEMO SCRIPT</span></div><p class="demo-script-note">这一段只说明一键演示的脚本走到哪一步，与订单状态无关。订单自身的进展在运行总览里那张订单进展表上。</p><div class="mission-progress"><div class="progress-head"><span>演示步骤</span><strong>{{ stageIndex }} / {{ DEMO_STAGES.length }}</strong></div><div class="progress-track"><span :style="{ width: progressPercent }"></span></div></div><ol class="stage-list"><li v-for="(stage, index) in DEMO_STAGES" :key="stage" :class="stageClass(index)"><span class="stage-number">{{ index + 1 }}</span><span>{{ stage }}</span><b v-if="demoState.completed || index < stageIndex - 1">完成</b><b v-else-if="index === stageIndex - 1">进行中</b></li></ol></section><GuidedDemo :source="source" :guide="guide" @created="onGuideOrderCreated" @next="showGuideStage(guide.index + 1)" @previous="previousGuideStage" @retry="loadGuidedHistory" @finish="finishGuide" /></template>
    </main>
    <footer><span>WaveNest · 沿江智能配送监控平台</span><span>{{ source === 'live' ? '数据来自后端实时服务' : '当前为离线演示数据' }}</span></footer>
  </div>
</template>
