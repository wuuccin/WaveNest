import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import 'leaflet/dist/leaflet.css'
import './styles/main.css'
// 预测看台样式单独一文件：只新增类名，方便回滚与 review（阶段二 1 · 杨添爻）
import './styles/prediction.css'
// 规划航迹样式（阶段二 2）：同样只新增类名
import './styles/planTrack.css'
// 气象标尺与注入面板样式（11-14 2-7）：只新增 .weather-* 类名，不改既有规则
import './styles/weather.css'
createApp(App).use(createPinia()).mount('#app')
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('/sw.js').catch(() => {})
}
