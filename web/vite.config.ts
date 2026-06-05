import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8091',
        changeOrigin: true,
        secure: false,
      },
      '/login': {
        target: 'http://localhost:8091',
        changeOrigin: true,
        secure: false,
      },
      '/logout': {
        target: 'http://localhost:8091',
        changeOrigin: true,
        secure: false,
      },
      '/oauth': {
        target: 'http://localhost:8091',
        changeOrigin: true,
        secure: false,
      },
      '/health': {
        target: 'http://localhost:8091',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
  },
})
