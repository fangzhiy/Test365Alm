import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const backendUrl = process.env.TEST365ALM_DEV_BACKEND_URL ?? 'http://127.0.0.1:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': backendUrl, '/health': backendUrl } },
})
