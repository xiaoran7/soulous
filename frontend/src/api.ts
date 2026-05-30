/**
 * 【前端 API 客户端模块】
 * 封装所有与后端的 HTTP 通信，包括：
 * - 普通 JSON 请求（自动携带 cookie、401 自动刷新 token 并重试）
 * - 文件上传（支持 XHR 进度回调）
 * - SSE 流式请求（用于 AI 对话和每日复盘的流式输出）
 *
 * 所有 API 方法均返回 Promise，调用方无需关心认证刷新和错误重试细节。
 */
import type { CourseCreateInput, CourseEntry, DailyReview, ExpLog, FocusSession, GoalDetail, GoalSummary, MetricsSnapshot, Pet, SessionSummary, SessionView, StudyTask, SubmissionDetail, Summary, TimetableImportResult, User } from './types';

/** 【API 基础路径，可通过环境变量 VITE_API_BASE 覆盖，空字符串表示同源请求】 */
const API_BASE = import.meta.env.VITE_API_BASE ?? '';

/**
 * 【未授权错误类】
 * 当 401 响应且刷新 token 也失败时抛出，
 * 上层组件可据此判断用户会话已过期，引导重新登录。
 */
export class UnauthorizedError extends Error {
  constructor() { super('Unauthorized'); this.name = 'UnauthorizedError'; }
}

/**
 * Single-flight refresh: if a request gets 401, ask the server to rotate our
 * refresh-token cookie and retry once. Concurrent 401s share one in-flight refresh
 * (otherwise 10 stale requests during a token transition fire 10 refresh calls and
 * the replay-detection on the backend nukes the user).
 *
 * 【单次刷新机制】
 * 多个并发请求同时收到 401 时，只触发一次 token 刷新请求，
 * 其余请求复用同一个 Promise 等待刷新完成。
 * 这避免了短时间内大量刷新请求导致后端的重放检测误杀用户会话。
 */
let refreshInFlight: Promise<boolean> | null = null;

/**
 * 【刷新访问令牌】
 * 向后端发送刷新请求，轮换 refresh-token cookie 并获取新的 access token。
 * 利用 refreshInFlight 单例确保并发安全。
 */
async function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    try {
      const r = await fetch(`${API_BASE}/api/auth/refresh-token`, {
        method: 'POST',
        credentials: 'include'
      });
      return r.ok;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/** Endpoints that must NOT trigger the refresh-and-retry loop. */
/**
 * 【判断是否为认证相关端点】
 * 登录、注册、刷新 token 等端点不应触发 401 自动重试，
 * 否则会形成死循环。
 */
function isAuthEndpoint(path: string): boolean {
  return path.startsWith('/api/auth/login')
      || path.startsWith('/api/auth/register')
      || path.startsWith('/api/auth/refresh');
}

/**
 * 【通用 JSON 请求函数】
 * 所有 API 调用的基础函数，自动：
 * 1. 设置 Content-Type 为 application/json
 * 2. 携带 cookie（credentials: 'include'）
 * 3. 遇到 401 时自动刷新 token 并重试一次
 * 4. 非 2xx 响应解析错误信息并抛出
 *
 * @param path - API 路径（不含基础路径）
 * @param init - fetch 请求配置
 * @returns 解析后的 JSON 响应
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/json');
  const doFetch = () => fetch(`${API_BASE}${path}`, { ...init, headers, credentials: 'include' });

  let response = await doFetch();
  if (response.status === 401 && !isAuthEndpoint(path)) {
    if (await refreshAccessToken()) {
      response = await doFetch();
    }
  }
  if (response.status === 401) throw new UnauthorizedError();
  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }
  return response.json();
}

/**
 * 【从非 2xx 响应中提取可读错误信息，保证返回非空字符串】
 * 优先取后端 JSON 的 error 字段；响应体不是 JSON 或缺字段时回退到
 * statusText；HTTP/2 下 statusText 为空，再回退到带状态码的兜底文案。
 * 避免抛出 message 为空的 Error（前端 `{error && ...}` 会因此不渲染任何提示）。
 */
async function errorMessage(response: Response): Promise<string> {
  let msg = '';
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string') msg = body.error.trim();
  } catch { /* not JSON */ }
  if (!msg) msg = (response.statusText || '').trim();
  if (!msg) msg = `请求失败 (${response.status})`;
  return msg;
}

/**
 * 【文件上传函数】
 * 支持两种模式：
 * - 无 onProgress：使用普通 fetch，简洁高效
 * - 有 onProgress：使用 XMLHttpRequest 以获取上传进度事件
 * 两种模式均支持 401 自动刷新 token 并重试。
 *
 * @param path - 上传端点路径
 * @param formData - 包含文件的 FormData
 * @param onProgress - 可选的进度回调（0-100）
 * @returns 上传成功后的响应数据
 */
async function upload<T>(path: string, formData: FormData, onProgress?: (pct: number) => void): Promise<T> {
  const doFetch = () => fetch(`${API_BASE}${path}`, { method: 'POST', body: formData, credentials: 'include' });
  if (!onProgress) {
    let response = await doFetch();
    if (response.status === 401 && await refreshAccessToken()) {
      response = await doFetch();
    }
    if (response.status === 401) throw new UnauthorizedError();
    if (!response.ok) {
      const body = await response.json().catch(() => ({ error: response.statusText }));
      throw new Error(body.error ?? response.statusText);
    }
    return response.json();
  }
  // XHR path keeps progress events; on 401 we refresh once and retry via a recursive call
  // (the retry has onProgress=undefined to avoid a confusing double-progress UX during retry).
  /**
   * 【XHR 上传路径】
   * 使用 XMLHttpRequest 实现上传进度追踪。
   * 401 时递归调用自身（不传 onProgress）以避免重试时出现双重进度动画。
   */
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API_BASE}${path}`);
    xhr.withCredentials = true;
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = async () => {
      if (xhr.status === 401) {
        if (await refreshAccessToken()) {
          try { resolve(await upload<T>(path, formData)); } catch (err) { reject(err); }
          return;
        }
        reject(new UnauthorizedError());
        return;
      }
      if (xhr.status < 200 || xhr.status >= 300) {
        let err = xhr.statusText;
        try { err = JSON.parse(xhr.responseText).error ?? err; } catch { /* ignore */ }
        reject(new Error(err));
        return;
      }
      try { resolve(JSON.parse(xhr.responseText) as T); } catch (e) { reject(e); }
    };
    xhr.onerror = () => reject(new Error('网络异常，上传失败'));
    xhr.send(formData);
  });
}

/**
 * Minimal SSE consumer over fetch + ReadableStream. EventSource can't POST or send a body,
 * so we parse the wire format ourselves. The server emits:
 *   event: token / data: "<json-encoded string>"
 *   event: done  / data: <json final payload>
 *   event: error / data: "<json-encoded message>"
 * Lines are separated by single \n; events by blank line.
 *
 * 【SSE 流式请求函数】
 * 基于 fetch + ReadableStream 实现的 SSE 消费者。
 * 原生 EventSource 不支持 POST 请求体，因此手动解析 SSE 协议格式。
 *
 * 服务端发送三种事件类型：
 * - token：增量文本片段（JSON 编码的字符串），通过 onToken 回调实时传递
 * - done：最终完整响应（JSON 对象），resolve 返回的 Promise
 * - error：错误信息（JSON 编码的字符串），reject 返回的 Promise
 *
 * 协议格式：行以 \n 分隔，事件之间以空行分隔。
 * 以 ':' 开头的行为注释/心跳，直接忽略。
 *
 * @param path - SSE 端点路径
 * @param body - POST 请求体
 * @param onToken - 增量文本片段的回调函数
 * @returns 最终的完整响应数据
 */
async function streamSse<T>(path: string, body: unknown, onToken: (chunk: string) => void): Promise<T> {
  const doFetch = () => fetch(`${API_BASE}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
    body: JSON.stringify(body)
  });

  let response = await doFetch();
  if (response.status === 401 && !isAuthEndpoint(path)) {
    if (await refreshAccessToken()) response = await doFetch();
  }
  if (response.status === 401) throw new UnauthorizedError();
  if (!response.ok || !response.body) {
    let err = response.statusText;
    try { err = (await response.json()).error ?? err; } catch { /* ignore */ }
    throw new Error(err);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let finalPayload: T | null = null;
  let errorMessage: string | null = null;

  // SSE frames are CRLF/LF-terminated lines; collect lines into a `pending` event until
  // we hit a blank line — that's the frame boundary.
  /**
   * 【SSE 帧解析状态】
   * evName：当前事件名称（默认 'message'）
   * dataLines：当前事件的数据行（一个事件可以有多行 data）
   * 空行标志着一个完整事件帧的结束，触发 dispatch。
   */
  let evName = 'message';
  let dataLines: string[] = [];

  /**
   * 【事件分发函数】
   * 将收集到的数据行合并，根据事件名称分别处理：
   * - token：解析 JSON 字符串并通过 onToken 回调传递给调用方
   * - done：解析 JSON 对象作为最终结果
   * - error：解析错误消息用于后续抛出
   */
  const dispatch = () => {
    if (dataLines.length === 0) return;
    const raw = dataLines.join('\n');
    dataLines = [];
    if (evName === 'token') {
      try { onToken(JSON.parse(raw) as string); } catch { /* skip malformed */ }
    } else if (evName === 'done') {
      try { finalPayload = JSON.parse(raw) as T; } catch { /* malformed; fail at end */ }
    } else if (evName === 'error') {
      try { errorMessage = JSON.parse(raw) as string; } catch { errorMessage = raw; }
    }
    evName = 'message';
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let nl: number;
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl).replace(/\r$/, '');
      buffer = buffer.slice(nl + 1);
      if (line === '') {
        dispatch();
        continue;
      }
      if (line.startsWith('event:')) evName = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
      // comment / heartbeat lines start with ':' — ignore
    }
  }
  // Flush trailing event if the server didn't end with a blank line.
  dispatch();

  if (errorMessage) throw new Error(errorMessage);
  if (!finalPayload) throw new Error('Stream ended without a done event');
  return finalPayload;
}

/**
 * 【API 方法集合】
 * 导出的 api 对象包含所有前端与后端交互的方法，按业务模块组织：
 * - 认证：captcha、login、register、logout、refreshToken、changePassword
 * - 用户：me、updateMe、uploadAvatar
 * - 任务：tasks、createTask、updateTask、deleteTask、startTask、pauseTask、resumeTask、submitTask
 * - AI：decompose、dailyReview、dailyReviewStream、answerAiQuestion、aiInfo
 * - 会话：sessionStart、sessionGet、sessionPostMessage、sessionCommit、sessionAbandon 等
 * - 目标：sessionActiveGoals、sessionAllGoals、updateGoal、deleteGoal、goalDetail
 * - 宠物：pet、feedPet、petLogs、setPetName、setPetAvatar
 * - 统计：summary
 * - 专注：focusSessions、activeFocus、startFocus、pauseFocus、resumeFocus、finishFocus
 * - 管理：adminSubmissions、approveSubmission、rejectSubmission 等
 * - 通知：notifications、notificationUnreadCount、markNotificationRead、markAllNotificationsRead
 */
export const api = {
  /** 【获取图形验证码】 */
  captcha: () => request<{ id: string; image: string }>('/api/auth/captcha'),
  /** 【用户登录】 */
  login: (username: string, password: string, captchaId: string, captchaCode: string) =>
    request<{ token: string; user: unknown }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password, captchaId, captchaCode })
    }),
  /** 【用户注册】 */
  register: (username: string, password: string, confirmPassword: string, nickname: string, captchaId: string, captchaCode: string) =>
    request<{ token: string; user: unknown }>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, confirmPassword, nickname, captchaId, captchaCode })
    }),
  /** 【管理员创建用户】 */
  adminCreateUser: (username: string, password: string, nickname: string, role: 'USER' | 'ADMIN') =>
    request<User>('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify({ username, password, nickname, role })
    }),
  /** 【退出登录】 */
  logout: () => request<{ ok: boolean }>('/api/auth/logout', { method: 'POST' }),
  /** 【退出所有设备】 */
  logoutAll: () => request<{ ok: boolean }>('/api/auth/logout-all', { method: 'POST' }),
  // Rotates the refresh-token cookie and returns a fresh access token. Usually NOT
  // called directly — the request() interceptor calls /api/auth/refresh-token
  // automatically on 401 and retries the original request.
  /**
   * 【刷新访问令牌】
   * 通常不直接调用，request() 拦截器会在收到 401 时自动调用并重试原请求。
   */
  refreshToken: () => request<{ token: string; user: unknown }>('/api/auth/refresh-token', { method: 'POST' }),
  /** 【修改密码】 */
  changePassword: (currentPassword: string, newPassword: string) => request<{ token: string; user: unknown }>('/api/auth/password', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword })
  }),
  /** 【获取当前用户信息】 */
  me: () => request('/api/users/me'),
  /** 【更新个人资料】 */
  updateMe: (profile: Partial<Pick<User, 'nickname' | 'email'>>) => request<User>('/api/users/me', {
    method: 'PUT',
    body: JSON.stringify(profile)
  }),
  /** 【获取所有任务列表】 */
  tasks: () => request<StudyTask[]>('/api/tasks'),
  /** 【创建新任务】 */
  createTask: (task: Partial<StudyTask>) => request<StudyTask>('/api/tasks', {
    method: 'POST',
    body: JSON.stringify(task)
  }),
  /** 【更新任务信息】 */
  updateTask: (id: number, task: Partial<StudyTask>) => request<StudyTask>(`/api/tasks/${id}`, {
    method: 'PUT',
    body: JSON.stringify(task)
  }),
  /** 【删除任务】 */
  deleteTask: (id: number) => request(`/api/tasks/${id}`, { method: 'DELETE' }),
  /** 【开始任务（TODO → DOING）】 */
  startTask: (id: number) => request<StudyTask>(`/api/tasks/${id}/start`, { method: 'POST' }),
  /** 【暂停任务（DOING → PAUSED）】 */
  pauseTask: (id: number) => request<StudyTask>(`/api/tasks/${id}/pause`, { method: 'POST' }),
  /** 【恢复任务（PAUSED → DOING）】 */
  resumeTask: (id: number) => request<StudyTask>(`/api/tasks/${id}/resume`, { method: 'POST' }),
  /** 【提交任务凭证，触发 AI 审核流程】 */
  submitTask: (id: number, body: Record<string, unknown>) => request<{ task: unknown; submission: { id: number }; review: unknown; aiQuestion: string }>(`/api/tasks/${id}/submit`, {
    method: 'POST',
    body: JSON.stringify(body)
  }),
  /** 【回答 AI 审核追问，可获得额外经验值奖励】 */
  answerAiQuestion: (submissionId: number, answer: string) => request<{ bonusExp: number; feedback: string }>('/api/ai/question/answer', {
    method: 'POST',
    body: JSON.stringify({ submissionId, answer })
  }),
  /** 【获取当前用户的提交记录列表】 */
  mySubmissions: () => request('/api/submissions/my'),
  /** 【获取提交详情（含审核报告）】 */
  submissionDetail: (id: number) => request<SubmissionDetail>(`/api/submissions/${id}`),
  /** 【上传截图文件，返回可访问的 URL】 */
  uploadScreenshot: (file: File, onProgress?: (pct: number) => void) => {
    const formData = new FormData();
    formData.append('file', file);
    return upload<{ url: string }>('/api/files/screenshots', formData, onProgress);
  },
  /** 【上传用户头像】 */
  uploadAvatar: (file: File, onProgress?: (pct: number) => void) => {
    const formData = new FormData();
    formData.append('file', file);
    return upload<User>('/api/users/avatar', formData, onProgress);
  },
  /** 【设置宠物头像（URL 或 null 清除）】 */
  setPetAvatar: (avatarUrl: string | null) => request('/api/pet/avatar', {
    method: 'POST',
    body: JSON.stringify({ avatarUrl })
  }),
  /** 【修改宠物名字】 */
  setPetName: (name: string) => request<Pet>('/api/pet', {
    method: 'PATCH',
    body: JSON.stringify({ name })
  }),
  /** 【获取 AI 服务信息（提供商、可用状态、模型名称）】 */
  aiInfo: () => request<{ provider: string; available: string; model: string }>('/api/ai/info'),
  /** 【AI 目标拆解：将自然语言目标分解为可执行任务列表】 */
  decompose: (goal: string) => request<{ tasks: Partial<StudyTask>[] }>('/api/ai/decompose', {
    method: 'POST',
    body: JSON.stringify({ goal })
  }),
  /** 【获取 AI 每日复盘报告（非流式）】 */
  dailyReview: () => request<DailyReview>('/api/ai/daily-review', { method: 'POST' }),
  /**
   * Streaming daily review. Narrative tokens stream through onToken; the final structured
   * body lands when the returned promise resolves. Falls back to non-streaming on error
   * is the caller's responsibility.
   *
   * 【流式每日复盘】
   * 叙事性文本通过 onToken 实时流式传输，最终结构化数据在 Promise resolve 时返回。
   * 调用方需自行处理流式失败时回退到非流式接口的逻辑。
   */
  dailyReviewStream: (onToken: (chunk: string) => void): Promise<DailyReview> =>
    streamSse<DailyReview>('/api/ai/daily-review/stream', {}, onToken),
  /** 【获取有活跃会话的目标列表】 */
  sessionActiveGoals: () => request<GoalSummary[]>('/api/ai/sessions/active-goals'),
  /** 【获取所有目标列表】 */
  sessionAllGoals: () => request<GoalSummary[]>('/api/ai/sessions/goals'),
  /** 【开启新目标拆解会话】 */
  sessionStartNewGoal: (goal: string) => request<SessionView>('/api/ai/sessions/new-goal', {
    method: 'POST', body: JSON.stringify({ goal })
  }),
  /** 【开启目标推进会话（Check-in）】 */
  sessionStartCheckIn: (goalId: number) => request<SessionView>('/api/ai/sessions/check-in', {
    method: 'POST', body: JSON.stringify({ goalId })
  }),
  /** 【获取会话详情】 */
  sessionGet: (id: number) => request<SessionView>(`/api/ai/sessions/${id}`),
  /** 【向会话发送消息（非流式）】 */
  sessionPostMessage: (id: number, content: string) => request<SessionView>(`/api/ai/sessions/${id}/messages`, {
    method: 'POST', body: JSON.stringify({ content })
  }),
  /**
   * Streaming variant. Calls the SSE endpoint with fetch+ReadableStream (EventSource can't
   * POST a body) and dispatches incremental tokens to onToken. The server emits exactly
   * one `done` event with the final SessionView, which resolves the returned promise.
   * Falls back to caller-handled error if anything blows up mid-stream.
   *
   * 【流式会话消息】
   * 通过 SSE 流式传输 AI 回复的增量文本，服务端最终发送一个 done 事件
   * 携带完整的 SessionView 数据。如果流式过程中出错，由调用方处理降级。
   */
  sessionPostMessageStream: (
    id: number,
    content: string,
    onToken: (chunk: string) => void
  ): Promise<SessionView> => streamSse<SessionView>(`/api/ai/sessions/${id}/messages/stream`, { content }, onToken),
  /** 【编辑计划草案中的单个任务】 */
  sessionEditPlanTask: (id: number, index: number, patch: {
    title?: string;
    description?: string;
    estimatedMinutes?: number;
    difficulty?: string;
    taskType?: string;
    baseExp?: number;
  }) => request<SessionView>(`/api/ai/sessions/${id}/plan/tasks/${index}`, {
    method: 'PATCH', body: JSON.stringify(patch)
  }),
  /** 【删除计划草案中的单个任务】 */
  sessionDeletePlanTask: (id: number, index: number) => request<SessionView>(`/api/ai/sessions/${id}/plan/tasks/${index}`, {
    method: 'DELETE'
  }),
  /** 【确认计划草案，将待定任务落地为真实任务并更新目标记忆】 */
  sessionCommit: (id: number) => request<SessionView>(`/api/ai/sessions/${id}/commit`, {
    method: 'POST'
  }),
  /** 【放弃当前会话】 */
  sessionAbandon: (id: number) => request<SessionView>(`/api/ai/sessions/${id}/abandon`, { method: 'POST' }),
  /** 【更新目标信息（标题、日期、状态等）】 */
  updateGoal: (id: number, patch: { title?: string; targetDate?: string | null; status?: GoalSummary['status']; clearTargetDate?: boolean }) =>
    request<{ id: number; title: string; status: GoalSummary['status']; targetDate?: string | null }>(`/api/goals/${id}`, {
      method: 'PATCH', body: JSON.stringify(patch)
    }),
  /** 【删除目标，同时解绑关联任务和关闭会话】 */
  deleteGoal: (id: number) => request<{ id: number; unboundTasks: number; closedSessions: number }>(`/api/goals/${id}`, {
    method: 'DELETE'
  }),
  /** 【获取目标详情（含蒸馏记忆和关联任务）】 */
  goalDetail: (id: number) => request<GoalDetail>(`/api/goals/${id}`),
  /** 【获取指定目标的所有会话列表】 */
  sessionListForGoal: (goalId: number) => request<SessionSummary[]>(`/api/ai/sessions?goalId=${goalId}`),
  /** 【删除会话】 */
  deleteSession: (id: number) => request<{ id: number; deletedTurns: number }>(`/api/ai/sessions/${id}`, { method: 'DELETE' }),
  /** 【获取宠物信息】 */
  pet: () => request<Pet>('/api/pet'),
  /** 【喂食宠物（增加饱食度）】 */
  feedPet: () => request('/api/pet/feed', { method: 'POST' }),
  /** 【获取宠物经验值变动日志】 */
  petLogs: () => request<ExpLog[]>('/api/pet/logs'),
  /** 【获取学习统计摘要】 */
  summary: () => request<Summary>('/api/stats/summary'),
  /** 【创建申诉（对 AI 审核结果不满意时发起）】 */
  createAppeal: (submissionId: number, appealReason: string, screenshotUrls: string[] = []) => request('/api/appeals', {
    method: 'POST',
    body: JSON.stringify({ submissionId, appealReason, screenshotUrls })
  }),
  /** 【管理员获取待审核提交列表】 */
  adminSubmissions: (scope: 'todo' | 'all' = 'todo') => request(`/api/admin/submissions?scope=${scope}`),
  /** 【管理员获取提交详情】 */
  adminSubmission: (id: number) => request(`/api/admin/submissions/${id}`),
  /** 【管理员批准提交（设置经验值和评语）】 */
  approveSubmission: (id: number, expAmount: number, comment: string) => request(`/api/admin/submissions/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({ expAmount, comment })
  }),
  /** 【管理员驳回提交】 */
  rejectSubmission: (id: number, comment: string) => request(`/api/admin/submissions/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ comment })
  }),
  /** 【管理员要求补充材料】 */
  needMoreSubmission: (id: number, comment: string) => request(`/api/admin/submissions/${id}/need-more`, {
    method: 'POST',
    body: JSON.stringify({ comment })
  }),
  /** 【获取专注会话历史列表】 */
  focusSessions: () => request<FocusSession[]>('/api/focus/sessions'),
  /** 【获取当前活跃的专注会话（如无则返回空对象）】 */
  activeFocus: () => request<FocusSession | Record<string, never>>('/api/focus/active'),
  /** 【开始新的专注计时会话】 */
  startFocus: (title: string, plannedMinutes: number, taskId?: number | null) => request<FocusSession>('/api/focus/sessions', {
    method: 'POST',
    body: JSON.stringify({ title, plannedMinutes, taskId: taskId ?? null })
  }),
  /** 【暂停专注计时】 */
  pauseFocus: (id: number) => request<FocusSession>(`/api/focus/sessions/${id}/pause`, { method: 'POST' }),
  /** 【恢复专注计时】 */
  resumeFocus: (id: number) => request<FocusSession>(`/api/focus/sessions/${id}/resume`, { method: 'POST' }),
  /** 【结束专注计时（完成或中断）】 */
  finishFocus: (id: number, outcome: 'completed' | 'aborted') => request<FocusSession>(`/api/focus/sessions/${id}/finish`, {
    method: 'POST',
    body: JSON.stringify({ outcome })
  }),
  /** 【管理员获取待审核申诉列表】 */
  adminAppeals: (scope: 'todo' | 'all' = 'todo') => request(`/api/admin/appeals?scope=${scope}`),
  /** 【管理员获取系统指标快照】 */
  adminMetrics: () => request<MetricsSnapshot>(`/api/admin/metrics`),
  /** 【管理员审核申诉】 */
  reviewAppeal: (id: number, status: string, comment: string, expAmount?: number) => request(`/api/admin/appeals/${id}/review`, {
    method: 'POST',
    body: JSON.stringify({ status, comment, expAmount })
  }),
  /** 【获取通知列表（支持分页和未读过滤）】 */
  notifications: (onlyUnread = false, page = 0, size = 20) =>
    request<{
      items: NotificationItem[];
      page: number; size: number; totalElements: number; totalPages: number;
      unreadCount: number;
    }>(`/api/notifications?onlyUnread=${onlyUnread}&page=${page}&size=${size}`),
  /** 【获取未读通知数量】 */
  notificationUnreadCount: () => request<{ unreadCount: number }>('/api/notifications/unread-count'),
  /** 【标记单条通知为已读】 */
  markNotificationRead: (id: number) => request<NotificationItem>(`/api/notifications/${id}/read`, { method: 'PATCH' }),
  /** 【标记所有通知为已读】 */
  markAllNotificationsRead: () => request<{ ok: boolean; marked: number }>('/api/notifications/read-all', { method: 'POST' }),

  // ===== 课表 Timetable =====
  /** 【获取当前用户课表，可选 semester 过滤】 */
  timetable: (semester?: string) =>
    request<CourseEntry[]>(`/api/timetable${semester ? `?semester=${encodeURIComponent(semester)}` : ''}`),
  /** 【导入课表：粘贴教务系统 HTML，AI 解析后落库】 */
  importTimetable: (html: string, semester?: string, replace = true) =>
    request<TimetableImportResult>('/api/timetable/import', {
      method: 'POST',
      body: JSON.stringify({ html, semester: semester || null, replace })
    }),
  /** 【手动新增一节课（不走 LLM）】 */
  createCourse: (body: CourseCreateInput) =>
    request<CourseEntry>('/api/timetable', {
      method: 'POST',
      body: JSON.stringify(body)
    }),
  /** 【删除一条课程】 */
  deleteCourse: (id: number) => request<{ deleted: boolean; id: number }>(`/api/timetable/${id}`, { method: 'DELETE' }),
  /** 【清空课表，可选 semester 只清某学期】 */
  clearTimetable: (semester?: string) =>
    request<{ cleared: number }>(`/api/timetable${semester ? `?semester=${encodeURIComponent(semester)}` : ''}`, { method: 'DELETE' })
};

/**
 * 【通知项类型】
 * 描述一条通知消息的完整结构，包含类型、标题、正文、
 * 关联引用（refType + refId）、已读状态和创建时间。
 * 用于通知铃铛下拉列表和 SSE 推送数据解析。
 */
export type NotificationItem = {
  id: number;
  type: string;
  title: string;
  body: string;
  refType: string | null;
  refId: number | null;
  readAt: string | null;
  createdAt: string;
};
