/**
 * 【自习室页面】FocusPage（原"专注"模块重写）
 *
 * 灵感来自抖音爆款原型 "StudyWithMe AI"。核心理念：一个人学习最难的不是计时，
 * 而是"进入状态"。所以本页不是单纯的番茄钟，而是用「场景 + 环境音 + 沉浸全屏」
 * 帮用户快速进入心流。
 *
 * 两种形态：
 * - **设置态**（无进行中会话）：选场景 → 调音（音乐/环境音）→ 设番茄钟 + 今日目标
 *   + 可选关联任务，点"进入自习室"开始。
 * - **沉浸态**（有进行中会话）：全屏场景背景 + 计时浮层 + 今日目标 + 音量条
 *   + 暂停/继续/结束/放弃 + 宠物陪伴。
 *
 * 保留原专注模块的全部后端能力：start/pause/resume/finish、完成发放宠物经验、
 * 关联任务自动累加实际用时、今日记录与本周趋势。场景/音量/目标等偏好只存
 * localStorage（见 useStudyRoomPrefs），不入库。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  BarChart2, BookOpen, Building2, CheckCircle2, CloudRain, Coffee, Flame, Maximize2,
  Music, Pause, Play, Snowflake, Sunrise, Target, Timer, Trees, Volume2, VolumeX, Waves, Wind, XCircle,
} from 'lucide-react';
import { api } from '../api';
import type { FocusSession, Pet, StudyTask, Summary } from '../types';
import { ClickableAvatar } from '../components/shared';
import { DURATION_PRESETS, getMusicTrack, getScene, MUSIC_TRACKS, SCENES, type AmbientKind, type StudyScene } from '../studyroom/scenes';
import { useStudyRoomPrefs } from '../studyroom/useStudyRoomPrefs';
import { useAudioMixer } from '../studyroom/useAudioMixer';

/**
 * 【格式化时间】将秒数转换为 HH:MM:SS 或 MM:SS，负数显示前导负号（超时）
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
 * 【计算已专注时长】运行中需加上 lastStartedAt 到现在的增量
 */
function currentElapsed(session: FocusSession): number {
  if (session.status === 'RUNNING' && session.lastStartedAt) {
    const extra = Math.floor((Date.now() - new Date(session.lastStartedAt).getTime()) / 1000);
    return session.elapsedSeconds + extra;
  }
  return session.elapsedSeconds;
}

/** 【环境音类型 → 图标】用于场景卡片角标 */
const AMBIENT_ICON: Record<AmbientKind, React.ReactNode> = {
  rain: <CloudRain size={13} />,
  waves: <Waves size={13} />,
  whitenoise: <Volume2 size={13} />,
  wind: <Wind size={13} />,
  forest: <Trees size={13} />,
  silent: <VolumeX size={13} />,
};

/** 【场景 ID → 主题图标】给场景卡片一点辨识度 */
const SCENE_ICON: Record<string, React.ReactNode> = {
  'morning-window': <Sunrise size={15} />,
  'rainy-cafe': <Coffee size={15} />,
  'night-library': <BookOpen size={15} />,
  'seaside-study': <Waves size={15} />,
  'rainy-forest-cabin': <Trees size={15} />,
  'city-night': <Building2 size={15} />,
  'nordic-snow': <Snowflake size={15} />,
  'courtyard-rain': <CloudRain size={15} />,
};

/**
 * 【生成场景背景 CSS】图片层叠在渐变兜底之上：
 * 若图片文件不存在（404），图片层不绘制，下方渐变自然显现，零素材也好看。
 * @param scene 场景
 * @param dim 顶部暗化遮罩强度（沉浸态更暗以突出文字）
 */
function sceneBackground(scene: StudyScene, dim: number): string {
  const overlay = `linear-gradient(180deg, rgba(8,6,4,${dim * 0.5}) 0%, rgba(8,6,4,${dim}) 100%)`;
  const img = scene.image ? `url("${scene.image}"), ` : '';
  return `${overlay}, ${img}${scene.gradient}`;
}

/**
 * 【场景卡片】设置态 STEP1 中的可选场景
 */
function SceneCard({ scene, selected, onPick }: { scene: StudyScene; selected: boolean; onPick: () => void }) {
  return (
    <button
      type="button"
      className={`scene-card${selected ? ' scene-card-active' : ''}`}
      style={{ background: sceneBackground(scene, 0.35) }}
      onClick={onPick}
      aria-pressed={selected}
    >
      <span className="scene-card-top">
        <span className="scene-card-icon">{SCENE_ICON[scene.id] ?? <Sunrise size={15} />}</span>
        <span className="scene-card-ambient">{AMBIENT_ICON[scene.ambient]}</span>
      </span>
      <span className="scene-card-body">
        <span className="scene-card-name">{scene.name}</span>
        <span className="scene-card-mood">{scene.mood}</span>
      </span>
      {selected && <span className="scene-card-check"><CheckCircle2 size={18} /></span>}
    </button>
  );
}

/**
 * 【音量滑块】带图标、开关与 0–100 滑条
 */
function VolumeRow({
  icon, label, hint, value, enabled, onValue, onToggle,
}: {
  icon: React.ReactNode; label: string; hint?: string;
  value: number; enabled: boolean;
  onValue: (v: number) => void; onToggle: () => void;
}) {
  return (
    <div className={`vol-row${enabled ? '' : ' vol-row-off'}`}>
      <button type="button" className="vol-toggle" onClick={onToggle} title={enabled ? '关闭' : '开启'}>
        {enabled ? icon : <VolumeX size={16} />}
      </button>
      <div className="vol-main">
        <div className="vol-head">
          <span className="vol-label">{label}</span>
          {hint && <span className="vol-hint">{hint}</span>}
        </div>
        <input
          type="range" min={0} max={100} value={Math.round(value * 100)}
          className="vol-slider"
          disabled={!enabled}
          onChange={e => onValue(Number(e.target.value) / 100)}
        />
      </div>
      <span className="vol-pct">{enabled ? `${Math.round(value * 100)}%` : '关'}</span>
    </div>
  );
}

/**
 * 【环形计时进度】SVG 进度环，超时变红
 */
function TimerRing({ elapsed, planned, accent }: { elapsed: number; planned: number; accent: string }) {
  const total = planned * 60;
  const progress = Math.min(elapsed / total, 1);
  const r = 92;
  const circ = 2 * Math.PI * r;
  const dash = circ * progress;
  const over = elapsed > total;
  return (
    <svg width="260" height="260" viewBox="0 0 220 220" className="immersive-ring">
      <circle cx="110" cy="110" r={r} fill="none" stroke="rgba(255,255,255,0.16)" strokeWidth="5" />
      <circle
        cx="110" cy="110" r={r} fill="none"
        stroke={over ? '#ff8a7a' : accent}
        strokeWidth="5" strokeLinecap="round"
        strokeDasharray={`${dash} ${circ}`}
        transform="rotate(-90 110 110)"
        style={{ transition: 'stroke-dasharray 0.5s linear' }}
      />
    </svg>
  );
}

/**
 * 【宠物陪伴】沉浸态左下角的小气泡，按状态切换文案
 */
function PetCompanion({ pet, status }: { pet: Pet | null; status: FocusSession['status'] | 'idle' }) {
  const msgs: Record<string, string> = {
    idle: '准备好了吗？一起进入状态~',
    RUNNING: '我在这儿陪你，慢慢来~',
    PAUSED: '歇一会儿，随时回来继续',
  };
  const key = status === 'RUNNING' ? 'RUNNING' : status === 'PAUSED' ? 'PAUSED' : 'idle';
  return (
    <div className="immersive-pet">
      <div className="immersive-pet-avatar">
        {pet?.avatarUrl
          ? <ClickableAvatar url={pet.avatarUrl} alt={pet.name ?? '宠物'} />
          : <span>{(pet?.name ?? '灵')[0]}</span>}
      </div>
      <div className="immersive-pet-text">{msgs[key]}</div>
    </div>
  );
}

/**
 * 【沉浸态自习室】全屏场景 + 计时 + 控制
 */
function ImmersiveRoom({
  session, scene, pet, prefs, audioControls, onUpdate, onPrefsPatch,
}: {
  session: FocusSession;
  scene: StudyScene;
  pet: Pet | null;
  prefs: ReturnType<typeof useStudyRoomPrefs>['prefs'];
  audioControls: React.ReactNode;
  onUpdate: (s: FocusSession) => void;
  onPrefsPatch: (p: Partial<ReturnType<typeof useStudyRoomPrefs>['prefs']>) => void;
}) {
  const [elapsed, setElapsed] = useState(() => currentElapsed(session));
  const [busy, setBusy] = useState(false);
  const [actError, setActError] = useState('');
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // 每秒刷新计时（仅运行中）
  useEffect(() => {
    setElapsed(currentElapsed(session));
    if (session.status === 'RUNNING') {
      if (tickRef.current) clearInterval(tickRef.current);
      tickRef.current = setInterval(() => setElapsed(currentElapsed(session)), 1000);
    }
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, [session]);

  /** 统一执行后端操作，处理忙碌与错误 */
  async function act(fn: () => Promise<FocusSession>) {
    setBusy(true); setActError('');
    try { onUpdate(await fn()); }
    catch (err) { setActError(err instanceof Error ? err.message : '操作失败'); }
    finally { setBusy(false); }
  }

  const total = session.plannedMinutes * 60;
  const over = elapsed > total;
  const remain = over ? -(elapsed - total) : total - elapsed;

  return (
    <div className="immersive-room" style={{ background: sceneBackground(scene, 0.5) }}>
      {/* 顶栏：场景名 + 状态 */}
      <div className="immersive-top">
        <span className="immersive-scene-name">{SCENE_ICON[scene.id]} {scene.name}</span>
        <span className="immersive-status">
          {session.status === 'RUNNING' ? '· 专注中' : session.status === 'PAUSED' ? '· 已暂停' : ''}
        </span>
      </div>

      {/* 中央计时 */}
      <div className="immersive-center">
        <div className="immersive-timer-wrap">
          <TimerRing elapsed={elapsed} planned={session.plannedMinutes} accent={scene.accent} />
          <div className="immersive-timer-inner">
            <div className={`immersive-time${over ? ' immersive-over' : ''}`}>{fmt(remain)}</div>
            <div className="immersive-sub">{over ? 'OVERTIME · 超时' : 'REMAINING · 剩余'}</div>
          </div>
        </div>
        <div className="immersive-elapsed">计划 {session.plannedMinutes} 分钟 · 已专注 {fmt(elapsed)}</div>

        {/* 今日目标（可即时编辑，仅本地） */}
        <div className="immersive-goal">
          <Target size={14} />
          <input
            className="immersive-goal-input"
            placeholder="写下今天想完成的事…"
            value={prefs.goal}
            maxLength={120}
            onChange={e => onPrefsPatch({ goal: e.target.value })}
          />
        </div>
      </div>

      {/* 底部：音量条 + 控制 */}
      <div className="immersive-bottom">
        <div className="immersive-audio">{audioControls}</div>
        <div className="immersive-controls">
          {session.status === 'RUNNING' && (
            <button className="im-btn" disabled={busy} onClick={() => act(() => api.pauseFocus(session.id))}>
              <Pause size={18} /> 暂停
            </button>
          )}
          {session.status === 'PAUSED' && (
            <button className="im-btn im-btn-primary" disabled={busy} onClick={() => act(() => api.resumeFocus(session.id))}>
              <Play size={18} /> 继续
            </button>
          )}
          <button className="im-btn im-btn-primary" disabled={busy} onClick={() => act(() => api.finishFocus(session.id, 'completed'))}>
            <CheckCircle2 size={18} /> 结束学习
          </button>
          <button className="im-btn im-btn-ghost" disabled={busy} onClick={() => act(() => api.finishFocus(session.id, 'aborted'))}>
            <XCircle size={18} /> 放弃
          </button>
        </div>
        {actError && <p className="immersive-error">{actError}</p>}
      </div>

      <PetCompanion pet={pet} status={session.status} />
    </div>
  );
}

/**
 * 【本周专注柱状图】沿用原实现，展示近 7 天专注时长
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
 * 【自习室页面主组件】
 */
export function FocusPage() {
  const { prefs, update } = useStudyRoomPrefs();
  const [active, setActive] = useState<FocusSession | null>(null);
  const [history, setHistory] = useState<FocusSession[]>([]);
  const [pet, setPet] = useState<Pet | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [tasks, setTasks] = useState<StudyTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  // 设置态：关联任务、试听、启动忙碌/错误
  const [linkTaskId, setLinkTaskId] = useState<number | ''>('');
  const [previewing, setPreviewing] = useState(false);
  const [starting, setStarting] = useState(false);
  const [startErr, setStartErr] = useState('');

  const scene = getScene(prefs.sceneId);
  const musicTrack = getMusicTrack(prefs.musicTrackId);

  // 音频：进行中会话或设置态试听时播放当前场景的声音 + 背景音乐
  useAudioMixer({
    ambient: scene.ambient,
    ambientFile: scene.ambientFile,
    musicSrc: musicTrack.src,
    ambientVolume: prefs.ambientVolume,
    musicVolume: prefs.musicVolume,
    ambientEnabled: prefs.ambientEnabled,
    musicEnabled: prefs.musicEnabled,
    playing: !!active || previewing,
  });

  /** 【加载数据】并行拉取活跃会话/历史/摘要/宠物/任务 */
  async function load() {
    setLoading(true); setLoadError('');
    try {
      const [activeRes, sessions, summaryRes, petRes, tasksRes] = await Promise.all([
        api.activeFocus(), api.focusSessions(), api.summary(), api.pet(), api.tasks(),
      ]);
      setActive('id' in activeRes ? activeRes as FocusSession : null);
      setHistory(sessions.filter(s => s.status === 'COMPLETED' || s.status === 'ABORTED'));
      setSummary(summaryRes);
      setPet(petRes);
      setTasks(tasksRes);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载自习室数据失败');
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { void load(); }, []);

  /** 【会话状态更新】完成/放弃则归档，否则更新活跃会话 */
  function handleUpdate(session: FocusSession) {
    if (session.status === 'COMPLETED' || session.status === 'ABORTED') {
      setActive(null);
      setHistory(prev => [session, ...prev]);
      // 完成后刷新宠物/摘要，反映新经验与连续天数
      void load();
    } else {
      setActive(session);
    }
  }

  /** 【可关联任务】只列出活跃状态的任务 */
  const ACTIVE_STATUSES: StudyTask['status'][] = ['TODO', 'DOING', 'PAUSED', 'NEED_MORE', 'AI_REJECTED'];
  const linkable = tasks.filter(t => ACTIVE_STATUSES.includes(t.status));

  /** 【进入自习室】用今日目标或任务名或场景名作标题，启动后端会话 */
  async function enterRoom() {
    setStarting(true); setStartErr('');
    const linked = linkTaskId === '' ? null : tasks.find(t => t.id === linkTaskId);
    const title = (prefs.goal.trim() || linked?.title || `${scene.name} · 自习`).slice(0, 128);
    try {
      const session = await api.startFocus(title, prefs.plannedMinutes, linkTaskId === '' ? null : linkTaskId);
      setPreviewing(false);
      setActive(session);
    } catch (err) {
      setStartErr(err instanceof Error ? err.message : '进入失败');
    } finally {
      setStarting(false);
    }
  }

  /** 【今日统计】 */
  const todayStr = new Date().toDateString();
  const todayCompleted = history.filter(
    s => s.status === 'COMPLETED' && new Date(s.createdAt).toDateString() === todayStr,
  );
  const todayMinutes = todayCompleted.reduce((sum, s) => sum + Math.floor(s.elapsedSeconds / 60), 0);

  /** 【音量控制块】设置态与沉浸态共用 */
  const audioControls = (
    <>
      <VolumeRow
        icon={<Music size={16} />} label="音乐" hint={musicTrack.name}
        value={prefs.musicVolume} enabled={prefs.musicEnabled}
        onValue={v => update({ musicVolume: v })}
        onToggle={() => update({ musicEnabled: !prefs.musicEnabled })}
      />
      <VolumeRow
        icon={AMBIENT_ICON[scene.ambient]} label="背景音" hint={`随场景 · ${scene.name}`}
        value={prefs.ambientVolume} enabled={prefs.ambientEnabled}
        onValue={v => update({ ambientVolume: v })}
        onToggle={() => update({ ambientEnabled: !prefs.ambientEnabled })}
      />
    </>
  );

  if (loading) return <div className="notice">加载中...</div>;
  if (loadError) return <div className="notice error-notice">{loadError}</div>;

  // ===== 沉浸态 =====
  if (active) {
    return (
      <ImmersiveRoom
        session={active}
        scene={scene}
        pet={pet}
        prefs={prefs}
        audioControls={audioControls}
        onUpdate={handleUpdate}
        onPrefsPatch={update}
      />
    );
  }

  // ===== 设置态 =====
  return (
    <div className="studyroom-setup">
      {/* 今日统计条 */}
      <div className="focus-stats-row">
        <div className="focus-stat-chip"><Timer size={16} /><span>今日专注 <strong>{todayMinutes}</strong> 分钟</span></div>
        <div className="focus-stat-chip"><CheckCircle2 size={16} /><span>完成 <strong>{todayCompleted.length}</strong> 次</span></div>
        {summary !== null && (
          <div className="focus-stat-chip focus-streak"><Flame size={16} /><span>连续 <strong>{summary.consecutiveDays}</strong> 天</span></div>
        )}
      </div>

      <div className="studyroom-cols">
        <div className="studyroom-main">
          {/* STEP 1 选场景 */}
          <section className="sr-step">
            <div className="sr-step-head"><span className="sr-step-no">STEP 1</span><h3>选择你的学习场景</h3></div>
            <div className="scene-grid">
              {SCENES.map(s => (
                <SceneCard key={s.id} scene={s} selected={s.id === prefs.sceneId} onPick={() => update({ sceneId: s.id })} />
              ))}
            </div>
          </section>

          {/* STEP 2 调音 */}
          <section className="sr-step">
            <div className="sr-step-head">
              <span className="sr-step-no">STEP 2</span><h3>设置声音氛围</h3>
              <button type="button" className="sr-preview-btn" onClick={() => setPreviewing(p => !p)}>
                {previewing ? <><Pause size={14} /> 停止试听</> : <><Play size={14} /> 试听</>}
              </button>
            </div>
            <div className="sr-audio card">
              {audioControls}
              <div className="sr-track-row">
                <span className="sr-track-label">曲目</span>
                {MUSIC_TRACKS.map(t => (
                  <button key={t.id} type="button"
                    className={prefs.musicTrackId === t.id ? 'sr-track-chip sr-track-on' : 'sr-track-chip'}
                    onClick={() => update({ musicTrackId: t.id })}>
                    {t.name}
                  </button>
                ))}
              </div>
            </div>
          </section>

          {/* STEP 3 番茄钟 + 目标 + 任务 */}
          <section className="sr-step">
            <div className="sr-step-head"><span className="sr-step-no">STEP 3</span><h3>设置番茄钟</h3></div>
            <div className="sr-pomodoro card">
              <div className="preset-row">
                {DURATION_PRESETS.map(p => (
                  <button key={p} type="button"
                    className={prefs.plannedMinutes === p ? 'primary-button small' : 'secondary-button small'}
                    onClick={() => update({ plannedMinutes: p })}>
                    {p} 分钟
                  </button>
                ))}
                <input
                  type="number" min={1} max={180}
                  className="text-input minutes-input"
                  value={prefs.plannedMinutes}
                  onChange={e => update({ plannedMinutes: Number(e.target.value) })}
                />
              </div>

              <label className="field-label">今日目标（可选）</label>
              <input
                className="text-input"
                placeholder="例如：读完第三章、刷两道算法题…"
                value={prefs.goal}
                maxLength={120}
                onChange={e => update({ goal: e.target.value })}
              />

              <label className="field-label">关联任务（可选）</label>
              <select
                className="text-input"
                value={linkTaskId}
                onChange={e => setLinkTaskId(e.target.value === '' ? '' : Number(e.target.value))}
              >
                <option value="">不关联任务（仅记录专注）</option>
                {linkable.map(t => <option key={t.id} value={t.id}>{t.title}</option>)}
              </select>
              <p className="muted small" style={{ margin: '4px 0 0' }}>关联后，完成将自动累加到任务的实际用时</p>

              {startErr && <p className="notice error-notice">{startErr}</p>}
              <button type="button" className="sr-enter-btn" disabled={starting} onClick={enterRoom}
                style={{ background: scene.accent }}>
                <Maximize2 size={18} /> 进入自习室
              </button>
            </div>
          </section>
        </div>

        {/* 侧栏：本周专注 + 今日记录 */}
        <div className="studyroom-sidebar">
          {summary && summary.trend.length > 0 && (
            <section className="panel">
              <div className="sidebar-section-title"><BarChart2 size={15} /><h3>本周专注</h3></div>
              <WeekChart trend={summary.trend} />
            </section>
          )}
          <section className="panel">
            <div className="sidebar-section-title"><CheckCircle2 size={15} /><h3>今日记录</h3></div>
            {todayCompleted.length > 0 ? (
              <div className="session-list">
                {todayCompleted.map(s => (
                  <div key={s.id} className="session-row">
                    <div className="session-row-icon"><CheckCircle2 size={14} className="text-success" /></div>
                    <div className="session-row-body">
                      <span className="session-row-title">{s.title}</span>
                      <span className="muted small">计划 {s.plannedMinutes} 分钟 · 实际 {fmt(s.elapsedSeconds)}</span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="muted small" style={{ margin: 0 }}>还没有完成的专注，进入自习室开始吧~</p>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
