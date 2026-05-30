import { defineConfig, devices } from '@playwright/test';

/**
 * 【中文：Playwright E2E 测试配置 —— Soulous 黄金路径端到端测试。
 *
 * 前置条件（本配置不会自动启动后端，需手动运行，详见 README "E2E 测试" 章节）：
 *   • 后端运行在 http://127.0.0.1:8080，并设置以下环境变量：
 *       SOULOUS_CAPTCHA_ENABLED=false          # 跳过验证码
 *       SOULOUS_LLM_PROVIDER=mock              # 使用模拟 LLM
 *       SOULOUS_RATE_LIMIT_ENABLED=false       # 禁用限流，避免快速脚本点击触发 429
 *     建议：使用临时 H2 文件或内存数据库，确保每次运行结果可复现。
 *
 * webServer 配置会自动启动 Vite 开发服务器；后端需手动启动，
 * 因为从 Node 配置中启动 JVM + Maven 在不同机器上稳定性较差。
 *
 * Playwright config for Soulous golden-path E2E.
 *
 * Prerequisites (not auto-spun by this config — see README "E2E 测试" section):
 *   • Backend on http://127.0.0.1:8080 with these env:
 *       SOULOUS_CAPTCHA_ENABLED=false
 *       SOULOUS_LLM_PROVIDER=mock
 *       SOULOUS_RATE_LIMIT_ENABLED=false     # avoid 429 during fast scripted clicks
 *     Recommended: a throwaway H2 file or in-memory DB so a clean run is reproducible.
 *
 * The webServer block starts Vite dev for us; the backend stays manual because
 * spinning JVM + Maven from a Node config is fragile across machines.
 */
export default defineConfig({
  // 【中文：E2E 测试文件目录】
  testDir: './e2e',
  fullyParallel: false,                  // single-user golden-path; parallelism doesn't help here
  // 【中文：单用户黄金路径测试，不需要并行执行（并行对单用户场景无帮助）】
  forbidOnly: !!process.env.CI,
  // 【中文：CI 环境下禁止 test.only（防止误提交跳过其他测试）】
  retries: process.env.CI ? 2 : 0,
  // 【中文：CI 环境失败重试 2 次，本地不重试（便于快速发现失败）】
  workers: 1,
  // 【中文：单 worker 执行，确保测试顺序稳定】
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  // 【中文：CI 环境使用 GitHub + HTML 报告器（不自动打开）；本地使用简洁列表输出】
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    // 【中文：E2E 测试基础 URL，默认指向本地 Vite 开发服务器】
    trace: 'retain-on-failure',
    // 【中文：失败时保留 Playwright trace 文件，便于调试】
    screenshot: 'only-on-failure',
    // 【中文：仅在测试失败时截图】
    video: 'retain-on-failure',
    // 【中文：失败时保留视频录像】
  },
  projects: [
    // 【中文：仅使用 Chromium 浏览器进行测试（单浏览器覆盖核心路径）】
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        // 【中文：未设置 E2E_BASE_URL 时自动启动 Vite 开发服务器】
        command: 'npm run dev',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: true,        // we're already running it from the preview server in dev
        // 【中文：复用已运行的开发服务器（避免重复启动）】
        timeout: 60_000,
        // 【中文：等待服务器启动的超时时间（60 秒）】
      },
});
