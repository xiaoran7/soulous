import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 【中文：Vitest 单元测试配置 —— Soulous 前端单元测试运行环境配置】
export default defineConfig({
  // 【中文：启用 React 插件，支持 JSX 和组件测试】
  plugins: [react()],
  test: {
    // 【中文：使用 jsdom 模拟浏览器 DOM 环境（React 组件测试需要）】
    environment: 'jsdom',
    // 【中文：全局注入 test/expect/describe 等 API，无需每个文件手动 import】
    globals: true,
    // 【中文：测试初始化文件 —— 在每个测试文件执行前运行，用于设置全局 mock 等】
    setupFiles: ['./src/test-setup.ts'],
    // 【中文：禁用 CSS 处理，加速测试执行（样式不影响单元测试结果）】
    css: false,
    // Playwright specs live under e2e/ and have their own runner — Vitest must skip them
    // (it tried to import @playwright/test and fail with "test.describe not supported").
    // 【中文：排除 Playwright E2E 测试目录 —— Playwright 有自己的测试运行器，
    //   Vitest 不应尝试解析 @playwright/test 依赖】
    exclude: ['node_modules', 'dist', 'e2e/**']
  }
});
