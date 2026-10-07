import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    // Proxy vers l'API en developpement : le navigateur ne voit qu'une origine, donc
    // pas de CORS a configurer en local. En production, CloudFront route /v1/* vers
    // l'ALB -- meme origine, meme absence de CORS, et des cookies de session qui
    // peuvent rester SameSite=Strict.
    proxy: {
      '/v1': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
  },
})
