import { defineConfig, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 【中文：后端代理配置 —— 统一指向本地 8080】
 * changeOrigin + 重写 Origin 头为 5173：后端 CORS 白名单只认 localhost:5173，
 * 当开发服务器跑在其它端口（PORT 环境变量 / 端口被占自动顺延）时代理请求依然放行。
 */
const backendProxy: ProxyOptions = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  configure: (proxy) => {
    proxy.on('proxyReq', (proxyReq) => {
      if (proxyReq.getHeader('origin')) proxyReq.setHeader('origin', 'http://localhost:5173');
    });
  }
};

// 【中文：Vite 构建工具配置 —— Soulous 前端开发服务器和构建配置】
export default defineConfig({
  // 【中文：启用 React 插件（JSX 转换、Fast Refresh 等）】
  plugins: [react()],
  server: {
    // 【中文：开发服务器端口号；支持 PORT 环境变量覆盖（preview 工具多实例并行时自动分配端口）】
    port: Number(process.env.PORT) || 5173,
    // 【中文：开发服务器代理配置 —— 将 API 请求和上传文件请求转发到后端，避免跨域问题】
    proxy: {
      // 【中文：将 /api 开头的请求代理到后端 8080 端口】
      '/api': backendProxy,
      // 【中文：将 /uploads 开头的请求代理到后端 8080 端口（用于访问用户上传的文件）】
      '/uploads': backendProxy
    }
  }
});
