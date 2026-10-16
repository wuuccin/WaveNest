import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
// 开发代理指向后端 wl-app 默认端口（application.yml: server.port=8080）。
// 若本机改过后端端口，请在 application-local.yml 覆盖，并同步改这里的 target。
const backendPort = 8080
export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/api': `http://127.0.0.1:${backendPort}`, '/ws': { target: `ws://127.0.0.1:${backendPort}`, ws: true } } }
})
