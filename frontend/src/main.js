import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import 'leaflet/dist/leaflet.css'
import './styles/main.css'
import './styles/mobile.css'
import './styles/crew.css'
import './styles/theme.css'
createApp(App).use(createPinia()).mount('#app')
