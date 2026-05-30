import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 【中文：Vite 构建工具配置 —— Soulous 前端开发服务器和构建配置】
export default defineConfig({
  // 【中文：启用 React 插件（JSX 转换、Fast Refresh 等）】
  plugins: [react()],
  server: {
    // 【中文：开发服务器端口号】
    port: 5173,
    // 【中文：开发服务器代理配置 —— 将 API 请求和上传文件请求转发到后端，避免跨域问题】
    proxy: {
      // 【中文：将 /api 开头的请求代理到后端 8080 端口】
      '/api': 'http://127.0.0.1:8080',
      // 【中文：将 /uploads 开头的请求代理到后端 8080 端口（用于访问用户上传的文件）】
      '/uploads': 'http://127.0.0.1:8080'
    }
  }
});
