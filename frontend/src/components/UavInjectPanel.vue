<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api/client'

const props = defineProps({ source: { type: String, required: true }, activeUavId: { type: String, default: '' } })
const drones = ref([])
const selectedUavId = ref('')
const deviationM = ref(60)
const bearingDeg = ref(90)
const percent = ref(15)
const busy = ref(false)
const feedback = ref('')
const failed = ref(false)
const selected = computed(() => drones.value.find(item => item.uavId === selectedUavId.value))
const locked = computed(() => props.source !== 'live' || busy.value)
let timer

async function refresh() {
  if (props.source !== 'live') return
  try {
    const result = await api.uavInjectionStatus()
    drones.value = Array.isArray(result.drones) ? result.drones : []
    if (!drones.value.some(item => item.uavId === selectedUavId.value)) selectedUavId.value = props.activeUavId || drones.value[0]?.uavId || ''
  } catch (error) {
    failed.value = true
    feedback.value = `读取无人机注入状态失败：${error.message}`
  }
}

async function perform(action) {
  if (locked.value || !selectedUavId.value) return
  busy.value = true
  failed.value = false
  feedback.value = ''
  try {
    const result = await action()
    await refresh()
    return result
  } catch (error) {
    failed.value = true
    feedback.value = error.message
    return null
  } finally { busy.value = false }
}

async function injectOfftrack() {
  const result = await perform(() => api.injectUavOfftrack({ uavId: selectedUavId.value, deviationM: deviationM.value, bearingDeg: bearingDeg.value }))
  if (result) feedback.value = `${result.uavId} 已开始偏航注入：目标 ${result.targetDeviationM} m，当前 ${result.currentDeviationM} m。位置逐拍变化，请等待状态追平。`
}
async function injectBattery() {
  const result = await perform(() => api.injectUavBattery({ uavId: selectedUavId.value, percent: percent.value }))
  if (result) feedback.value = `${result.uavId} 已注入电量 ${result.batteryPercent}%。告警结果请查看事件流。`
}
async function reset() {
  const result = await perform(() => api.clearUavInjections(selectedUavId.value))
  if (result) feedback.value = `${selectedUavId.value} 注入已复位；偏航位移会逐拍退回。`
}

watch(() => props.activeUavId, uavId => { if (uavId && drones.value.some(item => item.uavId === uavId)) selectedUavId.value = uavId })
watch(() => props.source, source => {
  clearInterval(timer)
  if (source === 'live') { refresh(); timer = setInterval(refresh, 1000) }
  else { drones.value = []; selectedUavId.value = ''; feedback.value = '' }
}, { immediate: true })
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <section class="uav-inject-panel" aria-label="无人机异常注入">
    <div class="panel-head compact"><div><h2>无人机异常注入</h2><p>选择在飞无人机注入偏航，或注入低电量；后端按遥测帧判定告警。</p></div></div>
    <p v-if="source !== 'live'" class="weather-note">离线演示不连接后端，注入不可用。</p>
    <div class="uav-inject-fields">
      <label>无人机<select v-model="selectedUavId" :disabled="locked || !drones.length" aria-label="注入无人机"><option value="" disabled>选择无人机</option><option v-for="item in drones" :key="item.uavId" :value="item.uavId">{{ item.uavId }}</option></select></label>
      <label>偏航位移 m<input v-model.number="deviationM" type="number" min="0" step="1" :disabled="locked" aria-label="偏航位移"></label>
      <label>偏航方向 °<input v-model.number="bearingDeg" type="number" min="0" max="360" step="1" :disabled="locked" aria-label="偏航方向"></label>
      <label>目标电量 %<input v-model.number="percent" type="number" min="0" max="100" step="1" :disabled="locked" aria-label="目标电量"></label>
    </div>
    <div class="weather-actions"><button class="button-primary" :disabled="locked || !selectedUavId" @click="injectOfftrack">注入偏航</button><button class="button-primary" :disabled="locked || !selectedUavId" @click="injectBattery">注入低电量</button><button class="button-quiet" :disabled="locked || !selectedUavId" @click="reset">复位所选无人机</button></div>
    <p v-if="selected" class="weather-note">{{ selected.uavId }}：{{ selected.offtrackActive ? (selected.currentDeviationM >= selected.targetDeviationM ? '偏航已到位' : '偏航注入中') : '无偏航注入' }}，当前 {{ selected.currentDeviationM }} / 目标 {{ selected.targetDeviationM }} m，电量 {{ selected.batteryPercent }}%。</p>
    <p v-if="feedback" class="weather-feedback" :class="{ danger: failed }" role="status">{{ feedback }}</p>
    <p class="weather-note">偏航告警需要在飞订单和航段。南北向航段可选 90° 或 270°；低电量无需在飞前提。</p>
  </section>
</template>
