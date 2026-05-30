/**
 * 【专注模式页面】FocusPage
 * 本页面提供番茄钟式的专注计时功能，核心特性：
 * - 开始/暂停/继续/完成/中止专注会话
 * - 实时计时器环形图（带超时提示）
 * - 可关联学习任务，完成专注后自动累加任务实际用时
 * - 本周专注趋势图（柱状图）
 * - 今日专注记录列表
 * - 宠物陪伴气泡（根据专注状态切换文案）
 *
 * 设计思路：专注模式强调"减少干扰"，因此界面简洁，重点突出计时器和控制按钮。
 * 宠物气泡提供情感陪伴，鼓励用户坚持专注。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { BarChart2, CheckCircle2, Circle, Clock, Flame, Pause, Play, Timer, XCircle } from 'lucide-react';
import { api } from '../api';
import type { FocusSession, Pet, StudyTask, Summary } from '../types';
import { ClickableAvatar } from '../components/shared';

/**
 * 【格式化时间】将秒数转换为 HH:MM:SS 或 MM:SS 格式
 * @param seconds - 总秒数，可以是负数（超时时显示负号）
 * @returns 格式化的时间字符串
 *
 * 设计要点：超时时显示负数，让用户清楚知道超出计划时长多少
 */
function fmt(seconds: number): string {
  const abs = Math.abs(seconds);
  const h = Math.floor(abs / 3600);
  const m = Math.floor((abs % 3600) / 60);
  const s = abs % 60;
  const sign = seconds < 0 ? '-' : '';
  if (h > 0) return `${sign}${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${sign}${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * 【计算已专注时长】处理"运行中"状态的实时计算
 * @param session - 专注会话数据
 * @returns 当前已专注的总秒数
 *
 * 逻辑：如果会话正在运行中（RUNNING），需要加上从 lastStartedAt 到现在的额外时间；
 * 否则直接返回已记录的 elapsedSeconds
 */
function currentElapsed(session: FocusSession): number {
  if (session.status === 'RUNNING' && session.lastStartedAt) {
    const extra = Math.floor((Date.now() - new Date(session.lastStartedAt).getTime()) / 1000);
    return session.elapsedSeconds + extra;
  }
  return session.elapsedSeconds;
}

/**
 * 【状态徽章组件】根据专注会话状态显示对应的标签
 * @param status - 会话状态：RUNNING/PAUSED/COMPLETED/ABORTED
 */
function StatusBadge({ status }: { status: FocusSession['status'] }) {
  const map = {
    RUNNING: { label: '专注中', cls: 'badge-running' },
    PAUSED: { label: '已暂停', cls: 'badge-paused' },
    COMPLETED: { label: '已完成', cls: 'badge-completed' },
    ABORTED: { label: '已中止', cls: 'badge-aborted' },
  };
  const { label, cls } = map[status];
  return <span className={`badge ${cls}`}>{label}</span>;
}

/**
 * 【计时器环形图组件】SVG 实现的圆形进度条
 * @param elapsed - 已专注秒数
 * @param planned - 计划分钟数
 *
 * 设计要点：
 * - 使用 SVG circle 的 strokeDasharray 实现进度动画
 * - 超时时圆环变为红色（var(--danger)）
 * - 中心显示剩余时间或超时时间
 * - 底部显示 "REMAINING · 剩余" 或 "OVERTIME · 超时"
 */
function TimerRing({ elapsed, planned }: { elapsed: number; planned: number }) {
  /** 【总时长秒数】计划分钟数转换为秒 */
  const total = planned * 60;
  /** 【进度比例】0-1 之间，超过 1 表示超时 */
  const progress = Math.min(elapsed / total, 1);
  /** 【圆环半径】SVG 坐标系中的半径 */
  const r = 80;
  /** 【圆环周长】用于计算 strokeDasharray */
  const circ = 2 * Math.PI * r;
  /** 【已填充长度】进度对应的弧长 */
  const dash = circ * progress;
  /** 【是否超时】已专注时间超过计划时长 */
  const over = elapsed > total;

  return (
    <svg width="240" height="240" viewBox="0 0 200 200" className="timer-ring">
      {/* 【背景圆环】灰色底圈 */}
      <circle cx="100" cy="100" r={r} fill="none" stroke="var(--line-2)" strokeWidth="6" />
      {/* 【进度圆环】根据进度填充，超时时变红 */}
      <circle
        cx="100" cy="100" r={r} fill="none"
        stroke={over ? 'var(--danger)' : 'var(--ink)'}
        strokeWidth="6"
        strokeDasharray={`${dash} ${circ}`}
        strokeLinecap="round"
        transform="rotate(-90 100 100)"
        style={{ transition: 'stroke-dasharray 0.5s linear' }}
      />
      {/* 【时间文本】中心显示倒计时或超时时间 */}
      <text x="100" y="100" textAnchor="middle" dominantBaseline="central" className="timer-time">
        {fmt(over ? -(elapsed - total) : total - elapsed)}
      </text>
      {/* 【状态标签】显示 "REMAINING · 剩余" 或 "OVERTIME · 超时" */}
      <text x="100" y="132" textAnchor="middle" className="timer-sub">
        {over ? 'OVERTIME · 超时' : 'REMAINING · 剩余'}
      </text>
    </svg>
  );
}

/**
 * 【活跃计时器组件】当有正在进行的专注会话时显示
 * @param session - 当前专注会话
 * @param onUpdate - 会话状态更新回调
 *
 * 功能：
 * - 每秒更新计时器显示
 * - 提供暂停/继续、完成、中止按钮
 * - 操作忙碌时禁用按钮防止重复点击
 */
function ActiveTimer({ session, onUpdate }: { session: FocusSession; onUpdate: (s: FocusSession) => void }) {
  /** 【已专注秒数】实时计算的当前值 */
  const [elapsed, setElapsed] = useState(() => currentElapsed(session));
  const [busy, setBusy] = useState(false);
  const [actError, setActError] = useState('');
  /** 【计时器引用】用于清理 setInterval */
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /**
   * 【启动计时器】每秒更新一次已专注时长
   * 使用 useCallback 缓存，避免 useEffect 无限循环
   */
  const startTick = useCallback(() => {
    if (tickRef.current) clearInterval(tickRef.current);
    tickRef.current = setInterval(() => {
      setElapsed(currentElapsed(session));
    }, 1000);
  }, [session]);

  /**
   * 【会话状态同步】当 session 变化时：
   * 1. 立即更新已专注时长
   * 2. 如果是运行中状态，启动每秒计时器
   * 3. 组件卸载时清理定时器
   */
  useEffect(() => {
    setElapsed(currentElapsed(session));
    if (session.status === 'RUNNING') startTick();
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, [session, startTick]);

  /**
   * 【执行操作】通用的操作执行函数，处理忙碌状态和错误
   * @param fn - 返回新会话状态的异步函数
   */
  async function act(fn: () => Promise<FocusSession>) {
    setBusy(true);
    setActError('');
    try {
      onUpdate(await fn());
    } catch (err) {
      setActError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="focus-active">
      <h2 className="focus-title">{session.title}</h2>
      <p className="muted small">计划 {session.plannedMinutes} 分钟</p>
      {/* 【环形计时器】核心视觉元素 */}
      <TimerRing elapsed={elapsed} planned={session.plannedMinutes} />
      <p className="focus-elapsed muted small">已专注 {fmt(elapsed)}</p>
      {/* 【控制按钮组】暂停/继续、完成、中止 */}
      <div className="focus-controls">
        {session.status === 'RUNNING' && (
          <button className="secondary-button" disabled={busy} onClick={() => act(() => api.pauseFocus(session.id))}>
            <Pause size={16} /> 暂停
          </button>
        )}
        {session.status === 'PAUSED' && (
          <button className="primary-button" disabled={busy} onClick={() => act(() => api.resumeFocus(session.id))}>
            <Play size={16} /> 继续
          </button>
        )}
        <button className="primary-button" disabled={busy} onClick={() => act(() => api.finishFocus(session.id, 'completed'))}>
          <CheckCircle2 size={16} /> 完成
        </button>
        <button className="danger-button" disabled={busy} onClick={() => act(() => api.finishFocus(session.id, 'aborted'))}>
          <XCircle size={16} /> 中止
        </button>
      </div>
      {actError && <p className="notice error-notice">{actError}</p>}
    </div>
  );
}

/**
 * 【开始专注表单组件】设置专注主题、时长和关联任务
 * @param tasks - 可关联的学习任务列表
 * @param onStarted - 专注会话启动成功的回调
 *
 * 设计要点：
 * - 支持快捷时长预设（15/25/45/60 分钟）
 * - 可选择关联任务，完成专注后自动累加任务用时
 * - 选择任务时自动填充主题（如果主题为空）
 */
function StartForm({ tasks, onStarted }: { tasks: StudyTask[]; onStarted: (s: FocusSession) => void }) {
  const [title, setTitle] = useState('');
  const [minutes, setMinutes] = useState(25);
  /** 【关联任务 ID】空字符串表示不关联 */
  const [taskId, setTaskId] = useState<number | ''>('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  /** 【可关联的任务状态】只有这些状态的任务才能被关联 */
  const ACTIVE_STATUSES: StudyTask['status'][] = ['TODO', 'DOING', 'PAUSED', 'NEED_MORE', 'AI_REJECTED'];
  const linkable = tasks.filter(t => ACTIVE_STATUSES.includes(t.status));

  /**
   * 【选择任务】如果主题为空，自动用任务标题填充
   */
  function onPickTask(id: number | '') {
    setTaskId(id);
    if (id !== '' && !title.trim()) {
      const t = tasks.find(x => x.id === id);
      if (t) setTitle(t.title);
    }
  }

  /**
   * 【提交表单】校验主题非空后调用 API 启动专注会话
   */
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) { setErr('请输入专注主题'); return; }
    setBusy(true);
    setErr('');
    try {
      const session = await api.startFocus(title.trim(), minutes, taskId === '' ? null : taskId);
      onStarted(session);
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : '启动失败');
    } finally {
      setBusy(false);
    }
  }

  /** 【快捷时长预设】常用的番茄钟时长 */
  const presets = [15, 25, 45, 60];

  return (
    <form className="focus-start-form card" onSubmit={submit}>
      <div className="focus-start-icon"><Timer size={24} /></div>
      <h2 style={{ textAlign: 'center' }}>开始 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>专注</em></h2>
      <p style={{ textAlign: 'center' }}>设定主题和时长，专注期间减少干扰</p>
      {/* 【关联任务选择器】可选，不关联则仅记录专注时长 */}
      <label className="field-label">关联任务（可选）</label>
      <select
        className="text-input"
        value={taskId}
        onChange={e => onPickTask(e.target.value === '' ? '' : Number(e.target.value))}
      >
        <option value="">不关联任务（仅记录专注）</option>
        {linkable.map(t => (
          <option key={t.id} value={t.id}>{t.title}</option>
        ))}
      </select>
      <p className="muted small" style={{ margin: '4px 0 8px' }}>
        关联后，完成专注将自动累加到任务的实际用时
      </p>
      {/* 【专注主题输入】最多 128 字符 */}
      <label className="field-label">专注主题</label>
      <input
        className="text-input"
        placeholder="例如：阅读第三章、完成 LeetCode 两题..."
        value={title}
        onChange={e => setTitle(e.target.value)}
        maxLength={128}
      />
      {/* 【时长选择】快捷预设 + 自定义输入 */}
      <label className="field-label">时长（分钟）</label>
      <div className="preset-row">
        {presets.map(p => (
          <button key={p} type="button"
            className={minutes === p ? 'primary-button small' : 'secondary-button small'}
            onClick={() => setMinutes(p)}>
            {p}
          </button>
        ))}
        <input
          type="number" min={1} max={180}
          className="text-input minutes-input"
          value={minutes}
          onChange={e => setMinutes(Number(e.target.value))}
        />
      </div>
      {err && <p className="notice error-notice">{err}</p>}
      <button type="submit" className="primary-button full-width" disabled={busy}>
        <Play size={16} /> 开始专注
      </button>
    </form>
  );
}

/**
 * 【专注记录行组件】展示单条专注会话的摘要信息
 * @param session - 专注会话数据
 * 用于今日记录列表
 */
function SessionRow({ session }: { session: FocusSession }) {
  const elapsed = session.elapsedSeconds;
  /** 【状态图标】不同状态使用不同的图标和颜色 */
  const statusIcon = {
    RUNNING: <Circle size={14} className="text-accent" />,
    PAUSED: <Pause size={14} />,
    COMPLETED: <CheckCircle2 size={14} className="text-success" />,
    ABORTED: <XCircle size={14} className="text-muted" />,
  }[session.status];

  return (
    <div className="session-row">
      <div className="session-row-icon">{statusIcon}</div>
      <div className="session-row-body">
        <span className="session-row-title">{session.title}</span>
        <span className="muted small">计划 {session.plannedMinutes} 分钟 · 实际 {fmt(elapsed)}</span>
      </div>
      <StatusBadge status={session.status} />
      <span className="muted small session-date">{new Date(session.createdAt).toLocaleDateString('zh-CN')}</span>
    </div>
  );
}

/**
 * 【本周专注柱状图组件】展示近 7 天的专注时长趋势
 * @param trend - 趋势数据数组，每项包含 date 和 focusMinutes
 *
 * 设计要点：
 * - 最后 7 天的数据
 * - 今天的柱子高亮显示
 * - 空数据时显示 3px 的占位条
 * - Y 轴自适应最大值
 */
function WeekChart({ trend }: { trend: Summary['trend'] }) {
  const last7 = trend.slice(-7);
  const max = Math.max(...last7.map(d => d.focusMinutes), 1);
  const today = new Date().toISOString().slice(0, 10);
  const dayLabels = ['日', '一', '二', '三', '四', '五', '六'];

  return (
    <div className="week-chart">
      {last7.map(d => {
        const isToday = d.date === today;
        const pct = d.focusMinutes > 0 ? Math.max((d.focusMinutes / max) * 100, 10) : 0;
        const dow = dayLabels[new Date(d.date + 'T12:00:00').getDay()];
        return (
          <div key={d.date} className="wc-col">
            <span className="wc-minutes">{d.focusMinutes > 0 ? d.focusMinutes : ''}</span>
            <div className="wc-bar-wrap">
              <div
                className={`wc-bar${isToday ? ' wc-today' : ''}${pct === 0 ? ' wc-empty' : ''}`}
                style={{ height: pct === 0 ? '3px' : `${pct}%` }}
                title={`${d.date}: ${d.focusMinutes} 分钟`}
              />
            </div>
            <span className={`wc-label${isToday ? ' wc-label-today' : ''}`}>{dow}</span>
          </div>
        );
      })}
    </div>
  );
}

/**
 * 【宠物气泡组件】在专注页面显示宠物的陪伴文案
 * @param pet - 宠物数据
 * @param active - 当前活跃的专注会话
 *
 * 设计要点：根据专注状态切换文案，营造陪伴感
 * - 空闲时：鼓励开始专注
 * - 运行中：加油鼓励
 * - 暂停时：安慰休息
 */
function PetBubble({ pet, active }: { pet: Pet | null; active: FocusSession | null }) {
  const msgs: Record<string, string> = {
    idle: '准备好了吗？让我们一起专注！',
    RUNNING: '加油！我一直在陪着你~',
    PAUSED: '先休息一下，随时可以继续',
  };
  const key = active?.status === 'RUNNING' ? 'RUNNING' : active?.status === 'PAUSED' ? 'PAUSED' : 'idle';

  return (
    <div className={`pet-bubble${key === 'RUNNING' ? ' pet-running' : ''}`}>
      <div className="pet-bubble-avatar">
        {pet?.avatarUrl
          ? <ClickableAvatar url={pet.avatarUrl} alt={pet.name ?? '宠物'} />
          : <span>{(pet?.name ?? '灵')[0]}</span>}
      </div>
      <div className="pet-bubble-body">
        <span className="pet-bubble-name">{pet?.name ?? '小灵'}</span>
        <span className="pet-bubble-text">{msgs[key]}</span>
      </div>
    </div>
  );
}

/**
 * 【专注页面主组件】
 *
 * 数据加载：并行获取活跃会话、历史记录、摘要、宠物、任务列表
 * 状态管理：
 * - active: 当前活跃的专注会话（null 表示空闲）
 * - history: 已完成/中止的历史记录
 * - pet/summary/tasks: 宠物、摘要、任务数据
 */
export function FocusPage() {
  const [active, setActive] = useState<FocusSession | null>(null);
  const [history, setHistory] = useState<FocusSession[]>([]);
  const [pet, setPet] = useState<Pet | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [tasks, setTasks] = useState<StudyTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  /**
   * 【加载页面数据】并行请求 5 个接口，提升加载速度
   * activeFocus: 获取当前活跃的专注会话
   * focusSessions: 获取历史专注记录
   * summary: 获取今日摘要（用于连续天数）
   * pet: 获取宠物数据（用于气泡）
   * tasks: 获取任务列表（用于关联选择）
   */
  async function load() {
    setLoading(true);
    setLoadError('');
    try {
      const [activeRes, sessions, summaryRes, petRes, tasksRes] = await Promise.all([
        api.activeFocus(),
        api.focusSessions(),
        api.summary(),
        api.pet(),
        api.tasks(),
      ]);
      setActive('id' in activeRes ? activeRes as FocusSession : null);
      setHistory(sessions.filter(s => s.status === 'COMPLETED' || s.status === 'ABORTED'));
      setSummary(summaryRes);
      setPet(petRes);
      setTasks(tasksRes);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载专注数据失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  /**
   * 【处理会话状态更新】当专注会话状态变化时更新页面状态
   * 如果是完成或中止，清除活跃会话并添加到历史记录
   * 否则更新活跃会话状态
   */
  function handleUpdate(session: FocusSession) {
    if (session.status === 'COMPLETED' || session.status === 'ABORTED') {
      setActive(null);
      setHistory(prev => [session, ...prev]);
    } else {
      setActive(session);
    }
  }

  /** 【今日统计】计算今日完成的专注次数和总时长 */
  const todayStr = new Date().toDateString();
  const todayCompleted = history.filter(
    s => s.status === 'COMPLETED' && new Date(s.createdAt).toDateString() === todayStr
  );
  const todayMinutes = todayCompleted.reduce((sum, s) => sum + Math.floor(s.elapsedSeconds / 60), 0);

  if (loading) return <div className="notice">加载中...</div>;
  if (loadError) return <div className="notice error-notice">{loadError}</div>;

  return (
    <div className="focus-page">
      {/* ===== 【今日统计栏】专注时长、完成次数、连续天数 ===== */}
      <div className="focus-stats-row">
        <div className="focus-stat-chip">
          <Clock size={16} />
          <span>今日专注 <strong>{todayMinutes}</strong> 分钟</span>
        </div>
        <div className="focus-stat-chip">
          <CheckCircle2 size={16} />
          <span>完成 <strong>{todayCompleted.length}</strong> 次</span>
        </div>
        {summary !== null && (
          <div className="focus-stat-chip focus-streak">
            <Flame size={16} />
            <span>连续 <strong>{summary.consecutiveDays}</strong> 天</span>
          </div>
        )}
      </div>

      {/* ===== 【主内容区】左侧计时器 + 右侧边栏 ===== */}
      <div className="focus-cols">
        <div className="focus-main">
          {/* 【宠物气泡】显示在计时器上方 */}
          <PetBubble pet={pet} active={active} />
          {/* 【计时器或开始表单】根据是否有活跃会话切换显示 */}
          {active ? (
            <ActiveTimer session={active} onUpdate={handleUpdate} />
          ) : (
            <StartForm tasks={tasks} onStarted={s => setActive(s)} />
          )}
        </div>

        <div className="focus-sidebar">
          {/* 【本周专注趋势】柱状图展示近 7 天数据 */}
          {summary && summary.trend.length > 0 && (
            <section className="panel">
              <div className="sidebar-section-title">
                <BarChart2 size={15} />
                <h3>本周专注</h3>
              </div>
              <WeekChart trend={summary.trend} />
            </section>
          )}

          {/* 【今日记录列表】展示今天已完成的专注会话 */}
          <section className="panel">
            <div className="sidebar-section-title">
              <CheckCircle2 size={15} />
              <h3>今日记录</h3>
            </div>
            {todayCompleted.length > 0 ? (
              <div className="session-list">
                {todayCompleted.map(s => <SessionRow key={s.id} session={s} />)}
              </div>
            ) : (
              <p className="muted small" style={{ margin: 0 }}>还没有完成的专注，加油！</p>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
