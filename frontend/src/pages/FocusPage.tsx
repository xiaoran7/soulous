/**
 * 【自习室页面】FocusPage
 *
 * 理念：不是强迫学习的番茄钟，而是"随心学习"——想学就开始，计时**往上加**（正计时秒表），
 * 没有目标时长、没有打卡压迫。选场景 + 调氛围（环境音/音乐）+ 写下今日目标，点「进入自习室」
 * 即进入**全屏沉浸态**（隐藏侧边栏与顶栏，由外壳的抽屉重新唤回）。
 *
 * 保留底层后端能力：start/pause/resume/finish、完成发放宠物经验、关联任务累加用时。
 * 场景/音量/目标/曲目偏好存 localStorage；用户上传的自定义场景图与音乐存 IndexedDB。
 * 声音遵循浏览器自动播放策略：需用户显式点击「开启声音」才出声。
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity, ArrowRight, BookOpen, Bot, Building2, CalendarCheck, CalendarRange, CheckCircle2, ClipboardList,
  CloudRain, Coffee, Maximize2, Minimize2, Music, Pause, Play, Plus,
  Snowflake, Sunrise, Target, Trees, Users, Volume2, VolumeX, Waves, Wind, X,
} from 'lucide-react';
import { api, ApiError } from '../api';
import type { FocusSession, Pet, RoomDetail, StudyTask, Summary } from '../types';
import { animationForPet, clampAnimation, petNick } from '../components/shared';
import {
  AMBIENT_KINDS, CUSTOM_SCENE_GRADIENT, DURATION_PRESETS, getMusicTrack, getScene, MUSIC_TRACKS, SCENES,
  type AmbientKind, type MusicTrack, type StudyScene,
} from '../studyroom/scenes';
import { useStudyRoomPrefs } from '../studyroom/useStudyRoomPrefs';
import { useAudioMixer } from '../studyroom/useAudioMixer';
import { addCustom, deleteCustom, listCustom, type CustomItem } from '../studyroom/customStore';
import { SharedRooms } from '../studyroom/SharedRooms';
import { PetSprite } from '../PetSprite';

/** 【格式化为正计时】秒 → H:MM:SS 或 MM:SS（始终非负，正计时不会出现负数） */
function fmt(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

/** 【已专注秒数】运行中加上 lastStartedAt 到现在的增量 */
function currentElapsed(session: FocusSession): number {
  if (session.status === 'RUNNING' && session.lastStartedAt) {
    const extra = Math.floor((Date.now() - new Date(session.lastStartedAt).getTime()) / 1000);
    return session.elapsedSeconds + extra;
  }
  return session.elapsedSeconds;
}

/** 【正计时启动时给后端的占位时长】仅满足接口（不再用于倒计时/不展示） */
const PLACEHOLDER_MINUTES = 25;

/**
 * 【是否有进行中会话的本地提示】
 * 自习室设置态（选场景/调音量）完全来自本地（常量 + localStorage + IndexedDB），
 * 不依赖网络。唯一需要等网络才能决定的是"是否要直接进沉浸态"。
 * 用这个本地提示来决定首屏是否需要等待：绝大多数情况下没有进行中会话，
 * 首屏直接渲染设置态、零加载闪屏；只有真在专注中时才短暂等待恢复沉浸态。
 */
const ACTIVE_HINT_KEY = 'soulous.studyroom.active.v1';
function readActiveHint(): boolean {
  try { return localStorage.getItem(ACTIVE_HINT_KEY) === '1'; } catch { return false; }
}
function writeActiveHint(active: boolean) {
  try {
    if (active) localStorage.setItem(ACTIVE_HINT_KEY, '1');
    else localStorage.removeItem(ACTIVE_HINT_KEY);
  } catch { /* 隐私模式/配额超限静默忽略 */ }
}

/**
 * 【自习室模块级缓存】保留上次拉取的进行中会话与可关联任务，跨面板切换重建时
 * 直接复用、不再请求，消除每次进入的顿挫。登出/重新登录时由 resetFocusCache() 清空。
 */
let focusCache: { active: FocusSession | null; tasks: StudyTask[] } | null = null;
export function resetFocusCache() { focusCache = null; }

const AMBIENT_ICON: Record<AmbientKind, React.ReactNode> = {
  rain: <CloudRain size={13} />,
  waves: <Waves size={13} />,
  whitenoise: <Volume2 size={13} />,
  wind: <Wind size={13} />,
  forest: <Trees size={13} />,
  silent: <VolumeX size={13} />,
};

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

/** 【场景背景 CSS】图片层叠在渐变兜底之上，图片缺失时渐变显现 */
function sceneBackground(scene: StudyScene, dim: number): string {
  const overlay = `linear-gradient(180deg, rgba(8,6,4,${dim * 0.5}) 0%, rgba(8,6,4,${dim}) 100%)`;
  const img = scene.image ? `url("${scene.image}"), ` : '';
  return `${overlay}, ${img}${scene.gradient}`;
}

/** 【音量行】 */
function VolumeRow({ icon, label, hint, value, enabled, onValue, onToggle }: {
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
          className="vol-slider" disabled={!enabled}
          onChange={e => onValue(Number(e.target.value) / 100)}
        />
      </div>
      <span className="vol-pct">{enabled ? `${Math.round(value * 100)}%` : '关'}</span>
    </div>
  );
}

/**
 * 【沉浸态自习室】全屏计时：正计时向上走 / 倒计时归零自动结算。
 * 布局（按需求重排）：计时器**透明**居中（无玻璃卡）、顶部两枚深色半透明小胶囊、
 * 底部深色半透明控制坞（音量 | 声音/暂停/继续/结束——「放弃」已移除，只留结束）。
 */
function ImmersiveRoom({ session, scene, scenes, prefs, audio, audioControls, onUpdate, onPrefsPatch, onNavigate, sharedRoom, pet }: {
  session: FocusSession;
  scene: StudyScene;
  scenes: StudyScene[];
  prefs: ReturnType<typeof useStudyRoomPrefs>['prefs'];
  audio: ReturnType<typeof useAudioMixer>;
  audioControls: React.ReactNode;
  onUpdate: (s: FocusSession) => void;
  onPrefsPatch: (p: Partial<ReturnType<typeof useStudyRoomPrefs>['prefs']>) => void;
  onNavigate?: (page: RoomNavTarget) => void;
  /** 【共享自习室】非空 = 这是共享房里的专注：左侧浮出房间名与在线成员（含各自专注计时） */
  sharedRoom?: RoomDetail | null;
  /** 【出战宠物】用于右下漂浮宠物展示正确品种 sprite */
  pet?: Pet | null;
}) {
  const [elapsed, setElapsed] = useState(() => currentElapsed(session));
  const [busy, setBusy] = useState(false);
  const [actError, setActError] = useState('');
  const [fullscreen, setFullscreen] = useState(false);
  /** 【沉浸态换场景条】顶部胶囊唤出横向缩略图，点击即换背景 */
  const [scenePick, setScenePick] = useState(false);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /** 【倒计时模式】展示剩余时间；归零自动按「完成」结算 */
  const countdown = prefs.timerMode === 'down';
  const totalSeconds = prefs.plannedMinutes * 60;
  const displaySeconds = countdown ? Math.max(0, totalSeconds - elapsed) : elapsed;
  /** 【归零只触发一次结算】 */
  const autoFinished = useRef(false);

  useEffect(() => {
    setElapsed(currentElapsed(session));
    if (session.status === 'RUNNING') {
      if (tickRef.current) clearInterval(tickRef.current);
      tickRef.current = setInterval(() => setElapsed(currentElapsed(session)), 1000);
    }
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, [session]);

  // 倒计时归零：自动结束并结算（与点「结束」等价）
  useEffect(() => {
    if (!countdown || autoFinished.current) return;
    if (session.status === 'RUNNING' && totalSeconds - elapsed <= 0) {
      autoFinished.current = true;
      void act(() => api.finishFocus(session.id, 'completed'));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [elapsed, countdown, totalSeconds, session.status, session.id]);

  // 跟随浏览器全屏状态（用户按 Esc 退出时同步图标）
  useEffect(() => {
    const onChange = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  /** 【沉浸式】切换浏览器原生全屏 */
  function toggleFullscreen() {
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => { /* 忽略 */ });
    } else {
      void document.documentElement.requestFullscreen?.().catch(() => { /* 忽略 */ });
    }
  }

  async function act(fn: () => Promise<FocusSession>) {
    setBusy(true); setActError('');
    try { onUpdate(await fn()); }
    catch (err) { setActError(err instanceof Error ? err.message : '操作失败'); }
    finally { setBusy(false); }
  }

  const paused = session.status === 'PAUSED';
  const statusLabel = countdown
    ? (paused ? 'PAUSED · 已暂停' : 'COUNTDOWN · 倒计时')
    : (paused ? 'PAUSED · 已暂停' : 'FOCUSING · 专注中');

  return (
    <div className="immersive-room" style={{ background: sceneBackground(scene, 0.3) }}>
      {scene.video && (
        <>
          <video className="immersive-video-bg" src={scene.video} autoPlay loop muted playsInline />
          <div className="immersive-video-dim" />
        </>
      )}
      {/* 顶部：深色半透明小胶囊（场景名/换场景 | 沉浸式），不再用整条玻璃 */}
      <div className="immersive-top2">
        <button type="button" className="im-pill im-pill-btn" onClick={() => setScenePick(o => !o)}
          title="换场景" aria-expanded={scenePick}>
          {SCENE_ICON[scene.id] ?? <Sunrise size={14} />} {scene.name}
          <em>{paused ? '已暂停' : '专注中'}</em>
        </button>
        <button type="button" className="im-pill im-pill-btn" onClick={toggleFullscreen}
          title={fullscreen ? '退出沉浸式' : '沉浸式'}>
          {fullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          <span>{fullscreen ? '退出沉浸' : '沉浸式'}</span>
        </button>
      </div>

      {/* 共享自习室成员面板：深色半透明（不用 backdrop-filter——内部计时每秒跳字会逼整块玻璃重栅格化） */}
      {sharedRoom && (
        <div className="immersive-members">
          <div className="immersive-members-head">
            <Users size={13} /> {sharedRoom.name} · 在线 {sharedRoom.onlineCount}
          </div>
          {sharedRoom.members.map(m => (
            <div key={m.userId} className={`immersive-member${m.focusing ? ' focusing' : ''}`}>
              <span className="immersive-member-dot" />
              <span className="immersive-member-name">{m.name}{m.self ? '（我）' : ''}</span>
              <em>{m.focusing ? fmt(m.focusSeconds) : '在线'}</em>
            </div>
          ))}
        </div>
      )}

      {/* 换场景条：点场景名胶囊唤出，选中即换背景并收起 */}
      {scenePick && (
        <div className="immersive-scenes">
          {scenes.map(s => (
            <button
              key={s.id} type="button"
              className={`room-scene-chip${s.id === prefs.sceneId ? ' on' : ''}`}
              style={{ background: sceneBackground(s, 0.18) }}
              title={`${s.name} · ${s.mood}`}
              aria-pressed={s.id === prefs.sceneId}
              onClick={() => { onPrefsPatch({ sceneId: s.id }); setScenePick(false); }}
            >
              <span>{s.name}</span>
            </button>
          ))}
        </div>
      )}

      {/* 居中透明计时器：无玻璃卡，大数字直接浮在场景上 */}
      <div className="immersive-center">
        <span className="immersive-center-label">{statusLabel}</span>
        <div className={`immersive-time-xl${session.status === 'RUNNING' ? ' is-running' : ''}`}>
          {fmt(displaySeconds)}
        </div>
        {countdown && <span className="immersive-center-sub">目标 {prefs.plannedMinutes} 分钟 · 已专注 {fmt(elapsed)}</span>}
        <div className="immersive-goal2">
          <Target size={14} />
          <input
            className="immersive-goal2-input"
            placeholder="写下今天想做的事…（可选）"
            value={prefs.goal}
            maxLength={120}
            onChange={e => onPrefsPatch({ goal: e.target.value })}
          />
        </div>
      </div>

      {/* 底部深色半透明控制坞：音量簇 | 声音/暂停/继续/结束（放弃已移除） */}
      <div className="immersive-dock-wrap">
        {actError && <p className="immersive-error">{actError}</p>}
        <div className="immersive-dock">
          <div className="immersive-audio">{audioControls}</div>
          <div className="immersive-controls">
            {/* 声音：开启 / 静音 合并为一个开关按钮 */}
            <button className="im-btn" onClick={() => audio.playing ? audio.stop() : audio.start()}
              title={audio.playing ? '静音' : '开启声音'}>
              {audio.playing ? <VolumeX size={22} /> : <Volume2 size={22} />}
              <span>{audio.playing ? '静音' : '声音'}</span>
            </button>
            {session.status === 'RUNNING' && (
              <button className="im-btn" disabled={busy} title="暂停"
                onClick={() => act(() => api.pauseFocus(session.id))}>
                <Pause size={22} /><span>暂停</span>
              </button>
            )}
            {paused && (
              <button className="im-btn im-btn-amber" disabled={busy} title="继续"
                onClick={() => act(() => api.resumeFocus(session.id))}>
                <Play size={22} /><span>继续</span>
              </button>
            )}
            <button className="im-btn im-btn-amber" disabled={busy} title="结束并结算"
              onClick={() => act(() => api.finishFocus(session.id, 'completed'))}>
              <CheckCircle2 size={22} /><span>结束</span>
            </button>
          </div>
        </div>
      </div>

      {/* 右下漂浮宠物：点击跳转宠物页 */}
      <button type="button" className="immersive-pet glass-card" title="去看看宠物"
        onClick={() => onNavigate?.('pet')}>
        <PetSprite state="idle" size={56} sheet={pet?.species?.spritePath} />
      </button>
    </div>
  );
}

/** 【按时段问候】房间主页大标题的第一行 */
function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return '夜深了';
  if (h < 11) return '早上好';
  if (h < 14) return '中午好';
  if (h < 18) return '下午好';
  return '晚上好';
}

/** 【主页可跳转的功能页】散点瓷片与迷你按钮的目的地 */
export type RoomNavTarget = 'tasks' | 'timetable' | 'review' | 'chat' | 'pet' | 'dashboard';

/**
 * 【自习室页面主组件】登录后的"主页"：你已经身处自习室。
 * 全局场景背景由外壳的 SceneBackdrop 渲染；本页只负责悬浮其上的玻璃层——
 * 中央主卡（目标 + 进入）、四周散点功能瓷片、底部场景坞与声音坞。
 *
 * @param userId 当前登录用户 id，用于按用户隔离自定义素材（IndexedDB 分库）
 * @param pet 出战宠物（散点瓷片展示）
 * @param summary 学习统计摘要（今日专注瓷片展示）
 * @param onImmersiveChange 通知外壳进入/退出全屏沉浸态（隐藏顶栏）
 * @param onNavigate 散点瓷片跳转其他功能页
 */
export function FocusPage({ userId, pet, summary, onImmersiveChange, onNavigate }: {
  userId?: string | number | null;
  pet?: Pet | null;
  summary?: Summary | null;
  onImmersiveChange?: (v: boolean) => void;
  onNavigate?: (page: RoomNavTarget) => void;
} = {}) {
  const { prefs, update } = useStudyRoomPrefs();
  const [active, setActive] = useState<FocusSession | null>(() => focusCache?.active ?? null);
  const [tasks, setTasks] = useState<StudyTask[]>(() => focusCache?.tasks ?? []);
  // 有缓存直接渲染、无需等待；无缓存时仅当本地提示"专注中"才等首屏恢复沉浸态，否则零闪屏
  const [loading, setLoading] = useState(() => focusCache === null && readActiveHint());
  const [loadError, setLoadError] = useState('');
  // 渲染期捕获是否需要首次拉取，避免被同步缓存的 effect 抢先改写
  const needInitialLoad = useRef(focusCache === null);

  const [linkTaskId, setLinkTaskId] = useState<number | ''>('');
  const [starting, setStarting] = useState(false);
  const [startErr, setStartErr] = useState('');
  /** 【自习室模式：独享(默认沉浸专注) / 共享(和别人一起在线自习)】 */
  const [roomMode, setRoomMode] = useState<'solo' | 'shared'>('solo');
  /** 【共享面板懒挂载】首次切到共享才挂载（避免一进页面就拉房间列表），之后保持挂载让滑动过渡平滑 */
  const [sharedMounted, setSharedMounted] = useState(false);
  useEffect(() => { if (roomMode === 'shared') setSharedMounted(true); }, [roomMode]);
  /** 【共享自习室】非空 = 当前专注发生在共享房里；建房/加入即开真实专注会话进沉浸态 */
  const [sharedRoom, setSharedRoom] = useState<RoomDetail | null>(null);

  // 自定义素材（IndexedDB）
  const [customItems, setCustomItems] = useState<CustomItem[]>([]);
  const urlsRef = useRef<string[]>([]);
  const [addingScene, setAddingScene] = useState(false);
  /** 【底部声音坞弹层】音量/曲目/背景音的玻璃浮层开关 */
  const [soundOpen, setSoundOpen] = useState(false);
  /** 【底部坞引用】场景条滚轮横滑 + 弹层点外部收起 */
  const dockRef = useRef<HTMLElement>(null);
  const dockScenesRef = useRef<HTMLDivElement>(null);

  // 【弹层点外部收起】声音氛围/自定义场景弹层打开时，点击坞外任意处关闭
  useEffect(() => {
    if (!soundOpen && !addingScene) return;
    function onPointerDown(e: MouseEvent) {
      if (dockRef.current && !dockRef.current.contains(e.target as Node)) {
        setSoundOpen(false);
        setAddingScene(false);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') { setSoundOpen(false); setAddingScene(false); }
    }
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [soundOpen, addingScene]);

  const reloadCustom = useCallback(async () => {
    try { setCustomItems(await listCustom(userId)); } catch { /* IndexedDB 不可用则忽略 */ }
  }, [userId]);
  useEffect(() => { void reloadCustom(); }, [reloadCustom]);

  // 合并内置 + 自定义；为自定义 blob 生成 object URL
  const { allScenes, allMusic, customAmbients } = useMemo(() => {
    urlsRef.current.forEach(u => URL.revokeObjectURL(u));
    urlsRef.current = [];
    const cs: StudyScene[] = customItems.filter(i => i.kind === 'scene').map(i => {
      const url = URL.createObjectURL(i.blob); urlsRef.current.push(url);
      const isVideo = i.blob.type.startsWith('video');
      return {
        id: i.id, name: i.name, mood: isVideo ? '自定义动态场景' : '自定义场景', tags: ['自定义'],
        gradient: CUSTOM_SCENE_GRADIENT, accent: '#9a93b0',
        image: isVideo ? undefined : url, video: isVideo ? url : undefined,
        ambient: i.ambient ?? 'silent',
      };
    });
    const cm: MusicTrack[] = customItems.filter(i => i.kind === 'music').map(i => {
      const url = URL.createObjectURL(i.blob); urlsRef.current.push(url);
      return { id: i.id, name: i.name, src: url };
    });
    const ca = customItems.filter(i => i.kind === 'ambient').map(i => {
      const url = URL.createObjectURL(i.blob); urlsRef.current.push(url);
      return { id: i.id, name: i.name, src: url };
    });
    return { allScenes: [...SCENES, ...cs], allMusic: [...MUSIC_TRACKS, ...cm], customAmbients: ca };
  }, [customItems]);
  useEffect(() => () => { urlsRef.current.forEach(u => URL.revokeObjectURL(u)); }, []);

  const scene = allScenes.find(s => s.id === prefs.sceneId) ?? getScene(prefs.sceneId);
  const musicTrack = allMusic.find(t => t.id === prefs.musicTrackId) ?? getMusicTrack(prefs.musicTrackId);
  // 背景音来源：选中自定义环境音则用其文件，否则跟随场景
  const customAmbient = customAmbients.find(a => a.id === prefs.ambientTrackId);
  const ambientFile = customAmbient ? customAmbient.src : scene.ambientFile;

  const audio = useAudioMixer({
    ambient: scene.ambient,
    ambientFile,
    musicSrc: musicTrack.src,
    ambientVolume: prefs.ambientVolume,
    musicVolume: prefs.musicVolume,
    ambientEnabled: prefs.ambientEnabled,
    musicEnabled: prefs.musicEnabled,
  });

  // 通知外壳：有进行中会话 = 全屏沉浸；离开页面时复位
  useEffect(() => {
    onImmersiveChange?.(!!active);
    return () => onImmersiveChange?.(false);
  }, [active, onImmersiveChange]);

  async function load() {
    // 不主动置 loading=true：首屏由 readActiveHint 决定；后台刷新静默进行，避免闪屏
    setLoadError('');
    try {
      const [activeRes, tasksRes] = await Promise.all([api.activeFocus(), api.tasks()]);
      const session = 'id' in activeRes ? activeRes as FocusSession : null;
      setActive(session);
      writeActiveHint(!!session); // 同步本地提示，保证下次进入的首屏判断准确
      setTasks(tasksRes);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '加载自习室数据失败');
    } finally {
      setLoading(false);
    }
  }
  // 会话/任务变化时同步回模块缓存
  useEffect(() => { focusCache = { active, tasks }; }, [active, tasks]);

  useEffect(() => {
    if (needInitialLoad.current) void load(); // 仅首次进入拉取；有缓存直接复用
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleUpdate(session: FocusSession) {
    if (session.status === 'COMPLETED' || session.status === 'ABORTED') {
      audio.stop();
      setActive(null);
      writeActiveHint(false);
      if (sharedRoom) {
        // 共享房里的专注结束 = 同时退出房间（空房由后端自动回收）
        void api.leaveRoom(sharedRoom.id).catch(() => { /* 房间可能已被删 */ });
        setSharedRoom(null);
      }
      void load(); // 刷新会话/任务状态
    } else {
      setActive(session);
    }
  }

  /** 【共享自习室：进房即开专注】建房/加入拿到房间详情后，启动真实专注会话并进入沉浸态。
   *  会话失败时向上抛错，由广场组件退房回滚。 */
  async function enterSharedRoom(room: RoomDetail) {
    audio.start(); // 点击手势内开声，规避自动播放拦截
    try {
      const minutes = prefs.timerMode === 'down' ? prefs.plannedMinutes : PLACEHOLDER_MINUTES;
      const title = `${room.name} · 共享自习`.slice(0, 128);
      const session = await api.startFocus(title, minutes, null);
      setSharedRoom(room);
      setActive(session);
      writeActiveHint(true);
    } catch (err) {
      audio.stop();
      throw err;
    }
  }

  // 【共享房心跳】每 15s 上报在线 + 专注状态/秒数（来自真实会话），并刷新房内成员。
  // 404/400 = 房间已删或成员被回收 → 退出共享视图（专注会话本身不受影响，继续独享计时）。
  const activeRef = useRef(active);
  activeRef.current = active;
  useEffect(() => {
    if (!sharedRoom) return;
    const id = sharedRoom.id;
    const beat = async () => {
      const s = activeRef.current;
      try {
        const d = await api.roomHeartbeat(id, s?.status === 'RUNNING', s ? currentElapsed(s) : 0);
        setSharedRoom(d);
      } catch (err) {
        if (err instanceof ApiError && (err.status === 404 || err.status === 400)) setSharedRoom(null);
        // 其他错误（网络抖动）忽略，下次心跳重试
      }
    };
    void beat(); // 进房立即上报一次，房内他人尽快看到
    const t = window.setInterval(() => void beat(), 15000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sharedRoom?.id]);

  // 【关页/刷新/离开页面时退房】keepalive 保证请求在页面销毁后仍能发出，避免留下僵尸成员
  useEffect(() => {
    if (!sharedRoom) return;
    const id = sharedRoom.id;
    const bye = () => api.leaveRoomBeacon(id);
    window.addEventListener('beforeunload', bye);
    return () => {
      window.removeEventListener('beforeunload', bye);
      // 组件卸载（切到其他功能页）也退房：心跳已停，留着只会变僵尸成员
      bye();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sharedRoom?.id]);

  const ACTIVE_STATUSES: StudyTask['status'][] = ['TODO', 'DOING', 'PAUSED', 'NEED_MORE', 'AI_REJECTED'];
  const linkable = tasks.filter(t => ACTIVE_STATUSES.includes(t.status));

  async function enterRoom() {
    setStarting(true); setStartErr('');
    audio.start(); // 在点击手势内开启声音，规避浏览器自动播放拦截
    const linked = linkTaskId === '' ? null : tasks.find(t => t.id === linkTaskId);
    const title = (prefs.goal.trim() || linked?.title || `${scene.name} · 自习`).slice(0, 128);
    try {
      // 倒计时把目标时长写进会话；正计时仍用占位时长（后端结算只看实际用时）
      const minutes = prefs.timerMode === 'down' ? prefs.plannedMinutes : PLACEHOLDER_MINUTES;
      const session = await api.startFocus(title, minutes, linkTaskId === '' ? null : linkTaskId);
      setActive(session);
      writeActiveHint(true);
    } catch (err) {
      audio.stop();
      setStartErr(err instanceof Error ? err.message : '进入失败');
    } finally {
      setStarting(false);
    }
  }

  /** 【上传自定义场景】name + 图片文件 + 环境音类型 → 存 IndexedDB */
  async function addSceneFile(name: string, file: File, ambient: AmbientKind) {
    const id = `custom-scene-${Date.now()}`;
    await addCustom(userId, { id, kind: 'scene', name: name || file.name.replace(/\.[^.]+$/, ''), blob: file, ambient, createdAt: Date.now() });
    await reloadCustom();
    update({ sceneId: id });
    setAddingScene(false);
  }

  /** 【上传自定义音乐】 */
  async function addMusicFile(file: File) {
    const id = `custom-music-${Date.now()}`;
    await addCustom(userId, { id, kind: 'music', name: file.name.replace(/\.[^.]+$/, ''), blob: file, createdAt: Date.now() });
    await reloadCustom();
    update({ musicTrackId: id });
  }

  /** 【上传自定义背景音】如雨天的淅沥声，循环作为环境音 */
  async function addAmbientFile(file: File) {
    const id = `custom-ambient-${Date.now()}`;
    await addCustom(userId, { id, kind: 'ambient', name: file.name.replace(/\.[^.]+$/, ''), blob: file, createdAt: Date.now() });
    await reloadCustom();
    update({ ambientTrackId: id });
  }

  /** 【删除自定义素材】若正选中则回退默认 */
  async function removeCustom(id: string) {
    await deleteCustom(userId, id);
    if (prefs.sceneId === id) update({ sceneId: SCENES[0].id });
    if (prefs.musicTrackId === id) update({ musicTrackId: MUSIC_TRACKS[0].id });
    if (prefs.ambientTrackId === id) update({ ambientTrackId: 'scene' });
    await reloadCustom();
  }

  const audioControls = (
    <>
      <VolumeRow
        icon={<Music size={16} />} label="音乐" hint={musicTrack.name}
        value={prefs.musicVolume} enabled={prefs.musicEnabled}
        onValue={v => update({ musicVolume: v })}
        onToggle={() => update({ musicEnabled: !prefs.musicEnabled })}
      />
      <VolumeRow
        icon={customAmbient ? <Volume2 size={16} /> : AMBIENT_ICON[scene.ambient]} label="背景音"
        hint={customAmbient ? customAmbient.name : `随场景 · ${scene.name}`}
        value={prefs.ambientVolume} enabled={prefs.ambientEnabled}
        onValue={v => update({ ambientVolume: v })}
        onToggle={() => update({ ambientEnabled: !prefs.ambientEnabled })}
      />
    </>
  );

  if (loading) {
    return <div className="room-home room-home-center"><div className="notice">加载中...</div></div>;
  }
  if (loadError) {
    return <div className="room-home room-home-center"><div className="notice error-notice">{loadError}</div></div>;
  }

  // ===== 沉浸态（全屏；独享与共享房共用，共享房多一块成员面板）=====
  if (active) {
    return (
      <ImmersiveRoom
        session={active} scene={scene} scenes={allScenes} prefs={prefs}
        audio={audio} audioControls={audioControls}
        onUpdate={handleUpdate} onPrefsPatch={update} onNavigate={onNavigate}
        sharedRoom={sharedRoom} pet={pet}
      />
    );
  }

  /** 【宠物瓷片动画】未解锁动作回退 idle，与其他展示位行为一致 */
  const petAnim = clampAnimation(animationForPet(pet ?? null), pet?.level ?? 1);

  // ===== 房间主页 =====
  // 你已经身处自习室：全局背景即场景，本层只有玻璃。
  // 布局：中央主卡（或共享面板） + 右侧散点功能瓷片 + 底部场景坞/声音坞。
  return (
    <div className="room-home">
      {/* 中央主卡：独享/共享共用同一张玻璃卡，切换只重排内容（不换组件、不跳尺寸） */}
      <section className="room-hero glass-card">
        <div className="sr-mode-tabs room-mode-tabs">
          <button type="button" className={roomMode === 'solo' ? 'active' : ''} onClick={() => setRoomMode('solo')}>独享自习</button>
          <button type="button" className={roomMode === 'shared' ? 'active' : ''} onClick={() => setRoomMode('shared')}>共享自习室</button>
        </div>

        {/* 独享/共享双面板横向滑动切换：两块面板常驻同一轨道，切换只平移轨道（平滑过渡，不换组件不跳尺寸） */}
        <div className="room-hero-slider">
          <div className={`room-hero-track${roomMode === 'shared' ? ' show-shared' : ''}`}>
            <div className="room-hero-pane" aria-hidden={roomMode !== 'solo'}>
            <p className="room-eyebrow">SOULOUS STUDY ROOM</p>
            <h1 className="room-title">{greeting()}，<br />要开始今天的专注吗？</h1>
            <p className="room-sub">当前场景「{scene.name}」· {scene.mood}。计时向上走，不设目标压力，想停就停。</p>

            <div className="room-goal-row">
              <Target size={15} />
              <input
                className="room-goal-input"
                placeholder="写下今天想做的事…（可选，回车直接进入）"
                value={prefs.goal} maxLength={120}
                onChange={e => update({ goal: e.target.value })}
                onKeyDown={e => { if (e.key === 'Enter' && !starting) void enterRoom(); }}
              />
            </div>
            <select
              className="room-task-select" value={linkTaskId}
              onChange={e => setLinkTaskId(e.target.value === '' ? '' : Number(e.target.value))}
            >
              <option value="">不关联任务（仅记录专注）</option>
              {linkable.map(t => <option key={t.id} value={t.id}>{t.title}</option>)}
            </select>

            {/* 计时方式：正计时（自由） / 倒计时（选目标时长，归零自动结算） */}
            <div className="room-timer-row">
              <span className="sr-track-label">计时</span>
              <button type="button"
                className={prefs.timerMode === 'up' ? 'sr-track-chip sr-track-on' : 'sr-track-chip'}
                onClick={() => update({ timerMode: 'up' })}>
                正计时
              </button>
              {DURATION_PRESETS.map(m => (
                <button key={m} type="button"
                  className={prefs.timerMode === 'down' && prefs.plannedMinutes === m ? 'sr-track-chip sr-track-on' : 'sr-track-chip'}
                  onClick={() => update({ timerMode: 'down', plannedMinutes: m })}>
                  {m} 分
                </button>
              ))}
            </div>
            {startErr && <p className="room-err">{startErr}</p>}
            <button type="button" className="room-enter-btn" disabled={starting} onClick={enterRoom}
              style={{ background: scene.accent }}>
              进入自习室 <ArrowRight size={18} />
            </button>
            </div>
            <div className="room-hero-pane room-shared-body" aria-hidden={roomMode !== 'shared'}>
              {sharedMounted && <SharedRooms onEnter={enterSharedRoom} />}
            </div>
          </div>
        </div>
      </section>

      {/* 散点功能瓷片：右侧两列等宽方块、错位半格——散而不乱（宽屏） */}
      <aside className="room-scatter">
        <div className="room-scatter-col">
          <button type="button" className="glass-card room-tile" onClick={() => onNavigate?.('review')} title="今日专注 · 点击查看复盘">
            <span className="room-tile-label">今日专注</span>
            <strong>{summary?.todayFocusMinutes ?? 0}<small>min</small></strong>
            <span className="room-tile-hint">{summary?.todayFocusSessions ?? 0} 次入座<br />连续 {summary?.consecutiveDays ?? 0} 天</span>
          </button>
          <button type="button" className="glass-card room-tile room-tile-pet" onClick={() => onNavigate?.('pet')} title={`${petNick(pet)} · 点击进入宠物页`}>
            <PetSprite state={petAnim} size={62} sheet={pet?.species?.spritePath} />
            <span className="room-tile-hint">{petNick(pet)} · Lv.{pet?.level ?? 1}</span>
          </button>
        </div>
        <div className="room-scatter-col">
          <button type="button" className="glass-card room-tile" onClick={() => onNavigate?.('tasks')} title="待办任务 · 点击打开任务页">
            <span className="room-tile-label">待办任务</span>
            <strong>{linkable.length}<small>项</small></strong>
            <span className="room-tile-hint">点击查看全部</span>
          </button>
          <div className="glass-card room-tile room-tile-links">
            <span className="room-tile-label">快捷入口</span>
            <div className="room-mini-grid">
              <button type="button" title="工作台" onClick={() => onNavigate?.('dashboard')}><Activity size={17} /></button>
              <button type="button" title="课表" onClick={() => onNavigate?.('timetable')}><CalendarRange size={17} /></button>
              <button type="button" title="复盘" onClick={() => onNavigate?.('review')}><CalendarCheck size={17} /></button>
              <button type="button" title="AI 拆解" onClick={() => onNavigate?.('chat')}><Bot size={17} /></button>
            </div>
          </div>
        </div>
      </aside>

      {/* 底部坞：场景缩略图（点击即换全屋背景，滚轮横滑） + 声音氛围弹层 */}
      <footer className="room-dock glass-card" ref={dockRef}>
        <div
          className="room-dock-scenes"
          ref={dockScenesRef}
          onWheel={e => {
            const el = dockScenesRef.current;
            if (el) el.scrollLeft += e.deltaY + e.deltaX;
          }}
        >
          {allScenes.map(s => (
            <span key={s.id} className="room-scene-chip-wrap">
              <button
                type="button"
                className={`room-scene-chip${s.id === prefs.sceneId ? ' on' : ''}`}
                style={{ background: sceneBackground(s, 0.18) }}
                onClick={() => update({ sceneId: s.id })}
                title={`${s.name} · ${s.mood}`}
                aria-pressed={s.id === prefs.sceneId}
              >
                <span>{s.name}</span>
              </button>
              {s.id.startsWith('custom-') && (
                <button type="button" className="room-scene-del" title="删除自定义场景" onClick={() => void removeCustom(s.id)}>
                  <X size={10} />
                </button>
              )}
            </span>
          ))}
          <button type="button" className="room-scene-chip room-scene-add" onClick={() => setAddingScene(o => !o)} title="自定义场景">
            <Plus size={15} />
            <span>自定义</span>
          </button>
        </div>
        <i className="room-dock-divider" aria-hidden="true" />
        <button type="button" className={`room-sound-btn${soundOpen ? ' on' : ''}`} onClick={() => setSoundOpen(o => !o)}>
          <Music size={15} /> 声音氛围
        </button>

        {/* 声音氛围弹层：音量 + 曲目 + 背景音 + 试听 */}
        {soundOpen && (
          <div className="room-pop room-sound-pop">
            <div className="room-pop-head">
              <span>声音氛围</span>
              <button type="button" className="sr-preview-btn" onClick={() => audio.playing ? audio.stop() : audio.start()}>
                {audio.playing ? <><Pause size={13} /> 停止试听</> : <><Volume2 size={13} /> 开启声音 / 试听</>}
              </button>
            </div>
            {audioControls}
            <div className="sr-track-row">
              <span className="sr-track-label">曲目</span>
              {allMusic.map(t => (
                <span key={t.id} className="sr-track-chip-wrap">
                  <button type="button"
                    className={prefs.musicTrackId === t.id ? 'sr-track-chip sr-track-on' : 'sr-track-chip'}
                    onClick={() => update({ musicTrackId: t.id })}>
                    {t.name}
                  </button>
                  {t.id.startsWith('custom-') && (
                    <button type="button" className="sr-track-del" title="删除" onClick={() => void removeCustom(t.id)}>
                      <X size={11} />
                    </button>
                  )}
                </span>
              ))}
              <label className="sr-track-chip sr-track-add">
                <Plus size={12} /> 添加音乐
                <input type="file" accept="audio/*" hidden
                  onChange={e => { const f = e.target.files?.[0]; if (f) void addMusicFile(f); e.currentTarget.value = ''; }} />
              </label>
            </div>
            <div className="sr-track-row">
              <span className="sr-track-label">背景音</span>
              <button type="button"
                className={prefs.ambientTrackId === 'scene' ? 'sr-track-chip sr-track-on' : 'sr-track-chip'}
                onClick={() => update({ ambientTrackId: 'scene' })}>
                随场景
              </button>
              {customAmbients.map(a => (
                <span key={a.id} className="sr-track-chip-wrap">
                  <button type="button"
                    className={prefs.ambientTrackId === a.id ? 'sr-track-chip sr-track-on' : 'sr-track-chip'}
                    onClick={() => update({ ambientTrackId: a.id })}>
                    {a.name}
                  </button>
                  <button type="button" className="sr-track-del" title="删除" onClick={() => void removeCustom(a.id)}>
                    <X size={11} />
                  </button>
                </span>
              ))}
              <label className="sr-track-chip sr-track-add">
                <Plus size={12} /> 添加背景音
                <input type="file" accept="audio/*" hidden
                  onChange={e => { const f = e.target.files?.[0]; if (f) void addAmbientFile(f); e.currentTarget.value = ''; }} />
              </label>
            </div>
          </div>
        )}

        {/* 自定义场景弹层 */}
        {addingScene && (
          <div className="room-pop room-scene-pop">
            <AddSceneForm onCancel={() => setAddingScene(false)} onSave={addSceneFile} />
          </div>
        )}
      </footer>
    </div>
  );
}

/**
 * 【添加自定义场景表单】内嵌在场景网格里的一张卡
 */
function AddSceneForm({ onCancel, onSave }: {
  onCancel: () => void;
  onSave: (name: string, file: File, ambient: AmbientKind) => void | Promise<void>;
}) {
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [ambient, setAmbient] = useState<AmbientKind>('rain');
  const [busy, setBusy] = useState(false);

  async function save() {
    if (!file) return;
    setBusy(true);
    try { await onSave(name.trim(), file, ambient); }
    finally { setBusy(false); }
  }

  return (
    <div className="scene-card scene-card-form">
      <div className="scene-form-head">
        <span>自定义场景</span>
        <button type="button" onClick={onCancel} title="取消"><X size={14} /></button>
      </div>
      <input className="scene-form-input" placeholder="场景名" value={name} maxLength={20}
        onChange={e => setName(e.target.value)} />
      <label className="scene-form-file">
        {file ? file.name.slice(0, 16) : '选择图片 / 动图 / 视频…'}
        <input type="file" accept="image/*,video/*" hidden onChange={e => setFile(e.target.files?.[0] ?? null)} />
      </label>
      <select className="scene-form-input" value={ambient} onChange={e => setAmbient(e.target.value as AmbientKind)}>
        {AMBIENT_KINDS.map(a => <option key={a.kind} value={a.kind}>背景音：{a.label}</option>)}
      </select>
      <button type="button" className="scene-form-save" disabled={!file || busy} onClick={() => void save()}>
        保存
      </button>
    </div>
  );
}
