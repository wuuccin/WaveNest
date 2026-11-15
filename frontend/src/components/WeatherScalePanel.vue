<script setup>
/**
 * 风况标尺（11-14 任务 2-7 · 杨添爻）。
 *
 * <p>一条横向标尺上同时给出三条线：当前风速（指针）、机型抗风上限、全域熔断阈值，
 * 另画一条预警线作参照。三条线的数值全部来自 props 与 store，模板里不出现风速字面量：
 * <ul>
 *   <li>当前风速 ← {@code useWeatherStore().windSpeedMs}（最不利网格点）；</li>
 *   <li>机型抗风上限 ← {@code /api/drones} 的 {@code windLimitMs}，本机未登记时回落全机队最保守；</li>
 *   <li>熔断与预警 ← {@code resolveThresholds(props.thresholds)}，缺省取 {@code 002_sys_param} 的两档值。</li>
 * </ul>
 *
 * <p>挂载由 2-11（彭紫情）落地，本组件不改 {@code App.vue}，也不自己取数（除 store 与 props）。
 */
import { computed } from 'vue'
import { useFleetStore, useWeatherStore } from '../stores'
import { SCALE_MAX_MS, buildScaleModel, pickDroneWindLimit, resolveThresholds } from '../api/weather'

const props = defineProps({
  /** 当前任务机号；为空时按全机队最保守机型画上限线 */
  droneId: { type: String, default: '' },
  /** 覆盖全域阈值 {warningMs, criticalMs, source}；不传则用 sys_param 默认值 */
  thresholds: { type: Object, default: null },
  /** 满量程（m/s） */
  maxMs: { type: Number, default: SCALE_MAX_MS },
  /** 无气象帧时的提示语，便于挂载方按场景改写 */
  emptyHint: { type: String, default: '等待气象帧' }
})

const fleet = useFleetStore()
const weather = useWeatherStore()

const limit = computed(() => pickDroneWindLimit(fleet.items, props.droneId))
const thresholds = computed(() => resolveThresholds(props.thresholds || {}))
const model = computed(() => buildScaleModel({
  windMs: weather.windSpeedMs,
  gustMs: weather.gustMs,
  windLimitMs: limit.value?.valueMs ?? null,
  thresholds: thresholds.value,
  maxMs: props.maxMs
}))

const levelLabel = computed(() => ({
  unknown: props.emptyHint,
  normal: '风况正常',
  warning: '已达预警风速',
  critical: '已达全域熔断'
}[model.value.level] ?? props.emptyHint))

const limitLabel = computed(() => limit.value
  ? `机型上限 ${limit.value.valueMs} m/s（${limit.value.scope}${limit.value.model ? ` · ${limit.value.model}` : ''}）`
  : '机型上限未登记')

const percent = value => (Number.isFinite(value) ? `${value}%` : '0%')
const fixed = value => (Number.isFinite(value) ? value.toFixed(1) : '--')
</script>

<template>
  <section class="weather-scale-panel" aria-label="风况标尺">
    <div class="panel-head compact">
      <div>
        <h2>风况标尺</h2>
        <p>{{ weather.hasData ? `最不利点 ${weather.worstPoint ? `${weather.worstPoint.lon.toFixed(4)}, ${weather.worstPoint.lat.toFixed(4)}` : '--'} · ${weather.pointCount} 个网格点` : emptyHint }}</p>
      </div>
      <span class="weather-state" :class="model.level">{{ levelLabel }}</span>
    </div>

    <div class="weather-scale">
      <div class="weather-track" role="img" :aria-label="`当前风速 ${fixed(model.wind.valueMs)} 米每秒，机型上限 ${fixed(model.droneLimit.valueMs)}，熔断 ${model.critical.valueMs}`">
        <span class="weather-fill" :class="model.level" :style="{ width: percent(model.wind.percent) }"></span>
        <i class="weather-line warning" :style="{ left: percent(model.warning.percent) }"><b>预警 {{ model.warning.valueMs }}</b></i>
        <i v-if="model.droneLimit.valueMs !== null" class="weather-line limit" :style="{ left: percent(model.droneLimit.percent) }"><b>上限 {{ model.droneLimit.valueMs }}</b></i>
        <i class="weather-line critical" :style="{ left: percent(model.critical.percent) }"><b>熔断 {{ model.critical.valueMs }}</b></i>
        <i v-if="model.hasWind" class="weather-pointer" :class="{ alert: model.overWarning, danger: model.overCritical }" :style="{ left: percent(model.wind.percent) }">
          <b>{{ fixed(model.wind.valueMs) }}</b>
        </i>
      </div>
      <div class="weather-axis"><span>0</span><span>{{ Math.round(maxMs / 2) }}</span><span>{{ maxMs }} m/s</span></div>
    </div>

    <div class="weather-facts">
      <div><span>当前风速</span><strong :class="{ alert: model.overWarning, danger: model.overCritical }">{{ fixed(model.wind.valueMs) }} m/s</strong></div>
      <div><span>阵风</span><strong>{{ fixed(model.gust.valueMs) }} m/s</strong></div>
      <div><span>风向</span><strong>{{ Number.isFinite(weather.windDirDeg) ? `${weather.windDirDeg.toFixed(0)}°` : '--' }}</strong></div>
      <div><span>{{ limitLabel }}</span><strong>{{ model.droneLimit.valueMs === null ? '--' : `${fixed(model.droneLimit.valueMs)} m/s` }}</strong></div>
    </div>

    <p class="weather-note">
      <template v-if="model.overDroneLimit === true">当前风速已超所选机型抗风上限，该机不可放飞。<br></template>
      <template v-else-if="model.overCritical">当前风速已达全域熔断阈值，派单应停止，在飞任务按复核结论处置。<br></template>
      <template v-else-if="model.overWarning">当前风速已达预警阈值，候选判定将按机型分档淘汰。<br></template>
      阈值来源：{{ thresholds.source }}<template v-if="weather.status.biasActive"> · 注入偏置生效中（累计注入 {{ weather.status.injectedCount }} 次）</template>
    </p>
  </section>
</template>
