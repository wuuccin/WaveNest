import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backend = env.WAVENEST_BACKEND_URL || 'http://127.0.0.1:8080'
  return {
  plugins: [vue()],
  server: { proxy: { '/api': backend, '/sim': backend, '/ws': { target: backend, ws: true } } }
  }
})
