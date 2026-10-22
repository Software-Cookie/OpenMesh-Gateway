/// <reference types="vitest" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// In development the console runs on Vite (port 5173) and every non-Vite request is proxied to
// the gateway, so the console talks to the same origin exactly like it does in production.
// The app uses hash routing (/#/routes), so the gateway's own path space stays free for API routes.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const gateway = env.GATEWAY_URL || 'http://localhost:8080';

  return {
    plugins: [react()],
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      sourcemap: false,
      chunkSizeWarningLimit: 600,
    },
    server: {
      port: 5173,
      proxy: {
        '^/(?!(src|@vite|@react-refresh|@id|@fs|node_modules|favicon\\.svg)(/|$)).+': {
          target: gateway,
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  };
});
