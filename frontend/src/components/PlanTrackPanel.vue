<script setup>
import { computed, watch } from 'vue'
import { usePlanTrackStore } from '../stores'
import { KIND, SEGMENT_ORDER, SEGMENT_STYLE, segmentLabel, offsetText, kindRank } from '../api/planTrack'

/**
 * 规划航迹面板（阶段二 2 · 杨添爻 11-04）。
 *
 * <p><b>为什么要有面板</b>：地图上只能看出「有线」，看不出「缺了哪段、这段多长、共几个航点」。
 * 本任务的判据是「三段着色正确」，逐段列出点数与历时，才谈得上「核过」。
 *
 * <p>与预测看台面板的分工：那块管<b>船舶预测</b>（点选船→看它的预测点列），
 * 这块管<b>无人机计划航迹</b>（按订单）。两块数据源不同，互不影响。
 */
const props = defineProps({ source: { type: String, default: 'live' } })
const store = usePlanTrackStore()

// 换数据源（实时/离线/回放）即全清计划线，与 stores/planTrack.js 不变式 2 对齐。
// 与 PredictionPanel 同思路：数据源切换是「全清」语义，不在切换点各自漏清。
watch(() => props.source, () => { store.reset() })

/** 段按契约顺序展示；契约外段排最后并原样回显（契约漂移不洗掉）。 */
const rows = computed(() => store.tracks.map(track => ({
  ...track,
  segments: [...track.segments].sort((a, b) => kindRank(a.kind) - kindRank(b.kind))
})))

const summary = computed(() => store.summary)

/** 每段一行摘要：`12 个航点 · 历时 3 分 20 秒 · 高度 40–120 m`。 */
function metaOf(segment) {
  if (!segment.points.length) return '无航点'
  const first = segment.points[0]
  const last = segment.points.at(-1)
  const span = offsetText(last?.tMs, first?.tMs)
  const altitudes = segment.points.map(point => point.altM).filter(value => Number.isFinite(value))
  const altText = altitudes.length ? `${Math.round(Math.min(...altitudes))}–${Math.round(Math.max(...altitudes))} m` : '高度未回传'
  return `${segment.points.length} 个航点${span ? ` · 历时 ${span.replace('+', '')}` : ''} · ${altText}`
}

const styleOf = kind => SEGMENT_STYLE[kind] || { color: '#7c2d3a' }

function hover(kind) { store.setHighlight(kind) }
function leave() { store.setHighlight('') }
</script>

<template>
  <section class="plan-track-panel" aria-label="规划航迹">
    <div class="panel-head">
      <div>
        <h2>规划航迹</h2>
        <p>三段计划航线；点划线，与船舶预测轨迹（虚线）区分</p>
      </div>
      <span class="count-badge">{{ store.count }} 笔订单 · {{ store.totalPoints }} 航点</span>
    </div>

    <!-- 两条提示并列，不互斥：同时「缺段」和「多出契约外段」是可能的，
         用 v-if/v-else-if 会让后者盖掉前者——那等于用一个问题遮住另一个。 -->
    <p v-if="summary.unexpected.length" class="plan-track-warn severe" role="status">
      出现契约外分段：{{ summary.unexpected.join('、') }}——后端分段口径可能已变更，请核对 22 §4
    </p>
    <p v-if="summary.missing.length" class="plan-track-warn" role="status">
      缺分段：{{ summary.missing.join('、') }}（未组批或航线未生成时属正常）
    </p>

    <p v-if="!rows.length" class="plan-track-empty">
      {{ props.source === 'mock' ? '离线演示不产出规划航迹帧' : '等待后端下发 plan_track 帧' }}<br>
      <small>航迹随派发产出；收到一帧后这里会按三段列出航点数与历时。</small>
    </p>

    <article v-for="track in rows" v-else :key="track.orderId" class="plan-track-card">
      <header class="plan-track-card-head">
        <strong>{{ track.orderId }}</strong>
        <small>
          {{ track.routeId || '航迹编号未回传' }}
          <template v-if="track.comboId"> · 组合 {{ track.comboId }}</template>
        </small>
      </header>
      <div class="plan-track-segments">
        <div
          v-for="segment in track.segments"
          :key="segment.kind || 'blank'"
          class="plan-track-segment"
          :class="{ empty: segment.empty, unexpected: !segment.known }"
          @mouseenter="hover(segment.kind)"
          @mouseleave="leave"
        >
          <i class="swatch" :style="{ background: styleOf(segment.kind).color }"></i>
          <span class="name">{{ segmentLabel(segment.kind) }}</span>
          <span class="meta">{{ metaOf(segment) }}</span>
        </div>
      </div>
    </article>
  </section>
</template>
