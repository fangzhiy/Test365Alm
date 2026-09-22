import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const backendUrl = process.env.TEST365ALM_DEV_BACKEND_URL ?? 'http://127.0.0.1:8080'
const devHost = process.env.TEST365ALM_WEB_HOST ?? '127.0.0.1'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: { host: devHost, proxy: { '/api': backendUrl, '/health': backendUrl } },
})
