import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Порт 3000: на него настроен клиент costguard-frontend в infra/keycloak/costguard-realm.json
export default defineConfig({
  plugins: [react()],
  server: { port: 3000, strictPort: true },
  preview: { port: 3000, strictPort: true },
});
