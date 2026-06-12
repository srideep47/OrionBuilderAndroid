import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Bound to localhost so the in-app WebView can reach the dev server.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    strictPort: false,
  },
})
