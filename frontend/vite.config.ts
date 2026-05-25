import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // During development, proxy all /api calls to Spring Boot on :8080
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Build output goes directly into Spring Boot's static resources
    // so that the fat jar can serve the SPA
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
});
