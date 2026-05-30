/**
 * 【任务管理页面】TasksPage
 * 本页面是 Soulous 的核心功能页面，负责学习任务的全生命周期管理：
 * - 任务列表：查看、筛选、编辑、删除任务
 * - 创建/编辑任务：填写标题、描述、课程、类型、时长等信息
 * - 提交任务：上传学习凭证（文字、截图、代码、链接），触发 AI 审核
 * - 提交历史：查看审核结果、发起申诉
 *
 * 状态机：TODO -> DOING -> SUBMITTED -> AI_REVIEWING -> AI_APPROVED/AI_REJECTED
 * 特殊状态：PAUSED（暂停）、NEED_MORE（需要补充）、APPEALING（申诉中）
 *
 * 设计要点：
 * - 草稿自动保存到 localStorage，防止意外丢失
 * - 提交过程有进度条动画，提升用户体验
 * - 支持 SSE 通知实时更新审核结果
 * - 提交详情有缓存机制，避免重复请求
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Edit3,
  FileText,
  Loader,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Save,
  Trash2
} from 'lucide-react';
import { api } from '../api';
import type { AiReview, StudyTask, Submission, SubmissionDetail } from '../types';
import { Empty, TaskRow, statusLabel } from '../components/shared';
import { ProofUploader, ProofThumbStrip, parseScreenshotUrls, type ProofImage } from '../components/ProofUploader';

/**
 * 【任务表单状态类型】定义创建/编辑任务时表单的数据结构
 * 用于表单双向绑定和提交数据组装
 */
type TaskFormState = {
  title: string;
  description: string;
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
  courseName: '数据结构',
  estimatedMinutes: 30,
  baseExp: 20,
  taskType: 'STUDY',
  difficulty: 'NORMAL',
  deadline: ''
});

/**
 * 【任务类型选项】每种类型对应不同的颜色标识，方便用户在列表中快速识别
 * accent 颜色用于选中状态的边框和背景高亮
 */
const TASK_TYPE_OPTIONS: Array<{ value: StudyTask['taskType']; label: string; accent: string }> = [
  { value: 'STUDY',   label: '学习', accent: '#0d9488' },
  { value: 'CODING',  label: '编程', accent: '#7c3aed' },
  { value: 'NOTE',    label: '笔记', accent: '#2563eb' },
  { value: 'MEMORY',  label: '背诵', accent: '#d97706' },
  { value: 'REVIEW',  label: '复盘', accent: '#e11d48' },
  { value: 'SIMPLE',  label: '简单', accent: '#64748b' }
];

/** 【难度选项】影响任务的经验值加成和 AI 审核标准 */
const DIFFICULTY_OPTIONS: Array<{ value: StudyTask['difficulty']; label: string }> = [
  { value: 'EASY',      label: '简单' },
  { value: 'NORMAL',    label: '普通' },
  { value: 'HARD',      label: '困难' },
  { value: 'CHALLENGE', label: '挑战' }
];

/** 【草稿存储键名】localStorage 中保存未提交表单数据的 key */
const DRAFT_KEY = 'soulous_task_draft_v1';

/** 【可提交状态集合】只有这些状态的任务才允许提交凭证 */
const SUBMITTABLE_STATUSES = new Set(['DOING', 'NEED_MORE', 'AI_REJECTED', 'MODERATION_BLOCKED']);
/** 【可编辑状态集合】只有这些状态的任务才允许修改基本信息 */
const EDITABLE_STATUSES = new Set(['TODO', 'NEED_MORE', 'AI_REJECTED', 'MODERATION_BLOCKED']);

/** 【页面标签页类型】list=任务列表, create=创建/编辑, submit=提交凭证, history=提交历史 */
type TabKey = 'list' | 'create' | 'submit' | 'history';
/** 【列表筛选类型】all=全部, todo=待完成, doing=进行中 */
type ListFilter = 'all' | 'todo' | 'doing';

/**
 * 【提交阶段状态机】
 * idle -> uploading -> submitting -> ai_reviewing -> done
 *                                                     \-> error
 * 用于控制提交进度条动画和状态文案
 */
type SubmitPhase = 'idle' | 'uploading' | 'submitting' | 'ai_reviewing' | 'done' | 'error';

/** 【阶段文案映射】每个提交阶段对应的用户提示文字 */
const PHASE_LABELS: Record<SubmitPhase, string> = {
  idle: '',
  uploading: '正在上传凭证...',
  submitting: '正在提交任务...',
  ai_reviewing: 'AI 正在审核中，请稍候...',
  done: '审核完成！',
  error: '提交失败'
};

/** 【阶段进度映射】每个阶段对应的进度条百分比 */
const PHASE_PROGRESS: Record<SubmitPhase, number> = {
  idle: 0,
  uploading: 15,
  submitting: 35,
  ai_reviewing: 65,
  done: 100,
  error: 0
};

/**
 * 【任务转表单】将 StudyTask 对象转换为表单状态，用于编辑模式的数据回填
 * 使用空字符串/默认值处理可能为 null 的字段
 */
const taskToForm = (task: StudyTask): TaskFormState => ({
  title: task.title,
  description: task.description ?? '',
  courseName: task.courseName ?? '',
  estimatedMinutes: task.estimatedMinutes ?? 30,
  baseExp: task.baseExp ?? 20,
  taskType: task.taskType,
  difficulty: task.difficulty,
  deadline: task.deadline ?? ''
});

/**
 * 【任务页面主组件】
 * @param tasks - 任务列表（从父组件传入）
 * @param onRefresh - 刷新任务列表的回调函数
 *
 * 核心状态：
 * - tab: 当前激活的标签页
 * - listFilter/courseFilter: 列表筛选条件
 * - form: 任务表单状态
 * - selected: 当前选中的任务
 * - proofImages: 上传的凭证图片列表
 * - submitPhase: 提交进度阶段
 * - aiQuestion: AI 追问问题（提交后 AI 可能会追问）
 */
export function TasksPage({ tasks, onRefresh }: { tasks: StudyTask[]; onRefresh: () => void }) {
  const [tab, setTab] = useState<TabKey>('list');
  const [listFilter, setListFilter] = useState<ListFilter>('all');
  const [courseFilter, setCourseFilter] = useState<string>('');

  const [form, setForm] = useState<TaskFormState>(emptyTaskForm());
  /** 【编辑中的任务 ID】非 null 表示正在编辑已有任务 */
  const [editingId, setEditingId] = useState<number | null>(null);
  /** 【确认删除的任务 ID】非 null 时显示二次确认按钮 */
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);
  /** 【当前选中的任务】用于提交凭证时的目标任务 */
  const [selected, setSelected] = useState<StudyTask | null>(tasks[0] ?? null);
  /** 【文字凭证】用户描述学习过程和成果的文本 */
  const [proof, setProof] = useState('我完成了本次学习，整理了关键知识点和练习结果。');
  /** 【代码片段】可选的代码证明 */
  const [codeSnippet, setCodeSnippet] = useState('');
  /** 【凭证链接】可选的题目或资料链接 */
  const [proofLink, setProofLink] = useState('');
  /** 【凭证图片列表】上传的截图，支持粘贴上传 */
  const [proofImages, setProofImages] = useState<ProofImage[]>([]);
  /** 【凭证区域 DOM 引用】用于监听粘贴事件，实现 Ctrl+V 截图上传 */
  const proofScopeRef = useRef<HTMLDivElement>(null);
  /** 【提交历史列表】用户的提交记录 */
  const [submissions, setSubmissions] = useState<Submission[]>([]);
  /** 【提交详情】当前查看的提交详情（含 AI 审核结果） */
  const [submissionDetail, setSubmissionDetail] = useState<SubmissionDetail | null>(null);
  /**
   * 【AI 追问】提交凭证后 AI 可能会追问以验证学习深度
   * 包含问题文本和对应的提交 ID
   */
  const [aiQuestion, setAiQuestion] = useState<{ question: string; submissionId: number } | null>(null);
  /** 【AI 追问答案】用户对 AI 追问的回答 */
  const [aiAnswer, setAiAnswer] = useState('');
  /** 【AI 回答反馈】回答后的经验加成和反馈信息 */
  const [aiAnswerFeedback, setAiAnswerFeedback] = useState<{ bonusExp: number; feedback: string } | null>(null);
  /** 【表单提交忙碌状态】防止重复提交 */
  const [taskFormBusy, setTaskFormBusy] = useState(false);
  /** 【AI 回答忙碌状态】防止重复提交答案 */
  const [aiAnswering, setAiAnswering] = useState(false);
  /** 【申诉中的提交 ID 集合】防止重复申诉 */
  const [appealingIds, setAppealingIds] = useState<Set<number>>(new Set());
  const [taskError, setTaskError] = useState('');
  /** 【正在加载详情的提交 ID】显示 loading 状态 */
  const [loadingDetailId, setLoadingDetailId] = useState<number | null>(null);
  /** 【高级选项展开状态】控制基础经验和难度选项的显示 */
  const [showAdvanced, setShowAdvanced] = useState(false);
  /** 【草稿状态提示】保存/恢复草稿时的反馈信息 */
  const [draftStatus, setDraftStatus] = useState<string>('');

  const [submitPhase, setSubmitPhase] = useState<SubmitPhase>('idle');
  /** 【提交进度百分比】进度条动画的目标值 */
  const [submitProgress, setSubmitProgress] = useState(0);
  /** 【进度动画定时器引用】用于清理定时器避免内存泄漏 */
  const progressTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /**
   * 【提交详情缓存】使用 useRef 而非 state，避免触发重渲染
   * key 是 submissionId，value 是 SubmissionDetail
   */
  const detailCache = useRef<Map<number, SubmissionDetail>>(new Map());

  /**
   * 【课程列表】从任务中提取不重复的课程名称，用于筛选器
   * 使用 useMemo 缓存，仅在 tasks 变化时重新计算
   */
  const courseList = useMemo(() => {
    const seen = new Set<string>();
    const out: string[] = [];
    tasks.forEach((t) => {
      const name = (t.courseName ?? '').trim();
      if (name && !seen.has(name)) { seen.add(name); out.push(name); }
    });
    return out;
  }, [tasks]);

  /**
   * 【最近课程】按创建时间倒序取前 6 个课程，用于表单中的快捷选择
   */
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

  /**
   * 【筛选后的任务列表】根据 listFilter 和 courseFilter 过滤任务
   */
  const filteredTasks = useMemo(() => {
    return tasks.filter((t) => {
      if (listFilter === 'todo' && t.status !== 'TODO') return false;
      if (listFilter === 'doing' && t.status !== 'DOING' && t.status !== 'PAUSED') return false;
      if (courseFilter && (t.courseName ?? '').trim() !== courseFilter) return false;
      return true;
    });
  }, [tasks, listFilter, courseFilter]);

  /** 【可提交任务列表】只包含允许提交凭证的状态的任务 */
  const submittableTasks = useMemo(
    () => tasks.filter((t) => SUBMITTABLE_STATUSES.has(t.status)),
    [tasks]
  );

  /** 【主题色】根据当前任务类型动态改变表单区域的强调色 */
  const accentColor = useMemo(
    () => TASK_TYPE_OPTIONS.find((o) => o.value === form.taskType)?.accent ?? '#0d9488',
    [form.taskType]
  );

  /** 【是否可提交】选中的任务且状态在可提交集合中 */
  const canSubmit = !!(selected && SUBMITTABLE_STATUSES.has(selected.status));
  /** 【是否为重新提交】需要补充或被拒绝的任务需要重新提交 */
  const isResubmit = !!(selected && (selected.status === 'NEED_MORE' || selected.status === 'AI_REJECTED'));

  /**
   * 【恢复草稿】组件挂载时从 localStorage 读取上次未提交的表单数据
   * 仅在非编辑模式下恢复，避免覆盖正在编辑的内容
   */
  useEffect(() => {
    if (editingId) return;
    try {
      const raw = localStorage.getItem(DRAFT_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Partial<TaskFormState>;
      if (parsed && typeof parsed === 'object' && (parsed.title || parsed.description)) {
        setForm({ ...emptyTaskForm(), ...parsed });
        setDraftStatus('已恢复上次未提交的草稿');
      }
    } catch { /* 忽略损坏的草稿数据 */ }
  }, []);

  /**
   * 【保存草稿】将当前表单数据保存到 localStorage
   * 2.4 秒后自动清除提示信息
   */
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
   * 【加载提交记录】从 API 获取用户的提交历史
   * 使用 useCallback 缓存函数引用，避免 useEffect 无限循环
   */
  const loadSubmissions = useCallback(async () => {
    try {
      const data = await api.mySubmissions();
      setSubmissions(Array.isArray(data) ? data as Submission[] : []);
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '加载提交记录失败');
    }
  }, []);

  // 【初始加载】组件挂载时获取提交记录
  useEffect(() => { void loadSubmissions(); }, []);

  /**
   * 【SSE 通知监听】监听 NotificationBell 触发的自定义事件
   * 当收到 AI_REVIEW_DONE（审核完成）、APPEAL_REVIEWED（申诉处理完成）、
   * MODERATION_BLOCKED（内容安全拦截）通知时，自动刷新任务列表和提交记录，
   * 让用户无需手动刷新即可看到最新的审核结果。
   *
   * Listen for SSE-driven notifications fired by NotificationBell. AI_REVIEW_DONE means
   * the async review pipeline finished — re-fetch both the task list (status moved from
   * AI_REVIEWING) and the submissions panel so the user sees the verdict without a
   * manual refresh.
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
   * 【任务列表同步】当父组件的 tasks 更新时，同步更新选中的任务
   * 如果当前没有选中任务且列表非空，默认选中第一个
   * 如果选中的任务被更新（引用变化），则更新引用
   * 如果选中的任务被删除，则回退到第一个任务
   */
  useEffect(() => {
    if (!selected && tasks.length > 0) { setSelected(tasks[0]); return; }
    if (selected) {
      const refreshed = tasks.find((t) => t.id === selected.id);
      if (refreshed && refreshed !== selected) setSelected(refreshed);
      if (!refreshed) setSelected(tasks[0] ?? null);
    }
  }, [tasks]);

  /**
   * 【启动进度动画】以随机速度递增进度条，营造"正在处理"的感觉
   * @param targetPercent - 目标百分比，进度条会趋近但不超过此值
   * 使用 200ms 间隔，每次递增 1-4%，在接近目标时停止
   */
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

  /** 【停止进度动画】清理定时器，防止内存泄漏 */
  function stopProgressAnimation() {
    if (progressTimerRef.current) {
      clearInterval(progressTimerRef.current);
      progressTimerRef.current = null;
    }
  }

  /**
   * 【创建/更新任务】表单提交处理函数
   * - 编辑模式：调用 updateTask API
   * - 创建模式：调用 createTask API，成功后清除草稿
   * - 成功后重置表单、刷新列表、切换到列表页
   */
  async function createTask(event: React.FormEvent) {
    event.preventDefault();
    setTaskError('');
    setTaskFormBusy(true);
    try {
      if (editingId) {
        const updated = await api.updateTask(editingId, form);
        setSelected(updated);
        setEditingId(null);
      } else {
        const created = await api.createTask(form);
        setSelected(created);
        try { localStorage.removeItem(DRAFT_KEY); } catch { /* ignore */ }
        setDraftStatus('');
      }
      setForm(emptyTaskForm());
      await onRefresh();
      setTab('list');
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setTaskFormBusy(false);
    }
  }

  /**
   * 【进入编辑模式】将选中的任务数据填充到表单，切换到创建标签页
   * 只有在可编辑状态（TODO, NEED_MORE, AI_REJECTED, MODERATION_BLOCKED）下才能编辑
   */
  function editTask(task: StudyTask) {
    if (!EDITABLE_STATUSES.has(task.status)) {
      setTaskError('任务已开始，无法编辑。请先暂停或重新开始一个未启动的任务。');
      return;
    }
    setSelected(task);
    setEditingId(task.id);
    setForm(taskToForm(task));
    setTab('create');
  }

  /** 【取消编辑】清除编辑状态，重置表单 */
  function cancelEdit() {
    setEditingId(null);
    setForm(emptyTaskForm());
  }

  /**
   * 【删除任务】物理删除任务及关联记录，不可恢复
   * 如果删除的是当前选中或正在编辑的任务，需要清理相关状态
   */
  async function deleteTask(id: number) {
    setConfirmDeleteId(null);
    setTaskError('');
    try {
      await api.deleteTask(id);
      if (selected?.id === id) setSelected(null);
      if (editingId === id) cancelEdit();
      await onRefresh();
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '删除失败');
    }
  }

  /**
   * 【开始任务】将任务状态从 TODO 变为 DOING
   * 只有 TODO 状态的任务才能开始
   */
  async function startTask(id: number) {
    setTaskError('');
    try {
      await api.startTask(id);
      await onRefresh();
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '开始任务失败');
    }
  }

  /** 【暂停任务】将任务状态从 DOING 变为 PAUSED */
  async function pauseTask(id: number) {
    setTaskError('');
    try {
      await api.pauseTask(id);
      await onRefresh();
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '暂停任务失败');
    }
  }

  /** 【继续任务】将任务状态从 PAUSED 变回 DOING */
  async function resumeTask(id: number) {
    setTaskError('');
    try {
      await api.resumeTask(id);
      await onRefresh();
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '继续任务失败');
    }
  }

  /**
   * 【提交任务凭证】核心提交流程：
   * 1. 设置提交阶段为 'submitting'，启动进度动画
   * 2. 切换到 'ai_reviewing' 阶段，等待 AI 审核
   * 3. 收集凭证数据（文字、代码、链接、截图 URL 列表）
   * 4. 调用 submitTask API
   * 5. 成功后显示 AI 追问、清理缓存、刷新数据
   * 6. 失败时显示错误，3 秒后重置状态
   */
  async function submitTask() {
    if (!selected || !canSubmit) return;
    setTaskError('');
    setSubmitPhase('submitting');
    setSubmitProgress(PHASE_PROGRESS.submitting);
    startProgressAnimation(PHASE_PROGRESS.ai_reviewing);

    try {
      setSubmitPhase('ai_reviewing');
      setSubmitProgress(PHASE_PROGRESS.ai_reviewing);
      startProgressAnimation(PHASE_PROGRESS.done);

      // 【组装截图 URL 列表】将上传的图片转换为 URL 数组
      const urls = proofImages.map((img) => img.url);
      const result = await api.submitTask(selected.id, {
        textProof: proof,
        studyMinutes: selected.estimatedMinutes,
        codeSnippet,
        proofLink,
        screenshotUrl: urls[0] ?? '',
        screenshotUrls: urls
      });

      stopProgressAnimation();
      setSubmitPhase('done');
      setSubmitProgress(100);

      // 【处理 AI 追问】如果 AI 提出了追问问题，显示追问卡片
      setAiQuestion({ question: result.aiQuestion, submissionId: result.submission.id });
      setAiAnswer('');
      setAiAnswerFeedback(null);
      setProofImages([]);

      // 【清理缓存并刷新】提交成功后需要重新获取最新数据
      detailCache.current.clear();
      await onRefresh();
      await loadSubmissions();

      // 【延迟重置】2 秒后将进度条和阶段重置为初始状态
      setTimeout(() => {
        setSubmitPhase('idle');
        setSubmitProgress(0);
      }, 2000);
    } catch (err) {
      stopProgressAnimation();
      setSubmitPhase('error');
      setSubmitProgress(0);
      setTaskError(err instanceof Error ? err.message : '提交失败');
      setTimeout(() => setSubmitPhase('idle'), 3000);
    }
  }

  /**
   * 【提交 AI 追问答案】用户回答 AI 的追问后提交答案
   * 成功后获得额外经验加成，清除追问状态
   */
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

  /**
   * 【发起申诉】对审核结果不满时，用户可以提交申诉
   * @param submissionId - 要申诉的提交 ID
   * @param reason - 申诉理由
   * @param urls - 补充截图 URL 列表
   */
  async function appeal(submissionId: number, reason: string, urls: string[]) {
    setAppealingIds((prev) => new Set(prev).add(submissionId));
    try {
      await api.createAppeal(submissionId, reason, urls);
      // 【清理详情缓存】申诉后审核结果可能变化，需要重新加载
      detailCache.current.delete(submissionId);
      await Promise.all([onRefresh(), loadSubmissions()]);
    } catch (err) {
      setTaskError(err instanceof Error ? err.message : '申诉失败');
    } finally {
      // 【从申诉集合中移除】无论成功失败都要清理状态
      setAppealingIds((prev) => { const next = new Set(prev); next.delete(submissionId); return next; });
    }
  }

  /**
   * 【打开提交详情】加载并展示提交的详细审核反馈
   * 使用缓存机制避免重复请求同一提交的详情
   */
  async function openSubmissionDetail(submissionId: number) {
    const cached = detailCache.current.get(submissionId);
    if (cached) {
      setSubmissionDetail(cached);
      return;
    }
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

  /**
   * 【获取状态提示】根据任务状态返回用户友好的提示信息
   * 帮助用户理解为什么不能提交，以及下一步应该做什么
   */
  function getStatusHint(status: string): string | null {
    switch (status) {
      case 'TODO': return '任务尚未开始，请先点击"开始"再提交凭证。';
      case 'PAUSED': return '任务已暂停，请先继续任务再提交凭证。';
      case 'COMPLETED': return '此任务已完成';
      case 'AI_APPROVED': return '已通过审核';
      case 'SUBMITTED': return '正在等待审核结果';
      case 'APPEALING': return '正在申诉中，等待管理员处理';
      case 'MANUAL_APPROVED': return '管理员已通过';
      case 'MANUAL_REJECTED': return '管理员已驳回';
      case 'MODERATION_BLOCKED': return '凭证被内容安全检查拦截，请修改后重新提交，或对该提交发起申诉。';
      default: return null;
    }
  }

  return (
    <div className="page-stack">
      {/* 【全局错误提示】在页面顶部显示错误信息 */}
      {taskError && <div className="form-error">{taskError}</div>}

      {/* ===== 【标签页导航】4 个功能标签页的切换按钮 ===== */}
      <div className="sub-tabs">
        <button className={`sub-tab${tab === 'list' ? ' active' : ''}`} onClick={() => setTab('list')}>我的任务</button>
        <button className={`sub-tab${tab === 'create' ? ' active' : ''}`} onClick={() => setTab('create')}>
          {editingId ? '编辑任务' : '创建任务'}
        </button>
        <button className={`sub-tab${tab === 'submit' ? ' active' : ''}`} onClick={() => setTab('submit')}>提交任务</button>
        <button className={`sub-tab${tab === 'history' ? ' active' : ''}`} onClick={() => setTab('history')}>提交与申诉</button>
      </div>

      {/* ===== 【任务列表标签页】展示所有任务，支持状态和课程筛选 ===== */}
      {tab === 'list' && (
        <section className="panel">
          <div className="panel-title"><h2>任务列表</h2></div>
          {/* 【状态筛选器】全部/待完成/进行中 */}
          <div className="chip-group" style={{ marginBottom: 8 }}>
            <button type="button" className={`chip${listFilter === 'all' ? ' selected' : ''}`} onClick={() => setListFilter('all')}>全部</button>
            <button type="button" className={`chip${listFilter === 'todo' ? ' selected' : ''}`} onClick={() => setListFilter('todo')}>待完成</button>
            <button type="button" className={`chip${listFilter === 'doing' ? ' selected' : ''}`} onClick={() => setListFilter('doing')}>进行中</button>
          </div>
          {/* 【课程筛选器】按课程名称过滤，仅在有课程时显示 */}
          {courseList.length > 0 && (
            <div className="chip-group" style={{ marginBottom: 8 }}>
              <button type="button" className={`chip small${courseFilter === '' ? ' selected' : ''}`} onClick={() => setCourseFilter('')}>全部分类</button>
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
          {/* 【任务卡片列表】每个卡片显示任务信息和操作按钮 */}
          <div className="task-list">
            {filteredTasks.map((task) => {
              const editable = EDITABLE_STATUSES.has(task.status);
              return (
                <div
                  key={task.id}
                  className={`task-card ${selected?.id === task.id ? 'selected' : ''}`}
                  onClick={() => setSelected(task)}
                >
                  <TaskRow task={task} />
                  <div className="row-actions">
                    <span>{task.estimatedMinutes} 分钟</span>
                    {/* 【开始按钮】仅 TODO 状态显示 */}
                    {task.status === 'TODO' && (
                      <button className="secondary-button" onClick={(e) => { e.stopPropagation(); void startTask(task.id); }}>
                        <Play size={14} />开始
                      </button>
                    )}
                    {/* 【暂停按钮】仅 DOING 状态显示 */}
                    {task.status === 'DOING' && (
                      <button className="secondary-button" onClick={(e) => { e.stopPropagation(); void pauseTask(task.id); }}>
                        <Pause size={14} />暂停
                      </button>
                    )}
                    {/* 【继续按钮】仅 PAUSED 状态显示 */}
                    {task.status === 'PAUSED' && (
                      <button className="secondary-button" onClick={(e) => { e.stopPropagation(); void resumeTask(task.id); }}>
                        <Play size={14} />继续
                      </button>
                    )}
                    {/* 【提交按钮】可提交状态的任务显示，跳转到提交标签页 */}
                    {SUBMITTABLE_STATUSES.has(task.status) && (
                      <button className="secondary-button" onClick={(e) => { e.stopPropagation(); setSelected(task); setTab('submit'); }}>
                        <CheckCircle2 size={14} />提交
                      </button>
                    )}
                    {/* 【编辑按钮】可编辑状态的任务显示 */}
                    {editable && (
                      <button className="secondary-button" onClick={(e) => { e.stopPropagation(); editTask(task); }}>
                        <Edit3 size={14} />编辑
                      </button>
                    )}
                    {/* 【删除按钮】带二次确认，防止误删 */}
                    {confirmDeleteId === task.id ? (
                      <>
                        <span className="muted small" style={{ alignSelf: 'center' }}>永久删除？此操作不可恢复</span>
                        <button className="secondary-button" style={{ color: 'var(--danger, #e55)' }}
                          onClick={(e) => { e.stopPropagation(); void deleteTask(task.id); }}>确认删除</button>
                        <button className="secondary-button"
                          onClick={(e) => { e.stopPropagation(); setConfirmDeleteId(null); }}>取消</button>
                      </>
                    ) : (
                      <button className="secondary-button"
                        onClick={(e) => { e.stopPropagation(); setConfirmDeleteId(task.id); }}
                        title="物理删除此任务及关联记录，不可恢复">
                        <Trash2 size={14} />删除
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
            {filteredTasks.length === 0 && <Empty text={tasks.length === 0 ? '任务列表是空的。' : '当前筛选下没有任务。'} />}
          </div>
        </section>
      )}

      {/* ===== 【创建/编辑任务标签页】表单区域 ===== */}
      {tab === 'create' && (
        <section className="panel form-panel" style={{ '--accent-color': accentColor } as React.CSSProperties}>
          <div className="panel-title">
            <h2>{editingId ? '编辑任务' : '创建任务'}</h2>
            {editingId && (
              <button className="icon-button" type="button" onClick={cancelEdit} title="取消编辑">
                <RefreshCw size={16} />
              </button>
            )}
          </div>
          <form className="stack-form compact" onSubmit={createTask}>
            <label className="field-label">
              <span>任务标题</span>
              <input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="例如：复习二叉树遍历" required />
            </label>
            <label className="field-label">
              <span>任务描述</span>
              <textarea rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="简单写一下要做什么" />
            </label>

            {/* 【课程选择】显示最近使用的课程作为快捷选项，也可自定义输入 */}
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

            {/* 【任务类型选择】每种类型有独特的颜色标识 */}
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

            {/* 【时长和截止日期】并排显示的两个输入框 */}
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

            {/* 【高级选项折叠】点击展开/收起基础经验和难度设置 */}
            <button
              type="button"
              className="advanced-toggle"
              onClick={() => setShowAdvanced((v) => !v)}
            >
              {showAdvanced ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              高级选项
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

            {/* 【草稿状态提示】保存/恢复草稿的反馈信息 */}
            {draftStatus && <div className="form-hint">{draftStatus}</div>}
            <div className="row-actions form-actions">
              {/* 【保存草稿按钮】仅在创建模式下显示 */}
              {!editingId && (
                <button className="secondary-button" type="button" onClick={saveDraft} disabled={taskFormBusy}>
                  <FileText size={14} />保存草稿
                </button>
              )}
              {editingId && <button className="secondary-button" type="button" onClick={cancelEdit}>取消</button>}
              <button className="primary-button" disabled={taskFormBusy}>
                {editingId ? <Save size={16} /> : <Plus size={16} />}
                {taskFormBusy ? '处理中...' : editingId ? '保存修改' : '添加任务'}
              </button>
            </div>
          </form>
        </section>
      )}

      {/* ===== 【提交任务标签页】选择任务并提交学习凭证 ===== */}
      {tab === 'submit' && (
        <>
          {/* 【任务选择区域】从可提交的任务中选择一个 */}
          <section className="panel">
            <div className="panel-title"><h2>选择要提交的任务</h2></div>
            {submittableTasks.length === 0 ? (
              <Empty text='没有可提交的任务。请先在"我的任务"中开始一个任务。' />
            ) : (
              <div className="task-list">
                {submittableTasks.map((task) => (
                  <div
                    key={task.id}
                    className={`task-card ${selected?.id === task.id ? 'selected' : ''}`}
                    onClick={() => setSelected(task)}
                  >
                    <TaskRow task={task} />
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* 【凭证提交区域】填写文字描述、上传截图、粘贴代码等 */}
          <section className="panel full">
            <div className="panel-title"><h2>提交学习凭证</h2></div>
            {selected ? (
              <div className="submission-box">
                {/* 【选中任务信息】显示任务标题、描述和当前状态 */}
                <div>
                  <h3>{selected.title}</h3>
                  <p className="muted">{selected.description || '暂无描述'}</p>
                  <span className={`badge ${selected.status}`} style={{ marginTop: 4, display: 'inline-block' }}>
                    {statusLabel[selected.status] ?? selected.status}
                  </span>
                </div>

                {canSubmit ? (
                  <>
                    {/* 【重新提交提示】需要补充或被拒绝的任务显示额外说明 */}
                    {isResubmit && (
                      <div className="resubmit-hint">
                        此任务之前被{selected.status === 'NEED_MORE' ? '要求补充' : '打回'}，你可以修改凭证后重新提交。
                      </div>
                    )}
                    {/* 【凭证输入区域】文字描述、链接、截图上传、代码片段 */}
                    <div ref={proofScopeRef} className="proof-scope">
                      <textarea value={proof} onChange={(e) => setProof(e.target.value)} placeholder="描述你的学习过程和成果（在此区域可直接 Ctrl/Cmd+V 粘贴截图）..." />
                      <input value={proofLink} onChange={(e) => setProofLink(e.target.value)} placeholder="题目链接或资料链接，可选" />
                      <ProofUploader images={proofImages} onChange={setProofImages} pasteScopeRef={proofScopeRef} />
                      <textarea value={codeSnippet} onChange={(e) => setCodeSnippet(e.target.value)} placeholder="代码片段，可选" className="code" />
                    </div>

                    {/* 【提交进度条】展示上传、提交、AI 审核的进度 */}
                    {submitPhase !== 'idle' && submitPhase !== 'error' && (
                      <div className="submit-progress-area">
                        <div className="submit-progress-bar">
                          <div
                            className={`submit-progress-fill ${submitPhase === 'done' ? 'done' : 'animating'}`}
                            style={{ width: `${submitProgress}%` }}
                          />
                        </div>
                        <div className="submit-progress-label">
                          {submitPhase === 'ai_reviewing' && <Loader size={14} className="spin" />}
                          <span>{PHASE_LABELS[submitPhase]}</span>
                        </div>
                      </div>
                    )}

                    {/* 【提交按钮】点击后触发 submitTask 流程 */}
                    <button
                      className="primary-button"
                      onClick={submitTask}
                      disabled={submitPhase !== 'idle' && submitPhase !== 'error'}
                    >
                      <CheckCircle2 size={16} />
                      {submitPhase === 'idle' || submitPhase === 'error'
                        ? (isResubmit ? '重新提交审核' : '提交审核')
                        : PHASE_LABELS[submitPhase]}
                    </button>
                  </>
                ) : (
                  /* 【状态提示】当任务不可提交时显示原因 */
                  <div className="status-hint">
                    {getStatusHint(selected.status) ?? `当前状态「${statusLabel[selected.status] ?? selected.status}」不允许提交。`}
                  </div>
                )}
              </div>
            ) : <Empty text="请选择一个任务后提交凭证。" />}

            {/* 【AI 追问卡片】提交后 AI 可能会追问以验证学习深度 */}
            {aiQuestion && (
              <div className="ai-question-card">
                <p className="ai-question-label"><Bot size={16} /> AI 追问</p>
                <p>{aiQuestion.question}</p>
                <textarea value={aiAnswer} onChange={(e) => setAiAnswer(e.target.value)} placeholder="请输入你的回答（越详细加成越高）..." />
                <div className="row-actions">
                  <button className="primary-button" onClick={submitAiAnswer} disabled={aiAnswering}>{aiAnswering ? '提交中...' : '提交答案'}</button>
                  <button className="secondary-button" onClick={() => setAiQuestion(null)} disabled={aiAnswering}>跳过</button>
                </div>
              </div>
            )}
            {/* 【AI 回答反馈】显示回答后的经验加成信息 */}
            {aiAnswerFeedback && (
              <div className="ai-feedback-banner">
                <Bot size={16} /> {aiAnswerFeedback.feedback}
              </div>
            )}
          </section>
        </>
      )}

      {/* ===== 【提交历史标签页】查看提交记录和发起申诉 ===== */}
      {tab === 'history' && (
        <>
          <section className="panel full">
            <div className="panel-title"><h2>我的提交与申诉</h2></div>
            <div className="task-list">
              {submissions.slice(0, 10).map((submission) => (
                <SubmissionCard
                  key={submission.id}
                  submission={submission}
                  isLoadingDetail={loadingDetailId === submission.id}
                  isAppealing={appealingIds.has(submission.id)}
                  onViewDetail={() => openSubmissionDetail(submission.id)}
                  onAppeal={(reason, urls) => appeal(submission.id, reason, urls)}
                />
              ))}
              {submissions.length === 0 && <Empty text="还没有提交记录。" />}
            </div>
          </section>

          {/* 【审核反馈详情面板】点击"查看反馈"后展示 */}
          {submissionDetail && (
            <section className="panel full">
              <div className="panel-title">
                <h2>审核反馈</h2>
                <button className="icon-button" onClick={() => setSubmissionDetail(null)} title="关闭反馈">×</button>
              </div>
              <SubmissionFeedback detail={submissionDetail} />
            </section>
          )}
        </>
      )}

    </div>
  );
}

/**
 * 【提交记录卡片组件】展示单条提交记录的摘要信息和操作按钮
 * @param submission - 提交记录数据
 * @param isLoadingDetail - 是否正在加载详情
 * @param isAppealing - 是否正在申诉中
 * @param onViewDetail - 查看详情回调
 * @param onAppeal - 发起申诉回调
 *
 * 功能：
 * - 显示提交 ID、关联任务、凭证文本、状态徽章
 * - 显示凭证截图缩略图
 * - 提供"查看反馈"和"发起申诉"按钮
 * - 申诉表单支持文字描述和截图上传
 */
function SubmissionCard({ submission, isLoadingDetail, isAppealing, onViewDetail, onAppeal }: {
  submission: Submission;
  isLoadingDetail: boolean;
  isAppealing: boolean;
  onViewDetail: () => void;
  onAppeal: (reason: string, urls: string[]) => void;
}) {
  /** 【申诉理由】默认文案，用户可修改 */
  const [localAppealReason, setLocalAppealReason] = useState('我已补充说明，请管理员人工复核。');
  /** 【申诉截图】支持粘贴上传 */
  const [appealImages, setAppealImages] = useState<ProofImage[]>([]);
  /** 【申诉表单展开状态】控制申诉表单的显示/隐藏 */
  const [showAppealForm, setShowAppealForm] = useState(false);
  /** 【申诉区域 DOM 引用】用于粘贴事件监听 */
  const appealScopeRef = useRef<HTMLDivElement>(null);
  /** 【是否可申诉】只有被拒绝、需要补充、被拦截的状态才能申诉 */
  const canAppeal = ['AI_REJECTED', 'NEED_MORE', 'MANUAL_REJECTED', 'MODERATION_BLOCKED'].includes(submission.status);
  /** 【关联任务标题】从 submission.task 中获取 */
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
      {/* 【凭证截图缩略图】展示提交的截图 */}
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
      {/* 【申诉表单】展开后显示申诉理由输入和截图上传 */}
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
 * @param hits - RAG 命中记录数组，每条包含来源类型、相似度、摘要等
 *
 * 设计思路：让用户知道 AI 审核时参考了哪些历史数据，增加透明度和信任感。
 * 默认折叠，点击展开查看详细列表。
 */
function RagHitsPanel({ hits }: { hits?: AiReview['ragHits'] }) {
  const [open, setOpen] = useState(false);
  if (!hits || hits.length === 0) return null;
  return (
    <div className="rag-hits" data-testid="rag-hits">
      <button
        type="button"
        className="rag-hits-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
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

/**
 * 【分数等级判定】根据 AI 审核分数返回等级标签和样式类名
 * @param score - AI 审核分数（0-100）
 * @returns 等级标签（通过/边缘/不足）和对应的 CSS 类名
 */
function scoreTier(score: number): { label: string; cls: 'ok' | 'warn' | 'bad' } {
  if (score >= 70) return { label: '通过', cls: 'ok' };
  if (score >= 45) return { label: '边缘', cls: 'warn' };
  return { label: '不足', cls: 'bad' };
}

/**
 * 【分数条组件】展示单项评分（相关性/完整度/质量）的进度条
 * @param label - 评分维度名称
 * @param value - 分数值（0-100）
 */
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
 * @param detail - 提交详情数据，包含提交信息、关联任务、AI 审核结果
 *
 * 展示内容：
 * - 提交元数据（关联任务、状态、学习时长）
 * - 凭证截图
 * - AI 审核分数（总分 + 三项子分）
 * - AI 判断理由和改进建议
 * - RAG 命中记录（AI 参考的历史学习数据）
 * - 异步审核中的等待状态（含耗时提示）
 * - 内容安全拦截原因
 * - 管理员意见
 */
function SubmissionFeedback({ detail }: { detail: SubmissionDetail }) {
  /** 【AI 审核结果】可能为 null（审核中或无审核记录） */
  const review = detail.review as AiReview | null;
  /**
   * 【异步审核计时器】当状态为 AI_REVIEWING 时，每 5 秒更新一次当前时间，
   * 用于计算审核已耗时。超过 3 分钟时显示警告提示。
   *
   * For AI_REVIEWING state: track elapsed minutes since createdAt so the user knows
   * whether the wait is normal (~30s) or stuck (>3min worth flagging).
   */
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (detail.submission.status !== 'AI_REVIEWING') return;
    const t = setInterval(() => setNowMs(Date.now()), 5000);
    return () => clearInterval(t);
  }, [detail.submission.status]);
  /** 【已耗时秒数】从提交创建时间到现在的秒数 */
  const elapsedSec = detail.submission.createdAt
    ? Math.max(0, Math.floor((nowMs - Date.parse(detail.submission.createdAt)) / 1000))
    : 0;

  return (
    <div className="submission-feedback">
      {/* 【提交元数据网格】显示关联任务、状态、学习时长 */}
      <div className="admin-meta-grid">
        <div><span>关联任务</span><strong>{detail.task.title}</strong></div>
        <div><span>提交状态</span><strong>{statusLabel[detail.submission.status] ?? detail.submission.status}</strong></div>
        <div><span>学习时长</span><strong>{detail.submission.studyMinutes} 分钟</strong></div>
      </div>
      {/* 【凭证截图】展示提交的截图证据 */}
      <ProofThumbStrip urls={parseScreenshotUrls(detail.submission)} />
      {review ? (
        <>
          {/* 【审核分数概览】总分和建议经验 */}
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
          {/* 【评分明细】相关性、完整度、质量三项子分的进度条 */}
          <div className="score-breakdown-bars">
            <ScoreBar label="相关性" value={review.relevanceScore} />
            <ScoreBar label="完整度" value={review.completenessScore} />
            <ScoreBar label="质量" value={review.qualityScore} />
          </div>
          {/* 【反馈文本】AI 判断理由和改进建议 */}
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
          {/* 【RAG 命中】AI 参考的历史学习记忆 */}
          <RagHitsPanel hits={review.ragHits} />
        </>
      ) : detail.submission.status === 'AI_REVIEWING' ? (
        /**
         * 【异步审核等待状态】显示"AI 正在思考"的动画和耗时。
         * 超过 30 秒显示已耗时，超过 3 分钟显示警告。
         *
         * Async review in flight — show a calm "AI is thinking" indicator. Page-level
         * listener for `soulous:notification` re-fetches this detail as soon as the
         * review notification fires. After ~3 min surface a hint that something may be
         * wrong so the user doesn't stare at the spinner indefinitely.
         */
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
      {/* 【内容安全拦截原因】如果被拦截，显示原因和处理建议 */}
      {detail.submission.moderationReason && (
        <div className="admin-comment-box">
          <strong>内容安全拦截原因</strong>
          <p>{detail.submission.moderationReason}</p>
          <p className="muted small">可修改凭证后重新提交，或对该提交发起申诉由管理员复核。</p>
        </div>
      )}
      {/* 【管理员意见】人工审核时管理员留下的评论 */}
      {detail.submission.adminComment && (
        <div className="admin-comment-box">
          <strong>管理员意见</strong>
          <p>{detail.submission.adminComment}</p>
        </div>
      )}
    </div>
  );
}
