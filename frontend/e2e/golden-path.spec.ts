import { test, expect } from '@playwright/test';

/**
 * 【Soulous 黄金路径端到端测试】
 *
 * 【测试策略】
 * 验证用户从注册到创建任务的核心业务流程（happy path），
 * 确保前端页面的关键交互链路在真实浏览器环境中正常工作。
 *
 * 【端到端覆盖的场景】
 *   1. 注册一个全新用户
 *   2. 进入工作台（Dashboard）
 *   3. 导航到任务页面，通过表单创建一个 SIMPLE 类型的任务
 *   4. 验证新创建的任务出现在任务列表中
 *
 * 【为什么不对 AI 审核和通知到达做断言？】
 *   AI 审核依赖异步 LLM 处理——即使 mock provider 返回很快，
 *   但将 UI 断言绑定到"通知徽章计数增加"会因 SSE 时序和 60 秒回退轮询而产生不稳定性。
 *   提交和 AI 审核的正确性由后端测试覆盖；这里只验证用户可见的顶层 happy path。
 *
 * 【前置条件】（参见 README "E2E 测试"）
 *   • 后端运行在 http://127.0.0.1:8080，配置如下：
 *       SOULOUS_CAPTCHA_ENABLED=false   （禁用验证码，简化测试流程）
 *       SOULOUS_LLM_PROVIDER=mock       （使用 mock AI provider）
 *       SOULOUS_RATE_LIMIT_ENABLED=false （禁用速率限制，避免测试被限流）
 *   • 前端由 Vite 在 http://127.0.0.1:5173 提供服务（Playwright 通过 webServer 配置自动启动）
 *
 * Soulous golden-path E2E.
 *
 * What it proves end-to-end:
 *   1. Register a fresh user
 *   2. Land on the workspace
 *   3. Navigate to Tasks, create a SIMPLE task via the form
 *   4. Verify the new task appears in the list
 *
 * Why not also assert AI review + notification arrival? That depends on async LLM
 * processing — even mock provider returns quickly, but tying a UI assertion to
 * "notification badge ticks up" introduces flakiness from SSE timing and the
 * 60s fallback poll. Submission + AI review correctness is covered by backend
 * tests; here we only validate the user-visible top-level happy path.
 *
 * Prereqs (see README "E2E 测试"):
 *   • Backend running at http://127.0.0.1:8080 with:
 *       SOULOUS_CAPTCHA_ENABLED=false
 *       SOULOUS_LLM_PROVIDER=mock
 *       SOULOUS_RATE_LIMIT_ENABLED=false
 *   • Frontend served by Vite at http://127.0.0.1:5173 (Playwright starts it via webServer).
 */

/**
 * 【生成唯一后缀】
 * 使用时间戳 + 随机字符串生成唯一标识，用于确保每次测试运行时
 * 用户名和任务标题不重复，避免并发测试或重试时的命名冲突。
 */
const uniqueSuffix = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

/**
 * 【黄金路径测试：注册 → 创建任务 → 在列表中看到任务】
 * 完整模拟新用户的典型使用流程：
 *   1. 注册新账号（使用唯一用户名避免冲突）
 *   2. 验证进入工作台后导航栏可见
 *   3. 导航到任务页面，切换到"创建任务"标签
 *   4. 填写任务表单并提交
 *   5. 验证新任务在列表中正确显示
 * 这是整个应用最核心的用户旅程，覆盖了认证、导航、表单提交、数据展示。
 */
test('register → create task → see it in list', async ({ page }) => {
  const username = `e2e_${uniqueSuffix()}`;
  const password = 'Soulous-e2e-pass1';

  await page.goto('/');

  // 【步骤 1：注册新用户】
  // --- Register ---
  await page.getByRole('button', { name: '创建新账号' }).click();
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码', { exact: true }).fill(password);
  await page.getByPlaceholder('再次输入密码').fill(password);
  await page.getByPlaceholder('昵称').fill('E2E Tester');
  // 【验证码已禁用】后端配置 SOULOUS_CAPTCHA_ENABLED=false，任意非空验证码即可通过
  // Captcha is disabled on the backend; any non-empty code passes.
  await page.getByPlaceholder('验证码').fill('XXXX');
  await page.getByRole('button', { name: '注册' }).click();

  // 【步骤 2：验证进入工作台】
  // --- Dashboard ---
  // 【导航栏"任务"按钮】页面上有多处包含"任务"文字的按钮（如宠物 aria-label、"新建任务"等），
  // 所以使用 exact: true 精确匹配顶部导航栏的"任务"按钮。
  // The top-nav "任务" button is the navigation entry; multiple other buttons mention
  // "任务" in their label (hero pet aria-label, "新建任务", etc.), so we pin to exact match.
  const tasksNav = page.getByRole('button', { name: '任务', exact: true });
  await expect(tasksNav).toBeVisible();

  // 【步骤 3：导航到任务页面并切换到创建标签】
  // --- Navigate to Tasks page ---
  await tasksNav.click();

  // 【切换到"创建任务"标签】TasksPage 默认停留在"列表"标签，需要手动切换到"创建"标签
  // Switch to the "create" sub-tab — the Tasks page lands on "list" by default.
  await page.getByRole('button', { name: '创建任务', exact: true }).click();

  const taskTitle = `E2E 任务 ${uniqueSuffix()}`;

  // 【步骤 4：填写任务表单】
  // 使用 placeholder 文本作为选择器，这是重构最安全的选择方式。
  // Fill the form. Placeholder text is the most refactor-resistant selector here.
  await page.getByPlaceholder('例如：复习二叉树遍历').fill(taskTitle);
  await page.getByPlaceholder('简单写一下要做什么').fill('Playwright 自动化创建的任务');

  // 【提交表单】主操作按钮标签为"添加任务"（带 Plus 图标 + 文字）
  // Submit. The primary button label is "添加任务" (Plus icon + text).
  await page.getByRole('button', { name: /添加任务/ }).click();

  // 【步骤 5：验证任务出现在列表中】
  // 【自动切换回列表标签】提交成功后表单会自动切回列表标签（TasksPage.tsx:257 setTab('list')）
  // --- Verify the task shows up in the list ---
  // Form auto-switches back to the list tab on success (TasksPage.tsx:257 setTab('list')).
  await expect(page.getByText(taskTitle)).toBeVisible({ timeout: 10_000 });
});
