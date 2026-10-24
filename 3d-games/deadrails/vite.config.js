import { defineConfig } from "vite";
import { fileURLToPath } from "url";
import path from "path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  root: __dirname,
  publicDir: "public",
  server: {
    port: 5173,
    host: true,
    proxy: {
      "/socket.io": {
        target: "http://127.0.0.1:3001",
        ws: true
      }
    }
  },
  build: {
    outDir: "dist",
    emptyOutDir: true
  }
});
