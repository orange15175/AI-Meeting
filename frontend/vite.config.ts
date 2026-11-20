import path from "node:path";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// 后端地址：优先环境变量，默认 8002（见 application.yaml 的 server.port）。
const apiTarget = process.env.VITE_API_TARGET || "http://127.0.0.1:8002";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: apiTarget,
        changeOrigin: true,
        ws: true,
      },
    },
  },
  // 不配置 manualChunks：按包名强制分组会把未使用的 Element Plus 组件
  // 一并拉进 chunk（实测反而从 561 kB 涨到 948 kB）。组件已在 main.ts 按需注册，
  // 让 Rollup 自行做 tree-shaking 才能得到更小的产物。
  build: {
    chunkSizeWarningLimit: 600,
  },
});
