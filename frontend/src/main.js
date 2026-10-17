import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import 'leaflet/dist/leaflet.css'
import './styles/main.css'
createApp(App).use(createPinia()).mount('#app')
