import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  server: { port: 5273, strictPort: true },
  build: {
    target: 'es2020',
    // ONE entry, and an IIFE bundle rather than an ES module.
    //
    // This matters: the owner opens the game by double-clicking a file, and
    // Chrome refuses to load `type="module"` scripts over file:// at all. An
    // IIFE has no imports, so tools/bundle.mjs can inline it straight into the
    // HTML and the result runs with no server and no commands.
    rollupOptions: {
      input: resolve(process.cwd(), 'orbit.html'),
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'app.js',
        assetFileNames: '[name][extname]',
      },
    },
  },
});
