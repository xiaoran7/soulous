/** 【用户角色类型：USER 为普通用户，ADMIN 为管理员】 */
export type UserRole = 'USER' | 'ADMIN';
/** 【任务状态枚举：覆盖从创建到完成的完整生命周期，包括 AI 审核、人工审核、申诉等环节】 */
export type TaskStatus = 'TODO' | 'DOING' | 'PAUSED' | 'SUBMITTED' | 'AI_REVIEWING' | 'AI_APPROVED' | 'AI_REJECTED' | 'NEED_MORE' | 'APPEALING' | 'MANUAL_APPROVED' | 'MANUAL_REJECTED' | 'COMPLETED' | 'MODERATION_BLOCKED';
/** 【任务类型：区分学习、编码、笔记、记忆、复习、简单任务等不同性质】 */
export type TaskType = 'SIMPLE' | 'STUDY' | 'CODING' | 'NOTE' | 'MEMORY' | 'REVIEW';
/** 【难度等级：从简单到挑战，影响任务基础经验值和宠物成长速度】 */
export type Difficulty = 'EASY' | 'NORMAL' | 'HARD' | 'CHALLENGE';

/**
 * 【用户信息接口】
 * 描述用户的基本资料，包含头像 URL、昵称、邮箱和角色权限。
 * 用于登录后全局状态存储、顶部导航栏展示、个人资料页编辑等场景。
 */
export interface User {
  id: number;
  username: string;
  nickname: string;
  email: string;
  avatarUrl: string;
  role: UserRole;
}

/**
 * 【学习任务接口】
 * 核心业务实体，描述一个完整的学习任务，包含类型、难度、课程名、
 * 预估/实际用时、基础经验值、截止日期及完整的状态时间线。
 * 前端任务列表、任务详情、提交凭证等页面均依赖此类型。
 */
export interface StudyTask {
  id: number;
  title: string;
  description: string;
  taskType: TaskType;
  difficulty: Difficulty;
  courseName: string;
  /** 【大分类：更高一层的主题分组，与 AI 拆解的对话分类共用命名。可空】 */
  category?: string | null;
  estimatedMinutes: number;
  actualMinutes: number;
  baseExp: number;
  deadline?: string;
  status: TaskStatus;
  createdAt: string;
  startedAt?: string;
  submittedAt?: string;
  completedAt?: string;
  /** 【建议安排在周几（1=周一 … 7=周日），可选。AI 按课表排课的任务携带】 */
  scheduledWeekday?: number;
}

/**
 * 【课表条目接口】CourseEntry
 * 用户从教务系统导入、经 AI 解析的一节课。对应后端 /api/timetable 返回的视图。
 */
export interface CourseEntry {
  id: number;
  courseName: string;
  teacher?: string | null;
  location?: string | null;
  /** 星期几 1-7，周一=1 */
  dayOfWeek: number;
  startSection?: number | null;
  endSection?: number | null;
  /** "HH:mm" */
  startTime?: string | null;
  endTime?: string | null;
  /** 开课周次原文，如 "1-16" */
  weeks?: string | null;
  /** ALL / ODD / EVEN */
  weekParity?: string | null;
  semester?: string | null;
}

/** 【课表同步结果】 */
export interface TimetableSyncResult {
  count: number;
  semester: string;
  weekStart: string;
  courses: CourseEntry[];
}

/**
 * 【手动新增课程的请求体】CourseCreateInput
 * 用户在课表网格上手工补一节课（临时调课、活动占用时段）。
 * courseName 与 dayOfWeek 必填，其余可空，字段对齐后端 CourseCreateRequest。
 */
export interface CourseCreateInput {
  courseName: string;
  /** 星期几 1-7，周一=1 */
  dayOfWeek: number;
  teacher?: string | null;
  location?: string | null;
  startSection?: number | null;
  endSection?: number | null;
  startTime?: string | null;
  endTime?: string | null;
  weeks?: string | null;
  weekParity?: string | null;
  semester?: string | null;
}

/**
 * 【宠物信息接口】
 * 描述用户成长宠物的完整状态，包括等级、经验值、心情值、饱食度、
 * 成长阶段和当前情绪状态。宠物状态会随任务完成情况动态变化。
 */
export interface Pet {
  id: number;
  name: string;
  avatarUrl?: string;
  level: number;
  currentExp: number;
  nextLevelExp: number;
  mood: number;
  satiety: number;
  growthStage: string;
  status: string;
}

/**
 * 【经验值变动日志接口】
 * 记录每次经验值变动的详情，包括变动原因、变动数量、事件类型，
 * 以及关联的任务信息（如果有的话）。用于经验值趋势图和日志列表展示。
 */
export interface ExpLog {
  id: number;
  expAmount: number;
  eventType?: string;
  reason: string;
  createdAt: string;
  task?: Pick<StudyTask, 'id' | 'title' | 'courseName'>;
}

/**
 * 【学习统计摘要接口】
 * 汇总今日及近期的学习数据，包括任务完成数、提交数、经验值、
 * 学习/专注分钟数、完成率、AI 审核通过率、连续打卡天数等，
 * 以及按日期的趋势数据和按课程的分布数据。
 * 用于 Dashboard 首页和统计页面的数据展示。
 */
export interface Summary {
  todayTasks: number;
  todaySubmissions: number;
  todayExp: number;
  todayMinutes: number;
  todayFocusMinutes: number;
  todayFocusSessions: number;
  completionRate: number;
  approvedCount: number;
  rejectedCount: number;
  aiApprovalRate: number;
  consecutiveDays: number;
  trend: { date: string; minutes: number; focusMinutes: number }[];
  courses: Record<string, number>;
}

/**
 * 【AI 每日复盘接口】
 * AI 生成的每日学习复盘报告，包含总结、亮点、风险提示、明日建议、
 * 宠物寄语以及关键指标统计。由后端 AI 服务生成，前端直接渲染。
 */
export interface DailyReview {
  date: string;
  title: string;
  summary: string;
  highlights: string[];
  risks: string[];
  tomorrowSuggestions: string[];
  petMessage: string;
  metrics: {
    completedTasks: number;
    submissions: number;
    studyMinutes: number;
    earnedExp: number;
    petLevel: number;
    petStatus: string;
  };
}

/**
 * 【学习凭证/提交记录接口】
 * 用户完成任务后提交的学习凭证，包含文本证明、截图 URL（单张/多张）、
 * 代码片段、外部链接等。每条提交会经过 AI 审核，可能需要人工复核。
 */
export interface Submission {
  id: number;
  textProof: string;
  screenshotUrl: string;
  screenshotUrls?: string;
  codeSnippet: string;
  proofLink: string;
  studyMinutes: number;
  submitType: string;
  status: string;
  adminComment?: string;
  moderationReason?: string;
  createdAt: string;
  task?: StudyTask;
  user?: User;
}

/**
 * 【RAG 检索命中结果接口】
 * 来自向量检索（Retrieval-Augmented Generation）的匹配结果，
 * 包含来源类型（目标记忆/会话摘要/已完成任务/每日复盘）、
 * 相似度分数和文本片段，用于 AI 审核时提供上下文参考。
 */
export interface RagHit {
  sourceType: 'GOAL_MEMORY' | 'SESSION_SUMMARY' | 'COMPLETED_TASK' | 'DAILY_REVIEW';
  sourceId: number | null;
  similarity: number;
  snippet: string;
  label: string;
}

/**
 * 【AI 审核报告接口】
 * AI 对用户提交的学习凭证进行自动审核的结果，包含综合评分、
 * 相关性/完整性/质量三个维度的子分数、审核建议、推荐经验值，
 * 以及 RAG 检索命中的参考片段。
 */
export interface AiReview {
  id: number;
  result: string;
  score: number;
  relevanceScore: number;
  completenessScore: number;
  qualityScore: number;
  reason: string;
  suggestion: string;
  recommendedExp: number;
  ragHits?: RagHit[];
}

/**
 * 【提交详情接口（含审核和任务信息）】
 * 将提交记录、AI 审核报告和关联任务组合在一起，
 * 用于提交详情页的完整数据展示。
 */
export interface SubmissionDetail {
  submission: Submission;
  review?: AiReview | null;
  task: StudyTask;
}

/** 【专注会话状态：运行中、已暂停、已完成、已中断】 */
export type FocusStatus = 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'ABORTED';

/**
 * 【专注计时会话接口】
 * 记录一次专注计时的完整数据，包括计划时长、已用秒数、
 * 关联任务 ID、开始/暂停/结束时间等。
 * 用于专注页面的状态管理和历史记录展示。
 */
export interface FocusSession {
  id: number;
  title: string;
  plannedMinutes: number;
  elapsedSeconds: number;
  status: FocusStatus;
  taskId: number | null;
  startedAt: string;
  lastStartedAt: string | null;
  endedAt: string | null;
  createdAt: string;
}

/** 【聊天消息角色：USER 为用户，ASSISTANT 为 AI 助手】 */
export type ChatRole = 'USER' | 'ASSISTANT';

/**
 * 【聊天分类接口】用户自建的对话文件夹（类似 Gemini 分组）。
 */
export interface ChatCategory {
  id: number;
  name: string;
}

/**
 * 【聊天对话摘要接口】侧边栏列表项。categoryId 为 null 表示未分类（默认组）。
 */
export interface ChatConversationSummary {
  id: number;
  title: string;
  categoryId: number | null;
  lastActivityAt: string;
  messageCount: number;
}

/**
 * 【侧边栏树接口】全部分类 + 全部对话摘要（前端据 categoryId 分组渲染）。
 */
export interface ChatTree {
  categories: ChatCategory[];
  conversations: ChatConversationSummary[];
}

/**
 * 【聊天消息视图接口】对话中的一条发言。
 */
export interface ChatMessageView {
  id: number;
  idx: number;
  role: ChatRole;
  content: string;
}

/**
 * 【待确认计划任务接口】
 * AI 拆解目标后生成的计划草案中的单个任务项，
 * 包含标题、描述、预估时长、难度和任务类型。
 * 用户可以在确认前编辑或删除这些待定任务。
 */
export interface PendingPlanTask {
  title: string;
  description?: string;
  estimatedMinutes?: number;
  difficulty?: Difficulty;
  taskType?: TaskType;
}

/**
 * 【待确认计划接口】
 * AI 在拆解会话中生成的完整计划草案，包含目标标题、
 * 任务列表和目标状态变更建议。用户确认后才会落地为真实任务。
 */
export interface PendingPlan {
  goalTitle?: string;
  /** AI 为这组任务起的「大类」归类名；落地任务时自动写入 task.category，用户无需手动建分类。 */
  category?: string;
  tasks?: PendingPlanTask[];
  goalStatusChange?: string | null;
}

/**
 * 【澄清选项接口】
 * 结构化澄清问题中的单个可选项，前端渲染为可点选的按钮/标签。
 */
export interface PendingClarifyOption {
  label: string;
  hint?: string;
}

/**
 * 【澄清问题接口】
 * AI 拆解过程中需要补充信息时给出的一道结构化选择题，
 * 前端据此渲染选项卡片，用户点选后把选择回灌成一条消息继续对话，
 * 替代以往纯文字一问一答的体验。
 */
export interface PendingClarifyQuestion {
  id?: string;
  question: string;
  multiSelect?: boolean;
  options: PendingClarifyOption[];
}

/**
 * 【待回答澄清接口】
 * AI 在拆解会话中提出的一组结构化澄清问题。计划已提出（PLAN_PROPOSED）时后端不会下发。
 */
export interface PendingClarify {
  questions: PendingClarifyQuestion[];
}

/**
 * 【聊天对话完整视图接口】
 * 一段对话的完整状态：标题、所属分类、全部消息、待确认计划草案、
 * 待回答的结构化澄清问题、当前可用操作。是 ChatConversation 组件的核心数据源。
 */
export interface ChatConversationView {
  id: number;
  title: string;
  categoryId: number | null;
  messages: ChatMessageView[];
  pendingPlan: PendingPlan | null;
  /** Structured clarifying questions to render as selectable cards; null once a plan is proposed. */
  pendingClarify?: PendingClarify | null;
  suggestedActions: string[];
}

/**
 * 【申诉记录接口】
 * 用户对 AI 审核结果不满意时发起的申诉，包含申诉理由、
 * 补充截图、当前状态和管理员评论。
 */
export interface Appeal {
  id: number;
  appealReason: string;
  screenshotUrls?: string;
  status: string;
  adminComment: string;
  createdAt: string;
  submission?: Submission;
}

/**
 * 【标签计数接口】
 * 通用的标签-计数对，用于管理后台指标面板中
 * 按维度聚合的统计数据展示（如按 LLM 提供商、按审核结论等）。
 */
export interface TagCount {
  label: string;
  count: number;
}

/**
 * 【管理后台指标快照接口】
 * 系统级运维指标，包含服务运行时长、LLM 调用统计、
 * 限流拦截统计、内容审核统计、存储 GC 和通知推送等数据。
 * 仅管理员可见。
 */
export interface MetricsSnapshot {
  uptimeSeconds: number;
  llm: {
    total: number;
    success: number;
    failure: number;
    byProvider: TagCount[];
  };
  rateLimitBlockedByRule: TagCount[];
  moderationByVerdict: TagCount[];
  storageGcDeleted: number;
  refreshTokenReplayed: number;
  notificationsPushedByType: TagCount[];
}

