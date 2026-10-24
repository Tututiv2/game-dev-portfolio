import { defineConfig } from "vite";

export default defineConfig({
  // Relative asset URLs so `dist/index.html` works from file://, subfolders, and static hosts without a root path.
  base: "./",
  root: ".",
  publicDir: "public",
  build: {
    outDir: "dist",
    sourcemap: true,
  },
  server: {
    port: 5173,
  },
});
