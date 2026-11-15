<script setup>
/**
 * 气象注入入口（11-14 任务 2-7 · 杨添爻）。
 *
 * <p>后端 {@code WeatherInjectController} 已在，本组件只做入口与回显，不另立契约：
 * {@code POST /sim/weather/inject}（风速／风向／阵风／persist）、{@code DELETE /sim/weather/bias}（复位）、
 * {@code GET /sim/weather}（当前偏置与网格点）。
 *
 * <p><b>发布粒度</b>：注入一次是「每个网格点各发一条」，不是一条全局值（U-05 甲案）。
 * 所以回显必须报 {@code publishedCount}，让人看见「发了几条」，而不是只看见「注入成功」。
 *
 * <p><b>为什么不在这里直接改标尺</b>：标尺读实时 {@code data.weather} 帧。
 * 注入成功后偏置生效，下一轮自产风即带偏置，标尺随帧变化——
 * 若面板自己把风速塞进 store，就成了「界面按参数报数、系统按另一套判定」，两边对不上。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'
import { normalizeInjectInput } from '../api/weather'
import { useWeatherStore } from '../stores'

const props = defineProps({
  /** 数据源：离线演示（mock）下后端不参与，注入按钮禁用 */
  source: { type: String, default: 'live' },
  /** 外部统一禁用（如批量操作进行中） */
  disabled: { type: Boolean, default: false },
  /** 表单初始风速（m/s）：11 落在「M30T 上限 10 淘汰、M400 上限 12 保留」的演示带内 */
  defaultWindMs: { type: Number, default: 11 },
  /** 表单初始风向（度） */
  defaultDirDeg: { type: Number, default: 135 }
})

const weather = useWeatherStore()
const form = reactive({ windSpeedMs: props.defaultWindMs, windDirDeg: props.defaultDirDeg, gustMs: '', persist: true })
const busy = ref(false)
const feedback = ref('')
const failed = ref(false)

const locked = computed(() => props.disabled || busy.value || props.source === 'mock')
const gustValue = computed(() => (form.gustMs === '' ? null : Number(form.gustMs)))

async function refreshStatus() {
  try {
    weather.applyStatus(await api.weatherStatus())
  } catch (error) {
    weather.failStatus(error)
  }
}

async function inject() {
  failed.value = false
  let payload
  try {
    payload = normalizeInjectInput({ windSpeedMs: form.windSpeedMs, windDirDeg: form.windDirDeg, gustMs: gustValue.value, persist: form.persist })
  } catch (error) {
    failed.value = true
    feedback.value = error.message
    return
  }
  busy.value = true
  try {
    const result = await api.injectWeather(payload)
    // 回显按后端实际回传拼，不用本地入参反推——否则参数被后端归一（如风向取模）时界面会说谎
    feedback.value = `已注入 ${result.windMs} m/s / ${result.dirDeg}°，向 ${result.publishedCount} 个网格点各发一条`
      + `${result.persist ? '，后续自产风保持该海况' : '，仅本轮生效'}；累计注入 ${result.injectedCount} 次`
    await refreshStatus()
  } catch (error) {
    failed.value = true
    feedback.value = error.message
  } finally {
    busy.value = false
  }
}

async function reset() {
  failed.value = false
  busy.value = true
  try {
    const result = await api.clearWeatherBias()
    feedback.value = `已清除注入偏置，自产风回到基准海况（累计注入 ${result.injectedCount} 次）`
    await refreshStatus()
  } catch (error) {
    failed.value = true
    feedback.value = error.message
  } finally {
    busy.value = false
  }
}

onMounted(refreshStatus)
</script>

<template>
  <section class="weather-inject-panel" aria-label="气象注入">
    <div class="panel-head compact">
      <div>
        <h2>风速注入</h2>
        <p>{{ source === 'mock' ? '离线演示不连后端，注入不可用' : '注入一片风，验证候选分档与超限处置' }}</p>
      </div>
      <span class="weather-state" :class="weather.status.biasActive ? 'warning' : 'normal'">
        {{ weather.status.biasActive ? '偏置生效中' : '基准海况' }}
      </span>
    </div>

    <div class="weather-form">
      <label>
        <span>风速 {{ Number(form.windSpeedMs).toFixed(1) }} m/s</span>
        <input v-model.number="form.windSpeedMs" type="range" min="0" max="20" step="0.5" :disabled="locked" aria-label="注入风速">
      </label>
      <label>
        <span>风向（度）</span>
        <input v-model.number="form.windDirDeg" type="number" min="0" max="360" step="5" :disabled="locked" aria-label="注入风向">
      </label>
      <label>
        <span>阵风（可选，m/s）</span>
        <input v-model="form.gustMs" type="number" min="0" step="0.5" placeholder="缺省取风速 × 1.2" :disabled="locked" aria-label="注入阵风">
      </label>
      <label class="weather-switch">
        <input v-model="form.persist" type="checkbox" :disabled="locked">
        <span>持续生效（关闭则只发一轮）</span>
      </label>
    </div>

    <div class="weather-actions">
      <button class="button-primary" :disabled="locked" @click="inject">{{ busy ? '注入中…' : '注入这片风' }}</button>
      <button class="button-quiet" :disabled="locked" @click="reset">复位海况</button>
      <span v-if="weather.status.loaded" class="weather-meta">网格点 {{ weather.status.gridPointCount }} · 累计注入 {{ weather.status.injectedCount }} 次</span>
    </div>

    <p v-if="feedback" class="weather-feedback" :class="{ danger: failed }">{{ feedback }}</p>
    <p v-else-if="weather.status.error" class="weather-feedback danger">{{ weather.status.error }}</p>
    <p class="weather-note">注入值不得写入 sys_param：阈值是登记参数，观测走 data.weather 帧。</p>
  </section>
</template>
