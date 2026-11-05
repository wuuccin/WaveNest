<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { usePredictionStore } from '../stores/prediction'
import { useShipStore } from '../stores'
import { SOURCE_TEXT } from '../api/predictionSource'
import { PREMISE_TEXT, remainingText } from '../api/prediction'

/**
 * 预测看台面板（阶段二 1 · 前端）。
 *
 * <p>判据三条，全在这个组件里有对应实现：
 * <ol>
 *   <li><b>列出全部在航船</b>——{@code rows} 直接用 store 的 {@code items}，<b>不做任何过滤</b>
 *       （不按前提状态筛、不按有效期筛、不按点数为 0 筛）。要筛也只能是"显示/隐藏"开关，
 *       且默认全显示；<b>尤其不能因为 premise 非 VALID 就把船藏起来</b>，
 *       那正好把最该看的船藏掉了。</li>
 *   <li><b>点选后地图叠加该船预测点列</b>——点行即 {@code store.select()}，
 *       叠加由 {@code MapView} 订阅 {@code store.overlay} 完成；本组件只负责"点了谁"。</li>
 *   <li><b>premise 非 VALID 有醒目标记</b>——行级红/橙底色 + 状态徽标 + 面板顶部计数条；
 *       并且地图终点标记也会变红（见 predictionLayer）。三处标记都由
 *       {@code isPremiseFlagged}／{@code premiseLevel} 统一判定，不在模板里各写一遍。</li>
 * </ol>
 */

const props = defineProps({
  /** 数据来源，与 App 的 source 一致：live / mock */
  source: { type: String, default: 'live' },
  /** 是否展开（调度台里可折叠，总览页默认展开） */
  collapsible: { type: Boolean, default: false }
})

const store = usePredictionStore()
const ship = useShipStore()
const open = ref(true)
const mode = computed(() => (props.source === 'mock' ? 'mock' : 'auto'))
const refreshedAt = ref('')

/** 判据 1：全部在航船，绝不在这里做筛选。 */
const rows = computed(() => store.items)

const flaggedCount = computed(() => store.flagged.length)
const flaggedText = computed(() => {
  if (!flaggedCount.value) return '全部前提成立'
  return `${flaggedCount.value} 条前提非 VALID（需标注）`
})

/** 剩余有效期每秒重算一次——用 tick 逼 computed 重新求值，避免"卡在 600 秒"。 */
const tick = ref(0)
let tickTimer = setInterval(() => { tick.value += 1 }, 1000)
onUnmounted(() => clearInterval(tickTimer))

function remainingOf(row) {
  // 显式依赖 tick：剩余秒数是随时间变的，computed 缓存必须被打破
  void tick.value
  if (row.remainingSec === null) return '—'
  if (!Number.isFinite(row.t0Ms) || !Number.isFinite(row.validitySec)) return remainingText(row.remainingSec)
  const seconds = Math.max(0, Math.round((row.t0Ms + row.validitySec * 1000 - Date.now()) / 1000))
  return remainingText(seconds)
}

function courseText(row) {
  return Number.isFinite(row.courseDeg) ? `${row.courseDeg.toFixed(1)}°` : '—'
}

function premiseText(row) {
  // 不认识的值原样显示出来——藏在 UNKNOWN 后面等于把契约问题洗掉
  return PREMISE_TEXT[row.premise] || `未知取值 ${row.premise}`
}

function updatedText() {
  if (!store.updatedAt) return '尚未刷新'
  return new Date(store.updatedAt).toLocaleTimeString('zh-CN', { hour12: false })
}

async function refresh() {
  await store.refresh(mode.value)
  refreshedAt.value = updatedText()
}

function pick(row) {
  if (store.selectedMmsi === row.mmsi) { store.clearSelection(); return }
  ship.select(row.mmsi)
  void store.select(row.mmsi, mode.value)
}

watch(() => props.source, () => { store.reset(); void refresh() })

// 首帧就拉一次：面板不该是空的等着用户点刷新
void refresh()
</script>

<template>
  <section class="prediction-panel" :class="{ collapsed: collapsible && !open }" aria-label="预测看台">
    <div class="panel-head compact">
      <div>
        <h2>预测看台</h2>
        <p>在航船预测一览；点选一行即在地图叠加该船预测点列</p>
      </div>
      <div class="prediction-head-tools">
        <span class="count-badge">{{ store.count }} 艘在航</span>
        <button v-if="collapsible" class="text-button" @click="open = !open">{{ open ? '收起' : '展开' }}</button>
        <button class="text-button" :disabled="store.loading" @click="refresh">{{ store.loading ? '刷新中…' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="open">
      <!-- 数据源必须写在明面上：样例数据冒充实时数据是最容易被误读的一类问题 -->
      <div class="prediction-source" :class="{ live: store.source === 'live' }">
        <strong>{{ SOURCE_TEXT[store.source] || '等待数据' }}</strong>
        <span>更新于 {{ store.updatedAt ? updatedText() : refreshedAt || '尚未刷新' }}</span>
      </div>

      <p v-if="store.notice" class="prediction-notice" role="status">{{ store.notice }}</p>
      <p v-if="store.error" class="prediction-error" role="alert">预测列表读取失败：{{ store.error }}（下方为上一次成功的数据）</p>

      <!-- 判据 3 的第一处标记：顶部计数条，不用滚到底也能知道有几条异常 -->
      <div class="prediction-flagbar" :class="{ clear: !flaggedCount }" role="status">
        <span class="flagbar-dot"></span>
        <strong>{{ flaggedText }}</strong>
        <span v-if="flaggedCount">这些船的预测前提已不成立或未判定，叠加线在地图上以红色/橙色标出。</span>
      </div>

      <div v-if="rows.length" class="prediction-table-wrap">
        <table class="order-table prediction-table">
          <thead>
            <tr>
              <th>MMSI</th>
              <th>版本</th>
              <th>前提</th>
              <th>航向</th>
              <th>预测点数</th>
              <th>有效期剩余</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="row.mmsi"
              :class="{ selected: store.selectedMmsi === row.mmsi, flagged: row.flagged, }"
              tabindex="0"
              role="button"
              :aria-pressed="store.selectedMmsi === row.mmsi"
              @click="pick(row)"
              @keydown.enter.prevent="pick(row)"
              @keydown.space.prevent="pick(row)"
            >
              <td>
                <strong>{{ row.mmsi }}</strong>
                <small v-if="row.flagged">点选查看预测（前提非 VALID）</small>
                <small v-else>点选叠加预测</small>
              </td>
              <td><span class="prediction-version">v{{ row.version ?? '—' }}</span></td>
              <td>
                <span class="premise-chip" :class="row.premiseLevel">{{ row.premise }}</span>
                <small class="premise-caption">{{ premiseText(row) }}</small>
              </td>
              <td>{{ courseText(row) }}</td>
              <td>{{ row.pointCount }}</td>
              <td>
                <span :class="{ 'prediction-stale': row.remainingSec === 0 }">{{ remainingOf(row) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-block">
        <strong>当前没有在航船</strong>
        <span>后端尚未产出任何预测快照；船舶 AIS 进入跟踪后会自动出现。</span>
      </div>

      <!-- 选中提示：让"点选 → 叠加"这条因果在界面里闭合 -->
      <div v-if="store.selectedMmsi" class="prediction-selected">
        <div>
          <span>已选船</span>
          <strong>{{ store.selectedMmsi }}</strong>
        </div>
        <div>
          <span>地图叠加</span>
          <strong>{{ store.overlay ? `${store.overlay.points.length} 个预测点` : (store.detailLoading ? '读取中…' : '无点列') }}</strong>
        </div>
        <div v-if="store.detailError" class="prediction-detail-error">{{ store.detailError }}</div>
        <button class="text-button" @click="store.clearSelection()">取消叠加</button>
      </div>
    </div>
  </section>
</template>
