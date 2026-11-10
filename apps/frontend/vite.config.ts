import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Порт 3000: на него настроен клиент costguard-frontend в infra/keycloak/costguard-realm.json.
// /api проксируется в ядро (CORE_URL, по умолчанию localhost:8080), поэтому CORS не нужен.
// eslint-disable-next-line
declare const process: { env: Record<string, string | undefined> };
const core = process.env.CORE_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: { port: 3000, strictPort: true, proxy: { '/api': { target: core, changeOrigin: true } } },
  preview: { port: 3000, strictPort: true, proxy: { '/api': { target: core, changeOrigin: true } } },
});
