import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import 'leaflet/dist/leaflet.css'
import './styles/main.css'
// 预测看台样式单独一文件：只新增类名，方便回滚与 review（阶段二 1 · 杨添爻）
import './styles/prediction.css'
createApp(App).use(createPinia()).mount('#app')
