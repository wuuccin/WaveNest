<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import MapView from './components/MapView.vue'
import StatsPanel from './components/StatsPanel.vue'
import OrderTimeline from './components/OrderTimeline.vue'
import EventStream from './components/EventStream.vue'
import CrewOrderForm from './components/CrewOrderForm.vue'
import { createRealtime } from './realtime'
import { createDemoRunner, DEMO_STAGES } from './realtime/demo'
import { normalizeFrame } from './api/adapter'
import { SCENES } from './realtime/mock'
import { api } from './api/client'
import { useOrderStore, useShipStore, useDroneStore, useAlertStore, useEventStore } from './stores'

const route=ref(location.hash.slice(1)||'dashboard'), sceneIndex=ref(0), replayIndex=ref(0), busy=ref(false), feedback=ref('')
const source=ref(import.meta.env.VITE_DATA_SOURCE === 'mock' ? 'mock' : 'live')
const replaySteps=ref([]), taskList=ref([]), selectedTask=ref(''), recordCount=ref(0)
const screenRecording=ref(false)
let screenRecorder, screenStream
const realtime=createRealtime(), orders=useOrderStore(), ship=useShipStore(), drone=useDroneStore(), alerts=useAlertStore(), events=useEventStore()
const demo=createDemoRunner({api, subscribe:realtime.subscribe, connected:()=>realtime.status.connection==='WebSocket 已连接'})
const connection=realtime.status
const view= computed(()=>['dashboard','console','replay','crew'].includes(route.value)?route.value:'dashboard')
const current=computed(()=>orders.orders[0])
const sceneTitle=computed(()=>source.value==='live' ? (DEMO_STAGES[Math.max(0,demo.state.step-1)] || '等待演示') : SCENES[Math.min(sceneIndex.value,SCENES.length-1)].title)
const phase=computed(()=>drone.current?.flightPhase||'待命')
const clock=ref(new Date().toLocaleTimeString('zh-CN',{hour12:false}));let clockTimer
const onHash=()=>route.value=location.hash.slice(1)||'dashboard'
onMounted(()=>{window.addEventListener('hashchange',onHash);clockTimer=setInterval(()=>clock.value=new Date().toLocaleTimeString('zh-CN',{hour12:false}),1000);source.value==='live'?realtime.connect():realtime.resetMock()})
onUnmounted(()=>{window.removeEventListener('hashchange',onHash);clearInterval(clockTimer);demo.cancel();realtime.close();stopScreenRecording()})
async function startScreenRecording(){
  if(screenRecording.value)return
  if(!navigator.mediaDevices?.getDisplayMedia || !window.MediaRecorder){feedback.value='当前浏览器不支持屏幕录像';return}
  try{
    const stream=await navigator.mediaDevices.getDisplayMedia({video:true,audio:true})
    const mimeType=['video/webm;codecs=vp9,opus','video/webm;codecs=vp8,opus','video/webm'].find(type=>MediaRecorder.isTypeSupported(type))
    const recorder=new MediaRecorder(stream,mimeType?{mimeType}:undefined)
    const chunks=[]
    recorder.ondataavailable=event=>{if(event.data.size)chunks.push(event.data)}
    recorder.onstop=()=>{
      screenRecording.value=false
      stream.getTracks().forEach(track=>track.stop())
      if(screenRecorder===recorder){screenRecorder=null;screenStream=null}
      if(!chunks.length){feedback.value='屏幕录像没有生成视频数据';return}
      const url=URL.createObjectURL(new Blob(chunks,{type:recorder.mimeType||'video/webm'}))
      const link=document.createElement('a');link.href=url;link.download='wavenest-screen-'+Date.now()+'.webm';link.click()
      setTimeout(()=>URL.revokeObjectURL(url),60000)
      feedback.value='屏幕录像已下载'
    }
    stream.getVideoTracks()[0]?.addEventListener('ended',()=>stopScreenRecording(),{once:true})
    screenStream=stream;screenRecorder=recorder;recorder.start(1000);screenRecording.value=true
    feedback.value='正在录屏；请在浏览器共享提示中选择大屏或标签页'
  }catch(error){
    screenStream?.getTracks().forEach(track=>track.stop());screenStream=null;screenRecorder=null
    feedback.value=error.name==='NotAllowedError'?'未选择录制画面':'录屏启动失败：'+error.message
  }
}
function stopScreenRecording(){
  if(screenRecorder?.state==='recording')screenRecorder.stop()
  else screenStream?.getTracks().forEach(track=>track.stop())
}
function selectSource(next){
  demo.cancel();source.value=next;localStorage.setItem('wavenest-source',next);feedback.value='';sceneIndex.value=0
  orders.reset();alerts.clear();events.clear();ship.apply(null);drone.apply(null)
  next==='live'?realtime.connect():realtime.resetMock()
}
function onOrderCreated(){if(source.value==='live')realtime.refresh().catch(e=>feedback.value=`订单刷新失败：${e.message}`)}
function step(){sceneIndex.value=realtime.nextMock()}
function run(){if(source.value==='live')demo.run();else realtime.autoMock(i=>sceneIndex.value=i)}
async function demoAction(action){
  busy.value=true;feedback.value=''
  try{const result=action==='reset'?await api.resetShip():await api.turnShip(45);
    feedback.value=action==='reset'?'仿真船已回到起点':`已注入船舶变向：新航向 ${result.newCog}°`}
  catch(e){feedback.value=`仿真船操作失败：${e.message}`}
  finally{busy.value=false}
}
const actions=['调整会面点','撤回未起飞订单','取消订单','冻结派单','紧急叫停','人工接管']
async function intervene(action){
  if(busy.value)return
  busy.value=true;feedback.value='提交中'
  try{const result=await api.intervene({action,orderId:current.value?.orderId});
    if(result.confirmed){const fresh=await api.orders();orders.snapshot(fresh);feedback.value=`${action}已由后端确认`}
    else feedback.value=result.message||'未确认，请查询后端状态'
  }catch(e){feedback.value=`未确认：${e.message}`;try{orders.snapshot(await api.orders())}catch{}}
  finally{busy.value=false}
}
const replayFrames=computed(()=>replaySteps.value.slice(0,replayIndex.value+1).flatMap(s=>s.frames))
function showReplay(index){
  replayIndex.value=Number(index); orders.reset();alerts.clear();events.clear();ship.apply(null);drone.apply(null)
  for(const [type,data] of replayFrames.value)realtime.emit(type,data,true)
  orders.replaying=true
}
function exitReplay(){
  orders.replaying=false
  if(source.value==='live')realtime.connect();else{const n=sceneIndex.value;realtime.resetMock();sceneIndex.value=0;for(let i=0;i<n;i++)step()}
  location.hash='#dashboard'
}
async function loadReplay(){
  feedback.value=''
  try {
    const id=selectedTask.value
    if(!id)throw new Error('暂无可回放任务，请先完成一次数据录制')
    const history=await api.replay(id)
    if(!Array.isArray(history.frames)||!history.frames.length)throw new Error('该任务暂无回放帧')
    replaySteps.value=history.frames.map(frame=>{const d=normalizeFrame(frame.type,frame.data);return {title:frame.type==='order_state'?d.orderStatus:frame.type==='alarm'?'告警事件':frame.type==='rendezvous'?'会面点更新':frame.type==='drone_state'?'无人机状态':'船舶轨迹',frames:[[frame.type,d]]}})
    const records=await api.records(id);recordCount.value=records.records?.length || 0
    showReplay(0)
  }catch(e){feedback.value=`回放读取失败：${e.message}`;replaySteps.value=[]}
}
async function enterReplay(){
  if(demo.state.running){feedback.value='请先停止演示再进入回放';return}
  if(source.value==='mock'){replaySteps.value=SCENES;showReplay(0)}
  else {
    try{const result=await api.tasks();taskList.value=result.tasks||[];selectedTask.value=taskList.value.find(t=>t.orderId===current.value?.orderId)?.taskId || taskList.value[0]?.taskId || ''}
    catch(e){feedback.value=`任务列表读取失败：${e.message}`}
    await loadReplay()
  }
  location.hash='#replay'
}
function exportRecording(){
  const offline=source.value==='mock'
  const frames=offline?SCENES.flatMap(scene=>scene.frames.map(([type,data])=>({type,data}))):realtime.recording
  if(!frames.length){feedback.value='暂无实时录制数据';return}
  const packageData={format:'wavenest.frames.v1',source:offline?'offline-demo':'live',frames}
  if(offline)packageData.steps=SCENES.map(scene=>({title:scene.title,frames:scene.frames.map(([type,data])=>({type,data}))}))
  const url=URL.createObjectURL(new Blob([JSON.stringify(packageData,null,2)],{type:'application/json'}))
  const a=document.createElement('a');a.href=url;a.download=offline?'wavenest-offline-demo.json':'wavenest-'+Date.now()+'.json';a.click();URL.revokeObjectURL(url);feedback.value=offline?'已导出离线演示回放包':'已导出实时录制回放包'
}
async function importRecording(event){
  try{const content=JSON.parse(await event.target.files[0].text());if(!Array.isArray(content.frames)||!content.frames.length)throw new Error('文件缺少历史帧');
    const allowed=['order_state','rendezvous','drone_state','ship_traj','alarm'];
    const rawSteps=Array.isArray(content.steps)&&content.steps.length?content.steps:content.frames.map(frame=>({title:frame.type,frames:[frame]}))
    const steps=rawSteps.map(step=>{
      if(!Array.isArray(step.frames)||!step.frames.length)throw new Error('历史片段缺少帧')
      return {title:typeof step.title==='string'?step.title:'历史片段',frames:step.frames.map(frame=>{
        const item=Array.isArray(frame)?{type:frame[0],data:frame[1]}:frame
        if(!allowed.includes(item.type))throw new Error('未知历史帧')
        return [item.type,normalizeFrame(item.type,item.data)]
      })}
    })
    demo.cancel();replaySteps.value=steps;location.hash='#replay';showReplay(0);feedback.value='已载入录制文件'
  }catch(e){feedback.value=`导入失败：${e.message}`}
}
</script>
<template><div class="app-shell">
  <header class="topbar"><div class="brand"><span class="brand-mark">W</span><div><strong>WaveNest</strong><small>沿江智能配送监控平台</small></div></div><nav class="main-nav" aria-label="主导航"><a href="#dashboard" :class="{selected:view==='dashboard'}">态势大屏</a><a href="#console" :class="{selected:view==='console'}">调度台</a><a href="#crew" :class="{selected:view==='crew'}">船员端</a><a href="#replay" :class="{selected:view==='replay'}" @click.prevent="enterReplay">任务回放</a></nav><div class="header-meta"><span class="time">{{ clock }}</span><span class="connection-dot" :class="{offline:source==='mock'}"></span><span>{{ connection.connection }}</span></div></header>
  <main>
    <div class="page-bar"><div><div class="eyebrow">MISSION CONTROL / {{ view.toUpperCase() }}</div><h1>{{ {dashboard:'江面实时态势',console:'任务调度台',crew:'船员订单',replay:'任务时间轴'}[view] }}</h1></div><div class="source-switch"><span>数据源</span><button :class="{active:source==='mock'}" @click="selectSource('mock')">离线演示</button><button :class="{active:source==='live'}" @click="selectSource('live')">实时服务</button></div></div>
    <div v-if="connection.error||demo.state.error||feedback" class="notice" role="status">{{ demo.state.error||feedback||connection.error }}</div>
    <template v-if="view==='dashboard'"><div class="dashboard-grid"><div class="map-column"><div class="map-toolbar"><span class="section-heading">运行地图 <span class="eyebrow">LIVE MAP</span></span><div class="legend"><span><i class="ship-key"></i>船舶预测</span><span><i class="meeting-key"></i>会面点</span><span><i class="drone-key"></i>无人机</span></div></div><MapView/><div class="mission-strip"><div><small>当前任务</small><strong>{{ current?.orderId||'--' }}</strong></div><div><small>船舶</small><strong>{{ ship.current?.shipName||'--' }}</strong></div><div><small>无人机阶段</small><strong>{{ phase }}</strong></div><div><small>会面点</small><strong>{{ Number.isFinite(orders.rendezvous?.lon) && Number.isFinite(orders.rendezvous?.lat) ? `${orders.rendezvous.lon.toFixed(3)}°E / ${orders.rendezvous.lat.toFixed(3)}°N` : '待计算' }}</strong></div></div></div><aside class="side-column"><StatsPanel/><OrderTimeline/><EventStream/></aside></div>
      <div class="demo-toolbar"><div><span class="eyebrow">SCENARIO CONTROL</span><strong>{{ sceneTitle }}</strong><small>第 {{ source==='live'?demo.state.step:sceneIndex }} / 6 段 · {{ source==='live'?'后端实时数据':'离线演示数据' }}</small></div><div class="demo-actions"><button v-if="source==='mock'" class="button-quiet" @click="realtime.resetMock();sceneIndex=0">重置</button><button v-else class="button-quiet" :disabled="busy||demo.state.running" @click="demoAction('reset')">重置仿真船</button><button v-if="source==='mock'" class="button-secondary" @click="step" :disabled="sceneIndex>=6">下一段</button><button v-else class="button-secondary" :disabled="busy||demo.state.running" @click="demoAction('next')">船舶变向</button><button class="button-primary" :disabled="demo.state.running" @click="run">一键演示</button><button v-if="demo.state.running" class="button-quiet" @click="demo.cancel">停止演示</button><button class="button-quiet" @click="screenRecording?stopScreenRecording():startScreenRecording()">{{ screenRecording?'结束录屏':'开始录屏' }}</button><button class="button-quiet" @click="exportRecording">导出回放数据</button><label class="button-quiet">导入录制<input type="file" accept="application/json" style="max-width:160px" @change="importRecording"/></label><button class="button-quiet" @click="enterReplay">查看回放</button></div></div>
    </template>
    <template v-else-if="view==='console'"><div class="console-grid"><section class="console-main"><div class="section-heading"><span>任务队列</span><span class="eyebrow">DISPATCH QUEUE</span></div><div class="table-wrap"><table><thead><tr><th>订单号</th><th>状态</th><th>等待原因</th><th>预计唤醒</th><th>更新时间</th></tr></thead><tbody><tr v-for="item in orders.orders" :key="item.orderId"><td>{{ item.orderId }}</td><td><span class="table-state">{{ item.orderStatus }}</span></td><td>{{ item.orderStatus==='已受理待唤醒' ? (item.waitReason===null?'等待原因暂不可用':({WAIT_SHIP:'等船',WAIT_CARGO:'等货',GATE_OR_DATA:'闸门熔断或数据不足'}[item.waitReason]||'字段异常')):'--' }}</td><td>{{ item.wakeEtaTs ? new Date(item.wakeEtaTs).toLocaleString() : '--' }}</td><td>{{ new Date(item.eventTs).toLocaleTimeString('zh-CN',{hour12:false}) }}</td></tr></tbody></table></div><div v-if="!orders.orders.length" class="empty-state">暂无任务</div><div class="section-heading subheading">人工干预 <span class="eyebrow">MANUAL CONTROL</span></div><div class="gate-line"><span>派单闸门</span><strong :class="{alarm:orders.gate.gateStatus==='熔断中'}">{{ orders.gate.gateStatus }}</strong><span>{{ orders.gate.gateSources?.join(' / ')||'无冻结来源' }}</span></div><div class="action-grid"><button v-for="action in actions" :key="action" :disabled="busy||source==='mock'" @click="intervene(action)">{{ action }}</button><button :disabled="busy||source==='mock'" @click="intervene('解除冻结')">解除人工冻结</button></div><p class="action-help">人工操作以服务端确认结果为准。离线模式只演示态势；具体可用操作以服务端实现为准。</p><p v-if="feedback" class="action-feedback" role="status">{{ feedback }}</p></section><aside class="console-side"><OrderTimeline/><EventStream/></aside></div></template>
    <template v-else-if="view==='crew'"><div class="crew-layout"><div class="crew-phone"><div class="crew-title"><small>我的配送</small><strong>江运 08</strong></div><div class="crew-order"><span>当前订单 <b>{{ current?.orderId||'--' }}</b></span><strong>{{ current?.orderStatus||'暂无订单' }}</strong><p v-if="current?.orderStatus==='已受理待唤醒'">{{ current.waitReason===null?'等待原因暂不可用':'请等待调度更新' }}</p><p v-if="current?.orderStatus==='失败'">已迫降，待人工回收</p><p v-if="current?.orderStatus==='异常终止'">任务异常终止，货物灭失</p></div><OrderTimeline/><CrewOrderForm :source="source" @created="onOrderCreated"/></div><div class="crew-map"><MapView/></div></div></template>
    <template v-else><div class="replay-layout"><div class="replay-map"><MapView/></div><aside><div class="section-heading">回放控制 <span class="eyebrow">PLAYBACK</span></div><select v-if="source==='live' && taskList.length" v-model="selectedTask" @change="loadReplay" aria-label="回放任务"><option v-for="task in taskList" :key="task.taskId" :value="task.taskId">{{ task.taskId }} · {{ task.droneId }}</option></select><p v-if="source==='live'">实际飞行记录 {{ recordCount }} 条</p><strong class="replay-title">{{ replaySteps[replayIndex]?.title }}</strong><input type="range" min="0" :max="Math.max(0,replaySteps.length-1)" :disabled="!replaySteps.length" step="1" :value="replayIndex" @input="showReplay($event.target.value)" aria-label="回放进度"/><div class="replay-ticks"><span>开始</span><span>拖动查看历史</span><span>最新记录</span></div><p>轨迹、会面点、告警及订单状态按同一演示片段回放；实时更新暂停写入。</p><button class="button-primary" @click="exitReplay">退出回放</button><OrderTimeline/></aside></div></template>
  </main><footer>WaveNest · 阶段性演示工程 <span>本地航道示意 / {{ source==='live'?'后端实时数据':'离线演示数据' }}</span></footer>
</div></template>
