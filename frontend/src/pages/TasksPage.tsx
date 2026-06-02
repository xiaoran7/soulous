/**
 * 【任务页面】TasksPage —— 重构版（列表 / 详情 双视图）
 *
 * 旧版把一个任务的生命周期拆成了 4 个并列 tab（我的任务 / 创建 / 提交 / 提交与申诉），
 * 用户要在 tab 之间反复横跳、重复选任务才能走完一件事。本次重构改成
 * 「列表页 + 独立任务详情页」的心智模型：
 *
 *   - 列表页：只负责浏览与筛选。点任意任务卡片 → 进入该任务的详情页。
 *   - 详情页：这个任务的一切都在这里完成——开始 / 暂停 / 继续 / 提交凭证 /
 *             看 AI 反馈 / 申诉 / 编辑 / 删除。提交进度、AI 追问、审核分数、
 *             以及「这个任务的提交记录」全部内联，不再跨页。
 *   - 创建/编辑：作为一个独立的表单视图（从列表的「＋ 新建」或详情的「编辑」进入），
 *             而非常驻导航项。
 *
 * 视图路由用组件内部状态实现（与全站手写 page 路由一致，不引入 react-router）：
 *   formMode != null  → 表单视图（创建/编辑）
 *   activeId  != null → 详情视图
 *   否则               → 列表视图
 *
 * 状态机：TODO -> DOING -> SUBMITTED -> AI_REVIEWING -> AI_APPROVED/AI_REJECTED
 * 特殊状态：PAUSED（暂停）、NEED_MORE（需要补充）、APPEALING（申诉中）
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  Edit3,
  FileText,
  Loader,
  Pause,
  Play,
  Plus,
  Save,
  Trash2
} from 'lucide-react';
import { api } from '../api';
import type { AiReview, StudyTask, Submission, SubmissionDetail, TaskStatus } from '../types';
import { Empty, TaskRow, statusLabel } from '../components/shared';
import { ProofUploader, ProofThumbStrip, parseScreenshotUrls, type ProofImage } from '../components/ProofUploader';
import { resetFocusCache } from './FocusPage';

/**
 * 【任务表单状态类型】定义创建/编辑任务时表单的数据结构
 */
type TaskFormState = {
  title: string;
  description: string;
  category: string;
  courseName: string;
  estimatedMinutes: number;
  baseExp: number;
  taskType: StudyTask['taskType'];
  difficulty: StudyTask['difficulty'];
  deadline: string;
};

/** 【空表单工厂函数】返回一个带默认值的空白表单状态 */
const emptyTaskForm = (): TaskFormState => ({
  title: '',
  description: '',
  category: '',
  courseName: '数据结构',
  estimatedMinutes: 30,
  baseExp: 20,
  taskType: 'STUDY',
  difficulty: 'NORMAL',
  deadline: ''
});

/**
 * 【任务类型选项】每种类型对应不同的颜色标识，方便用户在列表中快速识别
 */
const TASK_TYPE_OPTIONS: Array<{ value: StudyTask['taskType']; label: string; accent: string }> = [
  { value: 'STUDY',   label: '学习', accent: '#0d9488' },
  { value: 'CODING',  label: '编程', accent: '#7c3aed' },
  { value: 'NOTE',    label: '笔记', accent: '#2563eb' },
  { value: 'MEMORY',  label: '背诵', accent: '#d97706' },
  { value: 'REVIEW',  label: '复盘', accent: '#e11d48' },
  { value: 'SIMPLE',  label: '简单', accent: '#64748b' }
];

/** 【任务类型 → 中文标签】用于详情页元信息展示 */
const TASK_TYPE_LABEL: Record<string, string> = Object.fromEntries(
  TASK_TYPE_OPTIONS.map((o) => [o.value, o.label])
);

/** 【难度选项】影响任务的经验值加成和 AI 审核标准 */
const DIFFICULTY_OPTIONS: Array<{ value: StudyTask['difficulty']; label: string }> = [
  { value: 'EASY',      label: '简单' },
  { value: 'NORMAL',    label: '普通' },
  { value: 'HARD',      label: '困难' },
  { value: 'CHALLENGE', label: '挑战' }
];
const DIFFICULTY_LABEL: Record<string, string> = Object.fromEntries(
  DIFFICULTY_OPTIONS.map((o) => [o.value, o.label])
);

/** 【草稿存储键名】localStorage 中保存未提交表单数据的 key */
const DRAFT_KEY = 'soulous_task_draft_v1';

/** 【可提交状态集合】只有这些状态的任务才允许提交凭证 */
const SUBMITTABLE_STATUSES = new Set(['DOING', 'NEED_MORE', 'AI_REJECTED', 'MODERATION_BLOCKED']);
/** 【可编辑状态集合】只有这些状态的任务才允许修改基本信息 */
const EDITABLE_STATUSES = new Set(['TODO', 'NEED_MORE', 'AI_REJECTED', 'MODERATION_BLOCKED']);

/**
 * 【列表筛选分组】把 13 种细粒度状态收敛成 5 个用户视角的处理阶段，
 * 让筛选 chip 表达「我现在该看哪一类任务」，而不是逐个状态枚举。
 */
type ListFilter = 'all' | 'todo' | 'active' | 'review' | 'action' | 'done';
const STATUS_GROUP: Record<TaskStatus, Exclude<ListFilter, 'all'>> = {
  TODO: 'todo',
  DOING: 'active',
  PAUSED: 'active',
  SUBMITTED: 'review',
  AI_REVIEWING: 'review',
  APPEALING: 'review',
  NEED_MORE: 'action',
  AI_REJECTED: 'action',
  MODERATION_BLOCKED: 'action',
  MANUAL_REJECTED: 'action',
  AI_APPROVED: 'done',
  MANUAL_APPROVED: 'done',
  COMPLETED: 'done'
};
const FILTER_TABS: Array<{ key: ListFilter; label: string }> = [
  { key: 'all',    label: '全部' },
  { key: 'todo',   label: '待开始' },
  { key: 'active', label: '进行中' },
  { key: 'review', label: '审核中' },
  { key: 'action', label: '待处理' },
  { key: 'done',   label: '已完成' }
];

/**
 * 【列表卡片「下一步」提示】根据状态告诉用户点进去能做什么，
 * 替代旧版列表里一排状态相关的操作按钮——动作统一收进详情页。
 */
function nextStepHint(status: string): string {
  switch (status) {
    case 'TODO': return '去开始';
    case 'DOING': return '去提交';
    case 'PAUSED': return '已暂停';
    case 'SUBMITTED':
    case 'AI_REVIEWING': return '审核中';
    case 'APPEALING': return '申诉中';
    case 'NEED_MORE':
    case 'AI_REJECTED':
    case 'MODERATION_BLOCKED':
    case 'MANUAL_REJECTED': return '待处理';
    default: return '查看反馈';
  }
}

/**
 * 【提交阶段状态机】idle -> submitting -> ai_reviewing -> done / error
 */
type SubmitPhase = 'idle' | 'uploading' | 'submitting' | 'ai_reviewing' | 'done' | 'error';
const PHASE_LABELS: Record<SubmitPhase, string> = {
  idle: '',
  uploading: '正在上传凭证...',
  submitting: '正在提交任务...',
  ai_reviewing: 'AI 正在审核中，请稍候...',
  done: '审核完成！',
  error: '提交失败'
};
const PHASE_PROGRESS: Record<SubmitPhase, number> = {
  idle: 0,
  uploading: 15,
  submitting: 35,
  ai_reviewing: 65,
  done: 100,
  error: 0
};

/** 【任务转表单】用于编辑模式的数据回填 */
const taskToForm = (task: StudyTask): TaskFormState => ({
  title: task.title,
  description: task.description ?? '',
  category: task.category ?? '',
  courseName: task.courseName ?? '',
  estimatedMinutes: task.estimatedMinutes ?? 30,
  baseExp: task.baseExp ?? 20,
  taskType: task.taskType,
  difficulty: task.difficulty,
  deadline: task.deadline ?? ''
});

/** 【读取本地草稿】返回可用草稿或 null（损坏/空时） */
function readDraft(): Partial<TaskFormState> | null {
  try {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<TaskFormState>;
    if (parsed && typeof parsed === 'object' && (parsed.title || parsed.description)) return parsed;
  } catch { /* 忽略损坏的草稿数据 */ }
  return null;
}

/**
 * 【任务页面主组件】
 * @param tasks - 任务列表（从父组件传入）
 * @param onRefresh - 刷新任务列表的回调函数
 */
export function TasksPage({ tasks, onRefresh }: { tasks: StudyTask[]; onRefresh: () => void }) {
  /** 【当前详情页的任务 ID】null = 列表视图 */
  const [activeId, setActiveId] = useState<number | null>(null);
  /** 【表单模式】非 null 时显示创建/编辑表单视图，优先级高于详情 */
  const [formMode, setFormMode] = useState<null | 'create' | 'edit'>(null);

  const [listFilter, setListFilter] = useState<ListFilter>('all');
  const [courseFilter, setCourseFilter] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('');
  /** 【AI 拆解对话分类名】任务「大分类」的可复用候选，打通任务与 AI 拆解分类 */
  const [chatCategories, setChatCategories] = useState<string[]>([]);

  const [form, setForm] = useState<TaskFormState>(emptyTaskForm());
  /** 【确认删除的任务 ID】非 null 时显示二次确认按钮 */
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);

  /** 【文字凭证】用户描述学习过程和成果的文本 */
  const [proof, setProof] = useState('我完成了本次学习，整理了关键知识点和练习结果。');
  const [codeSnippet, setCodeSnippet] = useState('');
  const [proofLink, setProofLink] = useState('');
  const [proofImages, setProofImages] = useState<ProofImage[]>([]);
  const proofScopeRef = useRef<HTMLDivElement>(null);

  const [submissions, setSubmissions] = useState<Submission[]>([]);
  const [submissionDetail, setSubmissionDetail] = useState<SubmissionDetail | null>(null);
  const [aiQuestion, setAiQuestion] = useState<{ question: string; submissionId: number } | null>(null);
  const [aiAnswer, setAiAnswer] = useState('');
  const [aiAnswerFeedback, setAiAnswerFeedback] = useState<{ bonusExp: number; feedback: string } | null>(null);
  const [taskFormBusy, setTaskFormBusy] = useState(false);
  const [aiAnswering, setAiAnswering] = useState(false);
  const [appealingIds, setAppealingIds] = useState<Set<number>>(new Set());
  const [taskError, setTaskError] = useState('');
  const [loadingDetailId, setLoadingDetailId] = useState<number | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [draftStatus, setDraftStatus] = useState<string>('');

  const [submitPhase, setSubmitPhase] = useState<SubmitPhase>('idle');
  const [submitProgress, setSubmitProgress] = useState(0);
  const progressTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /** 【提交详情缓存】key 是 submissionId */
  const detailCache = useRef<Map<number, SubmissionDetail>>(new Map());

  /** 【当前视图】formMode 优先于 activeId，否则列表 */
  const view: 'list' | 'detail' | 'form' = formMode ? 'form' : activeId != null ? 'detail' : 'list';

  /** 【当前详情任务】从最新 tasks 里按 id 找，确保始终是最新引用 */
  const activeTask = useMemo(
    () => (activeId == null ? null : tasks.find((t) => t.id === activeId) ?? null),
    [tasks, activeId]
  );

  /** 【课程列表】从任务中提取不重复的课程名称，用于筛选器 */
  const courseList = useMemo(() => {
    const seen = new Set<string>();
    const out: string[] = [];
    tasks.forEach((t) => {
      const name = (t.courseName ?? '').trim();
      if (name && !seen.has(name)) { seen.add(name); out.push(name); }
    });
    return out;
  }, [tasks]);

  /** 【大分类列表】从任务中提取不重复的大分类名称，用于列表筛选器 */
  const categoryList = useMemo(() => {
    const seen = new Set<string>();
    const out: string[] = [];
    tasks.forEach((t) => {
      const name = (t.category ?? '').trim();
      if (name && !seen.has(name)) { seen.add(name); out.push(name); }
    });
    return out;
  }, [tasks]);

  /** 【大分类候选】合并任务大分类与 AI 拆解对话分类，去重，作为表单快捷选项 */
  const categorySuggestions = useMemo(() => {
    const seen = new Set<string>();
    const out: string[] = [];
    [...categoryList, ...chatCategories].forEach((name) => {
      const n = (name ?? '').trim();
      if (n && !seen.has(n)) { seen.add(n); out.push(n); }
    });
    return out;
  }, [categoryList, chatCategories]);

  /** 【最近课程】按创建时间倒序取前 6 个课程，用于表单快捷选择 */
  const recentCourses = useMemo(() => {
    const seen = new Set<string>();
    const out: string[] = [];
    [...tasks]
      .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
      .forEach((t) => {
        const name = (t.courseName ?? '').trim();
        if (name && !seen.has(name)) { seen.add(name); out.push(name); }
      });
    return out.slice(0, 6);
  }, [tasks]);

  /** 【各筛选分组的任务数】用于筛选 chip 上的计数 */
  const filterCounts = useMemo(() => {
    const counts: Record<ListFilter, number> = { all: tasks.length, todo: 0, active: 0, review: 0, action: 0, done: 0 };
    tasks.forEach((t) => { counts[STATUS_GROUP[t.status] ?? 'all']++; });
    return counts;
  }, [tasks]);

  /** 【筛选后的任务列表】按状态分组 + 大分类 + 课程过滤 */
  const filteredTasks = useMemo(() => {
    return tasks.filter((t) => {
      if (listFilter !== 'all' && STATUS_GROUP[t.status] !== listFilter) return false;
      if (categoryFilter && (t.category ?? '').trim() !== categoryFilter) return false;
      if (courseFilter && (t.courseName ?? '').trim() !== courseFilter) return false;
      return true;
    });
  }, [tasks, listFilter, categoryFilter, courseFilter]);

  /** 【这个任务的提交记录】详情页只展示当前任务的提交，按时间倒序（后端已排好） */
  const taskSubmissions = useMemo(
    () => submissions.filter((s) => s.task?.id === activeId),
    [submissions, activeId]
  );

  /** 【主题色】根据当前任务类型动态改变表单区域的强调色 */
  const accentColor = useMemo(
    () => TASK_TYPE_OPTIONS.find((o) => o.value === form.taskType)?.accent ?? '#0d9488',
    [form.taskType]
  );

  /** 【当前详情任务是否可提交凭证】 */
  const canSubmit = !!(activeTask && SUBMITTABLE_STATUSES.has(activeTask.status));
  /** 【是否为重新提交】需要补充或被拒绝的任务需要重新提交 */
  const isResubmit = !!(activeTask && (activeTask.status === 'NEED_MORE' || activeTask.status === 'AI_REJECTED'));

  /**
   * 【打开任务详情】进入某个任务的详情页，重置提交相关的临时状态
   */
  const openDetail = useCallback((id: number) => {
    setActiveId(id);
    setFormMode(null);
    setSubmissionDetail(null);
    setConfirmDeleteId(null);
    setTaskError('');
  }, []);

  /** 【返回列表】退出详情/表单视图 */
  const backToList = useCallback(() => {
    setActiveId(null);
    setFormMode(null);
    setSubmissionDetail(null);
    setTaskError('');
  }, []);

  /**
   * 【切换详情任务时重置凭证表单】避免上一个任务的草稿凭证、AI 追问、
   * 进度条状态泄漏到下一个任务。
   */
  useEffect(() => {
    setProof('我完成了本次学习，整理了关键知识点和练习结果。');
    setCodeSnippet('');
    setProofLink('');
    setProofImages([]);
    setAiQuestion(null);
    setAiAnswerFeedback(null);
    setSubmitPhase('idle');
    setSubmitProgress(0);
  }, [activeId]);

  /**
   * 【加载提交记录】从 API 获取用户的提交历史
   */
  const loadSubmissions = useCallback(async () => {
    try {
      const data = await api.mySubmissions();
      setSubmissions(Array.isArray(data) ? data as Submission[] : []);
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '加载提交记录失败');
    }
  }, []);

  useEffect(() => { void loadSubmissions(); }, []);

  /** 【加载 AI 拆解对话分类】作为任务「大分类」候选，失败静默 */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const tree = await api.chatTree();
        if (!cancelled) setChatCategories(tree.categories.map((c) => c.name));
      } catch { /* 候选项可选，失败忽略 */ }
    })();
    return () => { cancelled = true; };
  }, []);

  /**
   * 【SSE 通知监听】审核/申诉/拦截通知到达时自动刷新任务与提交记录，
   * 用户无需手动刷新即可看到最新结果。
   */
  useEffect(() => {
    const onPush = (ev: Event) => {
      const detail = (ev as CustomEvent).detail as { type?: string } | undefined;
      if (!detail) return;
      if (detail.type === 'AI_REVIEW_DONE' || detail.type === 'APPEAL_REVIEWED' || detail.type === 'MODERATION_BLOCKED') {
        void Promise.all([onRefresh(), loadSubmissions()]);
      }
    };
    window.addEventListener('soulous:notification', onPush);
    return () => window.removeEventListener('soulous:notification', onPush);
  }, [onRefresh, loadSubmissions]);

  /**
   * 【详情任务被删除时回退】若当前详情任务在最新列表里消失（被删/被筛掉），
   * 回到列表视图，避免详情页空白。
   */
  useEffect(() => {
    if (activeId != null && view === 'detail' && !activeTask) backToList();
  }, [activeId, view, activeTask, backToList]);

  /** 【启动进度动画】以随机速度递增进度条，营造"正在处理"的感觉 */
  function startProgressAnimation(targetPercent: number) {
    if (progressTimerRef.current) clearInterval(progressTimerRef.current);
    progressTimerRef.current = setInterval(() => {
      setSubmitProgress((prev) => {
        const next = prev + Math.random() * 3 + 1;
        if (next >= targetPercent - 2) {
          if (progressTimerRef.current) clearInterval(progressTimerRef.current);
          return targetPercent - 2;
        }
        return next;
      });
    }, 200);
  }

  function stopProgressAnimation() {
    if (progressTimerRef.current) {
      clearInterval(progressTimerRef.current);
      progressTimerRef.current = null;
    }
  }

  /** 【打开创建表单】载入本地草稿（若有），切到表单视图 */
  function openCreate() {
    const draft = readDraft();
    setForm(draft ? { ...emptyTaskForm(), ...draft } : emptyTaskForm());
    setDraftStatus(draft ? '已恢复上次未提交的草稿' : '');
    setShowAdvanced(false);
    setFormMode('create');
    setTaskError('');
  }

  /** 【保存草稿】将当前表单数据保存到 localStorage，2.4 秒后清除提示 */
  function saveDraft() {
    try {
      localStorage.setItem(DRAFT_KEY, JSON.stringify(form));
      setDraftStatus('草稿已保存到本地');
      window.setTimeout(() => setDraftStatus(''), 2400);
    } catch {
      setDraftStatus('草稿保存失败');
    }
  }

  /**
   * 【创建/更新任务】表单提交处理
   * - 编辑：updateTask，成功后留在该任务详情
   * - 创建：createTask，成功后清草稿并跳进新任务详情
   */
  async function createTask(event: React.FormEvent) {
    event.preventDefault();
    setTaskError('');
    setTaskFormBusy(true);
    try {
      if (formMode === 'edit' && activeId != null) {
        await api.updateTask(activeId, form);
        resetFocusCache();
        await onRefresh();
        setFormMode(null); // 回到该任务详情（activeId 不变）
      } else {
        const created = await api.createTask(form);
        try { localStorage.removeItem(DRAFT_KEY); } catch { /* ignore */ }
        setDraftStatus('');
        resetFocusCache();
        await onRefresh();
        openDetail(created.id); // 创建后直接进入新任务详情
      }
      setForm(emptyTaskForm());
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setTaskFormBusy(false);
    }
  }

  /**
   * 【进入编辑模式】将任务数据填充到表单，切换到表单视图
   */
  function editTask(task: StudyTask) {
    if (!EDITABLE_STATUSES.has(task.status)) {
      setTaskError('任务已开始，无法编辑。请先暂停或重新开始一个未启动的任务。');
      return;
    }
    setActiveId(task.id);
    setForm(taskToForm(task));
    setShowAdvanced(false);
    setFormMode('edit');
    setTaskError('');
  }

  /** 【删除任务】物理删除任务及关联记录，不可恢复 */
  async function deleteTask(id: number) {
    setConfirmDeleteId(null);
    setTaskError('');
    try {
      await api.deleteTask(id);
      if (activeId === id) backToList();
      await onRefresh();
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '删除失败');
    }
  }

  /** 【开始任务】TODO -> DOING */
  async function startTask(id: number) {
    setTaskError('');
    try { await api.startTask(id); await onRefresh(); }
    catch (err) { setTaskError(err instanceof Error ? err.message : '开始任务失败'); }
  }

  /** 【暂停任务】DOING -> PAUSED */
  async function pauseTask(id: number) {
    setTaskError('');
    try { await api.pauseTask(id); await onRefresh(); }
    catch (err) { setTaskError(err instanceof Error ? err.message : '暂停任务失败'); }
  }

  /** 【继续任务】PAUSED -> DOING */
  async function resumeTask(id: number) {
    setTaskError('');
    try { await api.resumeTask(id); await onRefresh(); }
    catch (err) { setTaskError(err instanceof Error ? err.message : '继续任务失败'); }
  }

  /**
   * 【提交任务凭证】对当前详情任务提交凭证，走进度动画 + AI 审核
   */
  async function submitTask() {
    if (!activeTask || !canSubmit) return;
    setTaskError('');
    setSubmitPhase('submitting');
    setSubmitProgress(PHASE_PROGRESS.submitting);
    startProgressAnimation(PHASE_PROGRESS.ai_reviewing);

    try {
      setSubmitPhase('ai_reviewing');
      setSubmitProgress(PHASE_PROGRESS.ai_reviewing);
      startProgressAnimation(PHASE_PROGRESS.done);

      const urls = proofImages.map((img) => img.url);
      const result = await api.submitTask(activeTask.id, {
        textProof: proof,
        studyMinutes: activeTask.estimatedMinutes,
        codeSnippet,
        proofLink,
        screenshotUrl: urls[0] ?? '',
        screenshotUrls: urls
      });

      stopProgressAnimation();
      setSubmitPhase('done');
      setSubmitProgress(100);

      setAiQuestion({ question: result.aiQuestion, submissionId: result.submission.id });
      setAiAnswer('');
      setAiAnswerFeedback(null);
      setProofImages([]);

      detailCache.current.clear();
      await onRefresh();
      await loadSubmissions();

      setTimeout(() => { setSubmitPhase('idle'); setSubmitProgress(0); }, 2000);
    } catch (err) {
      stopProgressAnimation();
      setSubmitPhase('error');
      setSubmitProgress(0);
      setTaskError(err instanceof Error ? err.message : '提交失败');
      setTimeout(() => setSubmitPhase('idle'), 3000);
    }
  }

  /** 【提交 AI 追问答案】回答后获得额外经验加成 */
  async function submitAiAnswer() {
    if (!aiQuestion) return;
    setAiAnswering(true);
    try {
      const result = await api.answerAiQuestion(aiQuestion.submissionId, aiAnswer);
      setAiAnswerFeedback(result);
      setAiQuestion(null);
      setAiAnswer('');
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '提交答案失败');
    } finally {
      setAiAnswering(false);
    }
  }

  /** 【发起申诉】对审核结果不满时提交申诉 */
  async function appeal(submissionId: number, reason: string, urls: string[]) {
    setAppealingIds((prev) => new Set(prev).add(submissionId));
    try {
      await api.createAppeal(submissionId, reason, urls);
      detailCache.current.delete(submissionId);
      await Promise.all([onRefresh(), loadSubmissions()]);
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '申诉失败');
    } finally {
      setAppealingIds((prev) => { const next = new Set(prev); next.delete(submissionId); return next; });
    }
  }

  /** 【打开提交详情】加载并展示审核反馈，带缓存 */
  async function openSubmissionDetail(submissionId: number) {
    const cached = detailCache.current.get(submissionId);
    if (cached) { setSubmissionDetail(cached); return; }
    setLoadingDetailId(submissionId);
    setTaskError('');
    try {
      const detail = await api.submissionDetail(submissionId);
      detailCache.current.set(submissionId, detail);
      setSubmissionDetail(detail);
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '加载反馈失败');
    } finally {
      setLoadingDetailId(null);
    }
  }

  /** 【获取状态提示】解释当前状态、引导下一步 */
  function getStatusHint(status: string): string | null {
    switch (status) {
      case 'TODO': return '任务尚未开始，点「开始任务」后即可提交凭证。';
      case 'PAUSED': return '任务已暂停，点「继续」后即可提交凭证。';
      case 'SUBMITTED': return '凭证已提交，正在等待审核结果。';
      case 'AI_REVIEWING': return 'AI 正在审核你的凭证，稍候会自动更新结果。';
      case 'APPEALING': return '正在申诉中，等待管理员处理。';
      case 'COMPLETED': return '此任务已完成。';
      case 'AI_APPROVED': return '已通过 AI 审核。';
      case 'MANUAL_APPROVED': return '管理员已通过。';
      case 'MANUAL_REJECTED': return '管理员已驳回，可在下方提交记录里发起申诉。';
      case 'MODERATION_BLOCKED': return '凭证被内容安全检查拦截，请修改后重新提交，或对该提交发起申诉。';
      default: return null;
    }
  }

  // ============================ 渲染 ============================

  // ---------- 表单视图（创建/编辑）----------
  if (view === 'form') {
    return (
      <div className="page-stack">
        {taskError && <div className="form-error">{taskError}</div>}
        <button type="button" className="back-link" onClick={() => (formMode === 'edit' ? setFormMode(null) : backToList())}>
          <ChevronLeft size={16} /> {formMode === 'edit' ? '返回任务详情' : '返回任务列表'}
        </button>
        <section className="panel form-panel" style={{ '--accent-color': accentColor } as React.CSSProperties}>
          <div className="panel-title"><h2>{formMode === 'edit' ? '编辑任务' : '创建任务'}</h2></div>
          <form className="stack-form compact" onSubmit={createTask}>
            <label className="field-label">
              <span>任务标题</span>
              <input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="例如：复习二叉树遍历" required />
            </label>
            <label className="field-label">
              <span>任务描述</span>
              <textarea rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="简单写一下要做什么" />
            </label>

            <div className="field-label">
              <span>大分类</span>
              <div className="chip-group">
                {categorySuggestions.map((name) => (
                  <button
                    key={name}
                    type="button"
                    className={`chip${form.category === name ? ' selected' : ''}`}
                    onClick={() => setForm({ ...form, category: form.category === name ? '' : name })}
                  >{name}</button>
                ))}
                <input
                  className="chip-input"
                  value={form.category}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}
                  placeholder={categorySuggestions.length ? '自定义大分类...' : '如 考研数学（关联 AI 拆解分类）'}
                />
              </div>
            </div>

            <div className="field-label">
              <span>课程</span>
              <div className="chip-group">
                {recentCourses.map((name) => (
                  <button
                    key={name}
                    type="button"
                    className={`chip${form.courseName === name ? ' selected' : ''}`}
                    onClick={() => setForm({ ...form, courseName: name })}
                  >{name}</button>
                ))}
                <input
                  className="chip-input"
                  value={form.courseName}
                  onChange={(e) => setForm({ ...form, courseName: e.target.value })}
                  placeholder={recentCourses.length ? '自定义...' : '如 数据结构'}
                />
              </div>
            </div>

            <div className="field-label">
              <span>类型</span>
              <div className="chip-group">
                {TASK_TYPE_OPTIONS.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    className={`chip${form.taskType === opt.value ? ' selected' : ''}`}
                    style={form.taskType === opt.value ? { borderColor: opt.accent, color: opt.accent, background: `${opt.accent}14` } : undefined}
                    onClick={() => setForm({ ...form, taskType: opt.value })}
                  >{opt.label}</button>
                ))}
              </div>
            </div>

            <div className="form-row-compact">
              <label className="field-label compact">
                <span>预计分钟</span>
                <input type="number" min={1} value={form.estimatedMinutes} onChange={(e) => setForm({ ...form, estimatedMinutes: Number(e.target.value) })} />
              </label>
              <label className="field-label compact">
                <span>截止日期</span>
                <input type="date" value={form.deadline} onChange={(e) => setForm({ ...form, deadline: e.target.value })} />
              </label>
            </div>

            <button type="button" className="advanced-toggle" onClick={() => setShowAdvanced((v) => !v)}>
              {showAdvanced ? <ChevronUp size={14} /> : <ChevronDown size={14} />} 高级选项
            </button>
            {showAdvanced && (
              <div className="advanced-block">
                <label className="field-label compact">
                  <span>基础经验</span>
                  <input type="number" min={0} value={form.baseExp} onChange={(e) => setForm({ ...form, baseExp: Number(e.target.value) })} />
                </label>
                <div className="field-label compact">
                  <span>难度</span>
                  <div className="chip-group">
                    {DIFFICULTY_OPTIONS.map((opt) => (
                      <button
                        key={opt.value}
                        type="button"
                        className={`chip small${form.difficulty === opt.value ? ' selected' : ''}`}
                        onClick={() => setForm({ ...form, difficulty: opt.value })}
                      >{opt.label}</button>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {draftStatus && <div className="form-hint">{draftStatus}</div>}
            <div className="row-actions form-actions">
              {formMode === 'create' && (
                <button className="secondary-button" type="button" onClick={saveDraft} disabled={taskFormBusy}>
                  <FileText size={14} />保存草稿
                </button>
              )}
              <button className="secondary-button" type="button" onClick={() => (formMode === 'edit' ? setFormMode(null) : backToList())} disabled={taskFormBusy}>
                取消
              </button>
              <button className="primary-button" disabled={taskFormBusy}>
                {formMode === 'edit' ? <Save size={16} /> : <Plus size={16} />}
                {taskFormBusy ? '处理中...' : formMode === 'edit' ? '保存修改' : '添加任务'}
              </button>
            </div>
          </form>
        </section>
      </div>
    );
  }

  // ---------- 详情视图 ----------
  if (view === 'detail' && activeTask) {
    const t = activeTask;
    const editable = EDITABLE_STATUSES.has(t.status);
    const statusHint = getStatusHint(t.status);
    return (
      <div className="page-stack">
        {taskError && <div className="form-error">{taskError}</div>}
        <button type="button" className="back-link" onClick={backToList}>
          <ChevronLeft size={16} /> 返回任务列表
        </button>

        {/* ===== 任务头 + 操作栏 ===== */}
        <section className="panel task-detail-head">
          <div className="task-detail-title">
            <h2>{t.title}</h2>
            <span className={`badge ${t.status}`}>{statusLabel[t.status] ?? t.status}</span>
          </div>
          <p className="muted">{t.description || '暂无描述'}</p>
          <div className="task-detail-meta">
            {t.category && <span className="task-cat-tag">{t.category}</span>}
            <span>{t.courseName || '未分类'}</span>
            <span>·</span>
            <span>{TASK_TYPE_LABEL[t.taskType] ?? t.taskType}</span>
            <span>·</span>
            <span>{DIFFICULTY_LABEL[t.difficulty] ?? t.difficulty}</span>
            <span>·</span>
            <span>{t.estimatedMinutes} 分钟</span>
            {t.deadline && <><span>·</span><span>截止 {t.deadline}</span></>}
          </div>

          {statusHint && <div className="status-hint">{statusHint}</div>}

          <div className="row-actions task-detail-actions">
            {t.status === 'TODO' && (
              <button className="primary-button" onClick={() => void startTask(t.id)}>
                <Play size={16} />开始任务
              </button>
            )}
            {t.status === 'DOING' && (
              <button className="secondary-button" onClick={() => void pauseTask(t.id)}>
                <Pause size={14} />暂停
              </button>
            )}
            {t.status === 'PAUSED' && (
              <button className="primary-button" onClick={() => void resumeTask(t.id)}>
                <Play size={16} />继续
              </button>
            )}
            {editable && (
              <button className="secondary-button" onClick={() => editTask(t)}>
                <Edit3 size={14} />编辑
              </button>
            )}
            {confirmDeleteId === t.id ? (
              <>
                <span className="muted small" style={{ alignSelf: 'center' }}>永久删除？此操作不可恢复</span>
                <button className="secondary-button" style={{ color: 'var(--danger, #e55)' }} onClick={() => void deleteTask(t.id)}>确认删除</button>
                <button className="secondary-button" onClick={() => setConfirmDeleteId(null)}>取消</button>
              </>
            ) : (
              <button className="secondary-button" onClick={() => setConfirmDeleteId(t.id)} title="物理删除此任务及关联记录，不可恢复">
                <Trash2 size={14} />删除
              </button>
            )}
          </div>
        </section>

        {/* ===== 提交凭证（仅可提交状态）===== */}
        {canSubmit && (
          <section className="panel full">
            <div className="panel-title"><h2>{isResubmit ? '重新提交凭证' : '提交学习凭证'}</h2></div>
            <div className="submission-box">
              {isResubmit && (
                <div className="resubmit-hint">
                  此任务之前被{t.status === 'NEED_MORE' ? '要求补充' : '打回'}，你可以修改凭证后重新提交。
                </div>
              )}
              <div ref={proofScopeRef} className="proof-scope">
                <textarea value={proof} onChange={(e) => setProof(e.target.value)} placeholder="描述你的学习过程和成果（在此区域可直接 Ctrl/Cmd+V 粘贴截图）..." />
                <input value={proofLink} onChange={(e) => setProofLink(e.target.value)} placeholder="题目链接或资料链接，可选" />
                <ProofUploader images={proofImages} onChange={setProofImages} pasteScopeRef={proofScopeRef} />
                <textarea value={codeSnippet} onChange={(e) => setCodeSnippet(e.target.value)} placeholder="代码片段，可选" className="code" />
              </div>

              {submitPhase !== 'idle' && submitPhase !== 'error' && (
                <div className="submit-progress-area">
                  <div className="submit-progress-bar">
                    <div className={`submit-progress-fill ${submitPhase === 'done' ? 'done' : 'animating'}`} style={{ width: `${submitProgress}%` }} />
                  </div>
                  <div className="submit-progress-label">
                    {submitPhase === 'ai_reviewing' && <Loader size={14} className="spin" />}
                    <span>{PHASE_LABELS[submitPhase]}</span>
                  </div>
                </div>
              )}

              <button className="primary-button" onClick={submitTask} disabled={submitPhase !== 'idle' && submitPhase !== 'error'}>
                <CheckCircle2 size={16} />
                {submitPhase === 'idle' || submitPhase === 'error'
                  ? (isResubmit ? '重新提交审核' : '提交审核')
                  : PHASE_LABELS[submitPhase]}
              </button>
            </div>
          </section>
        )}

        {/* ===== AI 追问 / 反馈 ===== */}
        {aiQuestion && (
          <section className="panel full">
            <div className="ai-question-card">
              <p className="ai-question-label"><Bot size={16} /> AI 追问</p>
              <p>{aiQuestion.question}</p>
              <textarea value={aiAnswer} onChange={(e) => setAiAnswer(e.target.value)} placeholder="请输入你的回答（越详细加成越高）..." />
              <div className="row-actions">
                <button className="primary-button" onClick={submitAiAnswer} disabled={aiAnswering}>{aiAnswering ? '提交中...' : '提交答案'}</button>
                <button className="secondary-button" onClick={() => setAiQuestion(null)} disabled={aiAnswering}>跳过</button>
              </div>
            </div>
          </section>
        )}
        {aiAnswerFeedback && (
          <div className="ai-feedback-banner"><Bot size={16} /> {aiAnswerFeedback.feedback}</div>
        )}

        {/* ===== 这个任务的提交记录 ===== */}
        <section className="panel full">
          <div className="panel-title"><h2>提交记录</h2></div>
          <div className="task-list">
            {taskSubmissions.map((submission) => (
              <SubmissionCard
                key={submission.id}
                submission={submission}
                isLoadingDetail={loadingDetailId === submission.id}
                isAppealing={appealingIds.has(submission.id)}
                onViewDetail={() => openSubmissionDetail(submission.id)}
                onAppeal={(reason, urls) => appeal(submission.id, reason, urls)}
              />
            ))}
            {taskSubmissions.length === 0 && <Empty text="这个任务还没有提交记录。" />}
          </div>
        </section>

        {/* ===== 审核反馈详情 ===== */}
        {submissionDetail && (
          <section className="panel full">
            <div className="panel-title">
              <h2>审核反馈</h2>
              <button className="icon-button" onClick={() => setSubmissionDetail(null)} title="关闭反馈">×</button>
            </div>
            <SubmissionFeedback detail={submissionDetail} />
          </section>
        )}
      </div>
    );
  }

  // ---------- 列表视图 ----------
  return (
    <div className="page-stack">
      {taskError && <div className="form-error">{taskError}</div>}

      <section className="panel">
        <div className="panel-title">
          <h2>任务列表</h2>
          <button className="primary-button" onClick={openCreate}><Plus size={16} />新建任务</button>
        </div>

        {/* 状态分组筛选 */}
        <div className="chip-group" style={{ marginBottom: 8 }}>
          {FILTER_TABS.map((f) => (
            <button
              key={f.key}
              type="button"
              className={`chip${listFilter === f.key ? ' selected' : ''}`}
              onClick={() => setListFilter(f.key)}
            >
              {f.label}<span className="chip-count">{filterCounts[f.key]}</span>
            </button>
          ))}
        </div>

        {/* 大分类筛选 */}
        {categoryList.length > 0 && (
          <div className="chip-group" style={{ marginBottom: 8 }}>
            <button type="button" className={`chip small${categoryFilter === '' ? ' selected' : ''}`} onClick={() => setCategoryFilter('')}>全部大分类</button>
            {categoryList.map((name) => (
              <button
                key={name}
                type="button"
                className={`chip small${categoryFilter === name ? ' selected' : ''}`}
                onClick={() => setCategoryFilter(categoryFilter === name ? '' : name)}
              >{name}</button>
            ))}
          </div>
        )}

        {/* 课程筛选 */}
        {courseList.length > 0 && (
          <div className="chip-group" style={{ marginBottom: 8 }}>
            <button type="button" className={`chip small${courseFilter === '' ? ' selected' : ''}`} onClick={() => setCourseFilter('')}>全部课程</button>
            {courseList.map((name) => (
              <button
                key={name}
                type="button"
                className={`chip small${courseFilter === name ? ' selected' : ''}`}
                onClick={() => setCourseFilter(courseFilter === name ? '' : name)}
              >{name}</button>
            ))}
          </div>
        )}

        {/* 任务卡片：整卡可点，进入详情 */}
        <div className="task-list">
          {filteredTasks.map((task) => (
            <button key={task.id} type="button" className="task-card task-card-link" onClick={() => openDetail(task.id)}>
              <TaskRow task={task} />
              <div className="task-card-foot">
                <span className="muted small">{task.estimatedMinutes} 分钟{task.deadline ? ` · 截止 ${task.deadline}` : ''}</span>
                <span className="task-next-hint">{nextStepHint(task.status)} <ChevronRight size={14} /></span>
              </div>
            </button>
          ))}
          {filteredTasks.length === 0 && <Empty text={tasks.length === 0 ? '任务列表是空的，点「新建任务」创建第一个学习目标。' : '当前筛选下没有任务。'} />}
        </div>
      </section>
    </div>
  );
}

/**
 * 【提交记录卡片组件】展示单条提交记录的摘要信息和操作按钮
 */
function SubmissionCard({ submission, isLoadingDetail, isAppealing, onViewDetail, onAppeal }: {
  submission: Submission;
  isLoadingDetail: boolean;
  isAppealing: boolean;
  onViewDetail: () => void;
  onAppeal: (reason: string, urls: string[]) => void;
}) {
  const [localAppealReason, setLocalAppealReason] = useState('我已补充说明，请管理员人工复核。');
  const [appealImages, setAppealImages] = useState<ProofImage[]>([]);
  const [showAppealForm, setShowAppealForm] = useState(false);
  const appealScopeRef = useRef<HTMLDivElement>(null);
  const canAppeal = ['AI_REJECTED', 'NEED_MORE', 'MANUAL_REJECTED', 'MODERATION_BLOCKED'].includes(submission.status);
  const taskTitle = submission.task?.title;

  return (
    <div className="task-card">
      <div className="task-row">
        <div>
          <strong>
            提交 #{submission.id}
            {taskTitle && <span className="muted" style={{ fontWeight: 400, marginLeft: 8, fontSize: 13 }}>— {taskTitle}</span>}
          </strong>
          <p>{submission.textProof || '无文字凭证'}</p>
        </div>
        <span className={`badge ${submission.status}`}>{statusLabel[submission.status] ?? submission.status}</span>
      </div>
      <ProofThumbStrip urls={parseScreenshotUrls(submission)} />
      <div className="row-actions">
        <button className="secondary-button compact-button" disabled={isLoadingDetail} onClick={onViewDetail}>
          {isLoadingDetail ? <><Loader size={12} className="spin" /> 加载中...</> : '查看反馈'}
        </button>
        {canAppeal && !showAppealForm && (
          <button className="secondary-button compact-button" onClick={() => setShowAppealForm(true)}>
            发起申诉
          </button>
        )}
      </div>
      {canAppeal && showAppealForm && (
        <div ref={appealScopeRef} className="appeal-form proof-scope">
          <textarea
            value={localAppealReason}
            onChange={(e) => setLocalAppealReason(e.target.value)}
            disabled={isAppealing}
            placeholder="申诉理由（可粘贴截图）..."
            rows={3}
          />
          <ProofUploader images={appealImages} onChange={setAppealImages} pasteScopeRef={appealScopeRef} />
          <div className="row-actions">
            <button
              className="primary-button"
              onClick={() => onAppeal(localAppealReason, appealImages.map((i) => i.url))}
              disabled={isAppealing}
            >
              {isAppealing ? '申诉中...' : '提交申诉'}
            </button>
            <button className="secondary-button" onClick={() => setShowAppealForm(false)} disabled={isAppealing}>
              取消
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 【RAG 命中面板组件】展示 AI 审核时参考的用户历史学习记忆
 */
function RagHitsPanel({ hits }: { hits?: AiReview['ragHits'] }) {
  const [open, setOpen] = useState(false);
  if (!hits || hits.length === 0) return null;
  return (
    <div className="rag-hits" data-testid="rag-hits">
      <button type="button" className="rag-hits-toggle" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        AI 参考了你之前的 {hits.length} 条学习记忆 {open ? '▾' : '▸'}
      </button>
      {open && (
        <ul className="rag-hits-list">
          {hits.map((h, i) => (
            <li key={`${h.sourceType}-${h.sourceId}-${i}`}>
              <span className="rag-hits-label">{h.label}</span>
              <span className="rag-hits-sim">相似度 {h.similarity.toFixed(2)}</span>
              <p className="rag-hits-snippet">{h.snippet}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** 【分数等级判定】根据 AI 审核分数返回等级标签和样式类名 */
function scoreTier(score: number): { label: string; cls: 'ok' | 'warn' | 'bad' } {
  if (score >= 70) return { label: '通过', cls: 'ok' };
  if (score >= 45) return { label: '边缘', cls: 'warn' };
  return { label: '不足', cls: 'bad' };
}

/** 【分数条组件】展示单项评分（相关性/完整度/质量）的进度条 */
function ScoreBar({ label, value }: { label: string; value: number }) {
  const tier = scoreTier(value);
  return (
    <div className="score-bar">
      <div className="score-bar-head">
        <span className="score-bar-label">{label}</span>
        <span className={`score-bar-value ${tier.cls}`}>{value}</span>
      </div>
      <div className="score-bar-track">
        <div className={`score-bar-fill ${tier.cls}`} style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
      </div>
    </div>
  );
}

/**
 * 【提交审核反馈详情组件】展示 AI 审核的完整结果
 */
function SubmissionFeedback({ detail }: { detail: SubmissionDetail }) {
  const review = detail.review as AiReview | null;
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (detail.submission.status !== 'AI_REVIEWING') return;
    const t = setInterval(() => setNowMs(Date.now()), 5000);
    return () => clearInterval(t);
  }, [detail.submission.status]);
  const elapsedSec = detail.submission.createdAt
    ? Math.max(0, Math.floor((nowMs - Date.parse(detail.submission.createdAt)) / 1000))
    : 0;

  return (
    <div className="submission-feedback">
      <div className="admin-meta-grid">
        <div><span>关联任务</span><strong>{detail.task.title}</strong></div>
        <div><span>提交状态</span><strong>{statusLabel[detail.submission.status] ?? detail.submission.status}</strong></div>
        <div><span>学习时长</span><strong>{detail.submission.studyMinutes} 分钟</strong></div>
      </div>
      <ProofThumbStrip urls={parseScreenshotUrls(detail.submission)} />
      {review ? (
        <>
          <div className="review-headline">
            <div className="review-headline-score">
              <span className={`review-headline-tier ${scoreTier(review.score).cls}`}>{scoreTier(review.score).label}</span>
              <span className="review-headline-num">{review.score}</span>
              <span className="muted small">/ 100</span>
            </div>
            <div className="review-headline-exp">
              <span className="muted small">建议经验</span>
              <strong>{review.recommendedExp}</strong>
            </div>
          </div>
          <div className="score-breakdown-bars">
            <ScoreBar label="相关性" value={review.relevanceScore} />
            <ScoreBar label="完整度" value={review.completenessScore} />
            <ScoreBar label="质量" value={review.qualityScore} />
          </div>
          <div className="feedback-copy">
            <div className="feedback-block">
              <span className="feedback-block-label">AI 判断</span>
              <p>{review.reason}</p>
            </div>
            {review.suggestion && (
              <div className="feedback-block">
                <span className="feedback-block-label">改进建议</span>
                <p>{review.suggestion}</p>
              </div>
            )}
          </div>
          <RagHitsPanel hits={review.ragHits} />
        </>
      ) : detail.submission.status === 'AI_REVIEWING' ? (
        <div className="planner-chat-row assistant" style={{ padding: '6px 0' }}>
          <span className="planner-chat-role">AI</span>
          <div className="planner-chat-bubble assistant streaming">
            <span className="planner-chat-typing"><span /><span /><span /></span>
            <span style={{ marginLeft: 8, color: 'var(--ink-3)', fontSize: 13 }}>
              正在审核你的凭证…
              {elapsedSec >= 30 && <> 已 {Math.floor(elapsedSec / 60)} 分 {elapsedSec % 60} 秒</>}
              {elapsedSec >= 180 && <span style={{ color: 'var(--danger)', marginLeft: 6 }}>超出常规时间，可稍后刷新</span>}
            </span>
          </div>
        </div>
      ) : <Empty text="这条提交还没有审核反馈。" />}
      {detail.submission.moderationReason && (
        <div className="admin-comment-box">
          <strong>内容安全拦截原因</strong>
          <p>{detail.submission.moderationReason}</p>
          <p className="muted small">可修改凭证后重新提交，或对该提交发起申诉由管理员复核。</p>
        </div>
      )}
      {detail.submission.adminComment && (
        <div className="admin-comment-box">
          <strong>管理员意见</strong>
          <p>{detail.submission.adminComment}</p>
        </div>
      )}
    </div>
  );
}
