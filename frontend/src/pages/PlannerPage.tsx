/**
 * 【AI 对话式拆解页面】PlannerPage
 * 本页面提供 AI 驱动的学习目标拆解功能：
 * - 输入学习目标，AI 通过对话引导用户拆解为可执行任务
 * - 目标管理：查看、编辑、删除、推进目标
 * - 目标详情：查看历史会话和关联任务
 * - AI 进度条：展示 AI 拆解过程的进度
 *
 * 核心交互流程：
 * 1. 用户输入学习目标（如"3 个月内通过日语 N4"）
 * 2. AI 通过对话引导用户细化目标，生成可执行任务
 * 3. 任务写入目标列表，用户可持续推进
 * 4. 支持多次对话（check-in）来更新进度和调整计划
 *
 * 设计要点：
 * - 目标有状态机：ACTIVE -> PAUSED/ACHIEVED/ABANDONED
 * - 目标可软删除，关联任务会保留并解绑
 * - 支持外部缓存（cache/setCache），页面切换后不丢失输入
 */
import React, { useEffect, useState } from 'react';
import { Info, MessageSquarePlus, Pencil, RotateCcw, Sparkles, Trash2 } from 'lucide-react';
import { api } from '../api';
import type { GoalSummary, SessionView } from '../types';
import { PlanningSessionChat } from '../components/PlanningSessionChat';
import { GoalDetailPanel } from '../components/GoalDetailPanel';
import { AiProgressBar } from '../components/AiProgressBar';

/** 【目标状态列表】用于编辑表单的下拉选择 */
const GOAL_STATUSES: GoalSummary['status'][] = ['ACTIVE', 'PAUSED', 'ACHIEVED', 'ABANDONED'];

/**
 * 【目标状态徽章映射】将目标状态转换为中文标签和任务模块同款徽章样式类
 * 复用 styles.css 的 badge 配色：进行中=amber，暂停=warn，达成=success，放弃=danger
 */
const GOAL_STATUS_META: Record<GoalSummary['status'], { label: string; cls: string }> = {
  ACTIVE: { label: '进行中', cls: 'badge-running' },
  PAUSED: { label: '已暂停', cls: 'badge-paused' },
  ACHIEVED: { label: '已达成', cls: 'badge-completed' },
  ABANDONED: { label: '已放弃', cls: 'badge-aborted' },
  ARCHIVED: { label: '已归档', cls: '' }
};

/**
 * 【格式化相对时间】将 ISO 日期字符串转换为相对时间描述
 * @param iso - ISO 日期字符串
 * @returns 如 "刚刚"、"5 分钟前"、"2 小时前"、"3 天前" 或具体日期
 */
function formatRelative(iso: string): string {
  const d = new Date(iso);
  const diffMs = Date.now() - d.getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return '刚刚';
  if (mins < 60) return `${mins} 分钟前`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} 天前`;
  return d.toISOString().slice(0, 10);
}

/**
 * 【AI 提供商标签映射】将后端 provider 标识转换为用户友好的名称
 */
const providerLabel: Record<string, string> = {
  mock: '规则模拟',
  anthropic: 'Anthropic Claude',
  openai: 'OpenAI 兼容'
};

/**
 * 【规划器缓存类型】用于在页面切换时保留用户输入
 * 目前只缓存目标输入框的内容
 */
export interface PlannerCache {
  goal: string;
}

/**
 * 【规划器页面主组件】
 * @param onRefresh - 刷新父组件数据的回调
 * @param cache - 外部缓存（可选），用于跨页面保持状态
 * @param setCache - 更新外部缓存的回调（可选）
 *
 * 核心状态：
 * - goalList: 目标列表
 * - session: 当前活跃的 AI 对话会话
 * - editingId/editDraft: 编辑中的目标和草稿
 * - detailGoalId: 查看详情的目标 ID
 */
export function PlannerPage({ onRefresh, cache, setCache }: {
  onRefresh: () => void;
  cache?: PlannerCache;
  setCache?: (next: PlannerCache | ((prev: PlannerCache) => PlannerCache)) => void;
}) {
  /**
   * 【内部缓存】当外部未提供 cache/setCache 时使用内部状态
   * 这种设计允许组件在有/无外部缓存的情况下都能工作
   */
  const [internalCache, setInternalCache] = useState<PlannerCache>({ goal: '' });
  const effectiveCache = cache ?? internalCache;
  const updateCache = setCache ?? setInternalCache;
  const { goal } = effectiveCache;
  const setGoal = (next: string) => updateCache((prev) => ({ ...prev, goal: next }));

  /** 【AI 信息】当前使用的 AI 提供商和模型 */
  const [info, setInfo] = useState<{ provider: string; model: string; available: string } | null>(null);
  const [goalList, setGoalList] = useState<GoalSummary[]>([]);
  /** 【目标筛选】ACTIVE=进行中, ALL=全部 */
  const [goalFilter, setGoalFilter] = useState<'ACTIVE' | 'ALL'>('ACTIVE');
  /** 【当前 AI 对话会话】非 null 时显示聊天界面 */
  const [session, setSession] = useState<SessionView | null>(null);
  const [sessionLoading, setSessionLoading] = useState(false);
  const [sessionError, setSessionError] = useState('');
  /** 【编辑中的目标 ID】非 null 时显示编辑表单 */
  const [editingId, setEditingId] = useState<number | null>(null);
  /** 【编辑草稿】编辑表单的临时数据 */
  const [editDraft, setEditDraft] = useState<{ title: string; targetDate: string; status: GoalSummary['status'] }>({ title: '', targetDate: '', status: 'ACTIVE' });
  const [editError, setEditError] = useState('');
  const [editBusy, setEditBusy] = useState(false);
  /** 【正在删除的目标 ID】显示删除中状态 */
  const [deletingId, setDeletingId] = useState<number | null>(null);
  /** 【确认删除的目标 ID】非 null 时显示二次确认 */
  const [confirmDeleteGoalId, setConfirmDeleteGoalId] = useState<number | null>(null);
  const [deleteError, setDeleteError] = useState('');
  /** 【查看详情的目标 ID】非 null 时显示详情面板 */
  const [detailGoalId, setDetailGoalId] = useState<number | null>(null);

  /**
   * 【初始化加载】组件挂载和筛选条件变化时加载数据
   * 1. 获取 AI 信息（提供商、模型）
   * 2. 获取目标列表
   */
  useEffect(() => {
    api.aiInfo().then(setInfo).catch(() => setInfo(null));
    refreshGoals();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [goalFilter]);

  /**
   * 【刷新目标列表】根据当前筛选条件重新加载目标数据
   */
  async function refreshGoals() {
    try {
      const list = goalFilter === 'ALL' ? await api.sessionAllGoals() : await api.sessionActiveGoals();
      setGoalList(list);
    } catch { /* ignore */ }
  }

  /**
   * 【开始新目标对话】用户输入目标后，启动 AI 对话会话
   * AI 会分析目标并生成可执行的任务草案
   */
  async function startNewGoal() {
    setSessionError(''); setSessionLoading(true);
    try {
      const next = await api.sessionStartNewGoal(goal);
      setSession(next);
      setGoal('');
    } catch (err) {
      setSessionError(err instanceof Error ? err.message : '启动会话失败');
    } finally {
      setSessionLoading(false);
    }
  }

  /**
   * 【进入编辑模式】将目标数据填充到编辑草稿
   */
  function beginEdit(g: GoalSummary) {
    setEditError('');
    setEditingId(g.id);
    setEditDraft({ title: g.title, targetDate: g.targetDate ?? '', status: g.status });
  }

  /** 【取消编辑】清除编辑状态 */
  function cancelEdit() {
    setEditingId(null);
    setEditError('');
  }

  /**
   * 【保存编辑】只提交有变化的字段（patch 模式）
   * 支持修改标题、目标日期、状态
   * 清除目标日期时设置 clearTargetDate = true
   */
  async function saveEdit(id: number, original: GoalSummary) {
    setEditBusy(true); setEditError('');
    try {
      const patch: Parameters<typeof api.updateGoal>[1] = {};
      const newTitle = editDraft.title.trim();
      if (!newTitle) throw new Error('标题不能为空');
      if (newTitle !== original.title) patch.title = newTitle;
      const newDate = editDraft.targetDate.trim();
      const oldDate = original.targetDate ?? '';
      if (newDate !== oldDate) {
        if (!newDate) patch.clearTargetDate = true;
        else patch.targetDate = newDate;
      }
      if (editDraft.status !== original.status) patch.status = editDraft.status;
      if (Object.keys(patch).length > 0) {
        await api.updateGoal(id, patch);
      }
      setEditingId(null);
      await refreshGoals();
      onRefresh();
    } catch (err) {
      setEditError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setEditBusy(false);
    }
  }

  /**
   * 【删除目标】软删除目标，关联任务保留并解绑
   * 如果删除的是正在编辑的目标，同时清除编辑状态
   */
  async function removeGoal(id: number) {
    setConfirmDeleteGoalId(null);
    setDeleteError('');
    setDeletingId(id);
    try {
      await api.deleteGoal(id);
      if (editingId === id) setEditingId(null);
      await refreshGoals();
      onRefresh();
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setDeletingId(null);
    }
  }

  /**
   * 【开始推进对话】对已有目标启动新的 AI 对话会话（check-in）
   * AI 会查看目标进度，帮助用户调整计划或生成新任务
   */
  async function startCheckIn(goalId: number) {
    setSessionError(''); setSessionLoading(true);
    try {
      const next = await api.sessionStartCheckIn(goalId);
      setSession(next);
    } catch (err) {
      setSessionError(err instanceof Error ? err.message : '启动会话失败');
    } finally {
      setSessionLoading(false);
    }
  }

  // 【目标详情独立页】点击「详情」后整页切换到目标详情，而非在列表下方内联展开
  if (detailGoalId !== null && !session) {
    return (
      <GoalDetailPanel
        goalId={detailGoalId}
        onBack={() => { setDetailGoalId(null); refreshGoals(); }}
        onOpenSession={(view) => { setSession(view); }}
        onChanged={() => { refreshGoals(); onRefresh(); }}
      />
    );
  }

  return (
    <div className="panel">
      {/* ===== 【页面标题和 AI 信息】 ===== */}
      <div className="panel-title">
        <h2>AI <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>对话式拆解</em></h2>
        {info && (
          <span className="chip" title={info.model || ''}>
            <Sparkles size={11} /> {providerLabel[info.provider] ?? info.provider}
            {info.available === 'true' && info.model ? ` · ${info.model}` : ''}
          </span>
        )}
      </div>

      <div className="muted small" style={{ marginBottom: 8 }}>
        输入一个学习目标，AI 会通过对话引导你拆解为可执行任务，并写入下方的目标列表持续推进。
      </div>

      {/* ===== 【目标输入区域】 ===== */}
      <div className="planner-input">
        <input
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
          disabled={sessionLoading || !!session}
          placeholder="例如：3 个月内通过日语 N4"
          onKeyDown={(e) => { if (e.key === 'Enter' && goal.trim() && !session) startNewGoal(); }}
        />
        <button
          className="primary-button"
          disabled={sessionLoading || !goal.trim() || !!session}
          onClick={startNewGoal}
        >
          <MessageSquarePlus size={14} /> 开始对话拆解
        </button>
      </div>

      {/* 【AI 进度条】展示 AI 拆解过程的进度和提示 */}
      <AiProgressBar
        active={sessionLoading && !session}
        label="AI 正在拆解目标..."
        hint="正在调用大模型分析你的目标，并生成可执行任务草案，请稍候。"
        estimateMs={10000}
      />

      {sessionError && <div className="form-error">{sessionError}</div>}

      {/* ===== 【目标列表区域】 ===== */}
      <div className="panel-title" style={{ marginTop: 16, alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>我的目标</h3>
        <div className="row-actions" style={{ gap: 6 }}>
          <button
            className={goalFilter === 'ACTIVE' ? 'primary-button' : 'secondary-button'}
            onClick={() => setGoalFilter('ACTIVE')}
          >进行中{goalFilter === 'ACTIVE' ? `（${goalList.length}）` : ''}</button>
          <button
            className={goalFilter === 'ALL' ? 'primary-button' : 'secondary-button'}
            onClick={() => setGoalFilter('ALL')}
          >全部</button>
        </div>
      </div>

      {/* 【空状态提示】 */}
      {goalList.length === 0 && (
        <div className="muted small" style={{ padding: '8px 0' }}>
          {goalFilter === 'ACTIVE' ? '暂无进行中的目标。用上方输入框开启一个吧。' : '还没有任何目标。'}
        </div>
      )}

      {/* 【目标卡片列表】仿任务模块的 task-list / task-card 样式 */}
      {goalList.length > 0 && (
      <div className="task-list" style={{ marginTop: 8 }}>
      {goalList.map((g) => {
        const isTerminal = g.status === 'ACHIEVED' || g.status === 'ABANDONED';
        const isEditing = editingId === g.id;
        const isDeleting = deletingId === g.id;
        const isConfirmingDelete = confirmDeleteGoalId === g.id;
        const statusMeta = GOAL_STATUS_META[g.status] ?? { label: g.status, cls: '' };
        return (
          <div key={g.id} className="task-card">
            {/* 【目标标题 + 状态徽章】仿任务模块的 task-row 结构 */}
            <div className="task-row">
              <div>
                <strong>{g.title}</strong>
                <p>
                  任务 {g.completedTasks}/{g.totalTasks} 完成
                  {g.openTasks > 0 ? ` · ${g.openTasks} 待办` : ''}
                  {' · '}已对话 {g.sessionCount} 次
                  {g.lastSessionAt ? ` · 上次：${formatRelative(g.lastSessionAt)}` : ''}
                  {g.targetDate ? ` · 📅 ${g.targetDate}` : ''}
                </p>
              </div>
              <span className={`badge ${statusMeta.cls}`}>{statusMeta.label}</span>
            </div>
            {/* 【操作按钮组】推进、详情、编辑、删除 */}
            <div className="row-actions">
                <button
                  className="primary-button"
                  disabled={sessionLoading || !!session || isTerminal || isEditing || isDeleting}
                  onClick={() => startCheckIn(g.id)}
                  title={isTerminal ? `目标已${g.status === 'ACHIEVED' ? '达成' : '放弃'}` : '继续推进这个目标'}
                >
                  <RotateCcw size={13} /> 推进
                </button>
                <button
                  className="secondary-button"
                  disabled={isDeleting || isEditing}
                  onClick={() => setDetailGoalId(g.id)}
                  title="查看历史会话与关联任务"
                >
                  <Info size={13} /> 详情
                </button>
                <button
                  className="secondary-button"
                  disabled={isDeleting}
                  onClick={() => (isEditing ? cancelEdit() : beginEdit(g))}
                  aria-label={`编辑 ${g.title}`}
                  title="编辑目标"
                >
                  <Pencil size={13} /> {isEditing ? '取消' : '编辑'}
                </button>
                {/* 【删除按钮】带二次确认 */}
                {isConfirmingDelete ? (
                  <>
                    <span className="muted small" style={{ alignSelf: 'center' }}>确认删除？</span>
                    <button
                      className="secondary-button"
                      style={{ color: 'var(--danger, #e55)' }}
                      disabled={isDeleting}
                      onClick={() => removeGoal(g.id)}
                    >
                      确认
                    </button>
                    <button className="secondary-button" onClick={() => setConfirmDeleteGoalId(null)}>取消</button>
                  </>
                ) : (
                  <button
                    className="secondary-button"
                    disabled={isDeleting || isEditing}
                    onClick={() => { setDeleteError(''); setConfirmDeleteGoalId(g.id); }}
                    aria-label={`删除 ${g.title}`}
                    title={`删除目标（软删除）\n关联 ${g.totalTasks} 个任务（${g.openTasks} 待办）会保留并解绑`}
                  >
                    <Trash2 size={13} /> {isDeleting ? '删除中…' : '删除'}
                  </button>
                )}
              </div>
            {/* 【编辑表单】展开时显示标题、目标日期、状态编辑 */}
            {isEditing && (
              <div style={{ marginTop: 10, padding: 10, background: 'var(--surface-2)', borderRadius: 6, display: 'grid', gap: 8 }}>
                <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
                  <span className="muted">标题</span>
                  <input
                    value={editDraft.title}
                    onChange={(e) => setEditDraft((d) => ({ ...d, title: e.target.value }))}
                    maxLength={200}
                  />
                </label>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <label style={{ display: 'grid', gap: 4, fontSize: 12, flex: '1 1 160px' }}>
                    <span className="muted">目标日期</span>
                    <input
                      type="date"
                      value={editDraft.targetDate}
                      onChange={(e) => setEditDraft((d) => ({ ...d, targetDate: e.target.value }))}
                    />
                  </label>
                  <label style={{ display: 'grid', gap: 4, fontSize: 12, flex: '1 1 120px' }}>
                    <span className="muted">状态</span>
                    <select
                      value={editDraft.status}
                      onChange={(e) => setEditDraft((d) => ({ ...d, status: e.target.value as GoalSummary['status'] }))}
                    >
                      {GOAL_STATUSES.map((s) => (
                        <option key={s} value={s}>{s}</option>
                      ))}
                    </select>
                  </label>
                </div>
                {editError && <div className="form-error">{editError}</div>}
                <div className="row-actions" style={{ justifyContent: 'flex-end' }}>
                  <button className="secondary-button" disabled={editBusy} onClick={cancelEdit}>取消</button>
                  <button className="primary-button" disabled={editBusy} onClick={() => saveEdit(g.id, g)}>
                    {editBusy ? '保存中…' : '保存'}
                  </button>
                </div>
              </div>
            )}
          </div>
        );
      })}
      </div>
      )}

      {deleteError && <div className="form-error" style={{ marginTop: 8 }}>{deleteError}</div>}

      {/* 【AI 对话会话界面】与 AI 进行目标拆解对话 */}
      {session && (
        <PlanningSessionChat
          session={session}
          onClose={() => { setSession(null); refreshGoals(); onRefresh(); }}
          onCommitted={() => { refreshGoals(); onRefresh(); }}
        />
      )}
    </div>
  );
}
