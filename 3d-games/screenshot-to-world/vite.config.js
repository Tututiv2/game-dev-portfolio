import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// base: './' so the built files work when Electron loads them off disk (file://),
// not just off a dev server.
export default defineConfig({
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(process.cwd(), 'index.html'),
        debug: resolve(process.cwd(), 'debug.html'),
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
});
