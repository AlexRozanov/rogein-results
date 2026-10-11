import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:18790",
        changeOrigin: true,
      },
      "/media": "http://127.0.0.1:18790",
    },
  },
});
