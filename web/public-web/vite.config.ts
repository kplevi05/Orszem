import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The browser always calls the API same-origin at /api/v1, so production needs no CORS
// header at all: Caddy reverse-proxies /api/* on the site origin to the backend.
// In development, Vite plays the part Caddy plays in production.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    // Plain static output for Caddy to serve. No SSR, no Node server in production.
    outDir: 'dist',
    sourcemap: true,
  },
})
