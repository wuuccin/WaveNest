<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { api } from '../api/client'
import { useOrderStore } from '../stores'

const props = defineProps({ source: { type: String, required: true } })
const orders = useOrderStore()
const target = ref(null)
const statistics = ref(null)
const error = ref('')
let chart
let timer

const fallback = computed(() => ({
  totalOrders: orders.orders.length,
  inFlightCount: orders.orders.filter(order => ['去程中', '末段交接中', '返航中'].includes(order.orderStatus)).length,
  finishedCount: orders.orders.filter(order => order.orderStatus === '已完成').length,
  terminalCount: orders.orders.filter(order => ['已拒绝', '失败', '异常终止'].includes(order.orderStatus)).length,
  byStatus: []
}))
const display = computed(() => props.source === 'live' ? statistics.value : fallback.value)

function renderChart() {
  if (!chart) return
  const rows = display.value?.byStatus || []
  chart.setOption({
    grid: { top: 12, right: 12, bottom: 30, left: 35 },
    xAxis: { type: 'category', data: rows.map(row => row.label), axisLabel: { interval: 0, rotate: 35, fontSize: 9 } },
    yAxis: { type: 'value', minInterval: 1 },
    tooltip: { trigger: 'axis' },
    series: [{ type: 'bar', barWidth: 18, data: rows.map(row => row.count), itemStyle: { color: '#148b78' } }]
  })
}
async function refresh() {
  if (props.source !== 'live') { statistics.value = null; error.value = ''; return }
  try { statistics.value = await api.stats(); error.value = '' }
  catch (cause) { statistics.value = null; error.value = cause.message }
}
function resize() { chart?.resize() }
onMounted(() => {
  chart = echarts.init(target.value)
  renderChart()
  window.addEventListener('resize', resize)
  void refresh()
  timer = setInterval(refresh, 5000)
})
onUnmounted(() => { clearInterval(timer); window.removeEventListener('resize', resize); chart?.dispose() })
watch(display, renderChart)
watch(() => props.source, refresh)
</script>

<template>
  <section class="stats-panel">
    <div class="section-heading"><span>后端运行统计</span><span class="eyebrow">MONITOR</span></div>
    <p v-if="error" class="action-feedback" role="status">统计接口读取失败：{{ error }}</p>
    <p v-else-if="source === 'live' && !display" class="empty-state">等待后端统计数据</p>
    <template v-if="display">
      <div class="kpis"><div><strong>{{ display.totalOrders }}</strong><span>订单总数</span></div><div><strong>{{ display.inFlightCount }}</strong><span>在飞任务</span></div><div><strong>{{ display.finishedCount }}</strong><span>已完成</span></div><div><strong>{{ display.terminalCount }}</strong><span>终止态</span></div></div>
      <div class="chart-heading">订单状态分布 <span>{{ source === 'live' ? '后端统计' : '离线样例' }}</span></div>
    </template>
    <div ref="target" class="stats-chart"></div>
  </section>
</template>
