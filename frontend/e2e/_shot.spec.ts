import { test, expect } from '@playwright/test';
import * as fs from 'fs';

/**
 * 【一次性截图脚本（非回归测试）】真实教务 HTML → 真·DeepSeek 解析 → 截网格图 + 点击课程块截详情弹层。
 * 需后端以 SOULOUS_CAPTCHA_ENABLED=false 启动（真实 DeepSeek）。
 */
const API = 'http://127.0.0.1:8080';
const HTML_PATH = 'C:\\Users\\tanner\\Desktop\\新建 文本文档.txt';

test('import real timetable, screenshot grid + detail', async ({ page }) => {
  test.setTimeout(180_000);
  const html = fs.readFileSync(HTML_PATH, 'utf-8');
  const username = 'shot_' + Date.now();
  const password = 'Passw0rd!';

  expect((await page.request.post(`${API}/api/auth/register`, {
    data: { username, password, confirmPassword: password, nickname: '截图用户', captchaId: 'x', captchaCode: 'x' }
  })).ok()).toBeTruthy();
  expect((await page.request.post(`${API}/api/auth/login`, {
    data: { username, password, captchaId: 'x', captchaCode: 'x' }
  })).ok()).toBeTruthy();

  await page.goto('/');
  await page.reload();

  await page.getByRole('button', { name: '课表' }).click();
  // 粘贴 HTML 现收在折叠的"高级"导入区里，先展开
  await page.getByRole('button', { name: /粘贴课表 HTML/ }).click();
  await page.getByPlaceholder(/课表页的 HTML/).fill(html);
  await page.getByRole('button', { name: '导入 HTML' }).click();

  await expect(page.getByText(/导入成功：识别到/)).toBeVisible({ timeout: 150_000 });
  await expect(page.getByText('离散数学')).toBeVisible({ timeout: 10_000 });

  fs.mkdirSync('e2e/__screenshots__', { recursive: true });
  await page.screenshot({ path: 'e2e/__screenshots__/timetable-grid.png', fullPage: true });

  // 点击"数据结构"课程块，弹出详情卡
  await page.getByRole('button', { name: /数据结构/ }).first().click();
  await expect(page.getByText('课程详情')).toBeVisible({ timeout: 5_000 });
  await page.screenshot({ path: 'e2e/__screenshots__/timetable-detail.png', fullPage: true });
});
