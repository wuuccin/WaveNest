<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { useOrderStore } from '../stores'
import { chartOption } from '../layers/statsPanel'
const order=useOrderStore(), target=ref(null)
let chart
const completed=computed(()=>order.orders.filter(o=>o.orderStatus==='已完成').length)
const active=computed(()=>order.orders.filter(o=>!['已完成','失败','已拒绝','异常终止'].includes(o.orderStatus)).length)
onMounted(()=>{chart=echarts.init(target.value);chart.setOption(chartOption(completed.value,active.value));window.addEventListener('resize',resize)})
onUnmounted(()=>{window.removeEventListener('resize',resize);chart?.dispose()})
function resize(){chart?.resize()}
watch([completed,active],()=>chart?.setOption(chartOption(completed.value,active.value)))
</script>
<template><section class="stats-panel"><div class="section-heading"><span>运行概览</span><span class="eyebrow">OPERATIONS</span></div><div class="kpis"><div><strong>{{ active }}</strong><span>进行中</span></div><div><strong>{{ completed }}</strong><span>已完成</span></div><div><strong>98.2<small>%</small></strong><span>链路可用率 · 示意</span></div></div><div class="chart-heading">配送能耗对比 <span>仿真示意值</span></div><div ref="target" class="stats-chart"></div></section></template>
