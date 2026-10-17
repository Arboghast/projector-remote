import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `tailscale serve` addresses this server by the serving machine's Tailscale name (<machine>.<tailnet>.ts.net); Vite
// refuses names it doesn't know. Who can reach it is Tailscale's call, not this list's.
const TAILNET_HOSTS = ['.ts.net']

// In dev the relay runs separately; in production it serves the built files itself.
const RELAY = 'http://127.0.0.1:8790'

export default defineConfig({
  plugins: [react()],
  server: {
    allowedHosts: TAILNET_HOSTS,
    proxy: {
      '/ws': { target: RELAY, ws: true },
      '/share': RELAY,
      '/health': RELAY,
    },
  },
  preview: { allowedHosts: TAILNET_HOSTS },
})
