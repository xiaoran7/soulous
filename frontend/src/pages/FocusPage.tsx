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
  BookOpen, Building2, CheckCircle2, CloudRain, Coffee, Maximize2, Minimize2, Music, Pause, Play, Plus,
  Snowflake, Sunrise, Target, Trash2, Trees, Volume2, VolumeX, Waves, Wind, X, XCircle,
} from 'lucide-react';
import { api } from '../api';
import type { FocusSession, StudyTask } from '../types';
import {
  AMBIENT_KINDS, CUSTOM_SCENE_GRADIENT, getMusicTrack, getScene, MUSIC_TRACKS, SCENES,
  type AmbientKind, type MusicTrack, type StudyScene,
} from '../studyroom/scenes';
import { useStudyRoomPrefs } from '../studyroom/useStudyRoomPrefs';
import { useAudioMixer } from '../studyroom/useAudioMixer';
import { addCustom, deleteCustom, listCustom, type CustomItem } from '../studyroom/customStore';

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

/** 【场景卡片】 */
function SceneCard({ scene, selected, custom, onPick, onDelete }: {
  scene: StudyScene; selected: boolean; custom: boolean;
  onPick: () => void; onDelete?: () => void;
}) {
  return (
    <button
      type="button"
      className={`scene-card${selected ? ' scene-card-active' : ''}`}
      style={{ background: sceneBackground(scene, 0.35) }}
      onClick={onPick}
      aria-pressed={selected}
    >
      {scene.video && (
        <video className="scene-card-video" src={scene.video} autoPlay loop muted playsInline />
      )}
      <span className="scene-card-top">
        <span className="scene-card-icon">{SCENE_ICON[scene.id] ?? <Sunrise size={15} />}</span>
        <span className="scene-card-ambient">{AMBIENT_ICON[scene.ambient]}</span>
      </span>
      <span className="scene-card-body">
        <span className="scene-card-name">{scene.name}</span>
        <span className="scene-card-mood">{scene.mood}</span>
      </span>
      {selected && <span className="scene-card-check"><CheckCircle2 size={18} /></span>}
      {custom && onDelete && (
        <span className="scene-card-del" role="button" tabIndex={0}
          onClick={(e) => { e.stopPropagation(); onDelete(); }}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.stopPropagation(); onDelete(); } }}
          title="删除自定义场景">
          <Trash2 size={13} />
        </span>
      )}
    </button>
  );
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
 * 【沉浸态自习室】全屏正计时
 */
function ImmersiveRoom({ session, scene, prefs, audio, audioControls, onUpdate, onPrefsPatch }: {
  session: FocusSession;
  scene: StudyScene;
  prefs: ReturnType<typeof useStudyRoomPrefs>['prefs'];
  audio: ReturnType<typeof useAudioMixer>;
  audioControls: React.ReactNode;
  onUpdate: (s: FocusSession) => void;
  onPrefsPatch: (p: Partial<ReturnType<typeof useStudyRoomPrefs>['prefs']>) => void;
}) {
  const [elapsed, setElapsed] = useState(() => currentElapsed(session));
  const [busy, setBusy] = useState(false);
  const [actError, setActError] = useState('');
  const [fullscreen, setFullscreen] = useState(false);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    setElapsed(currentElapsed(session));
    if (session.status === 'RUNNING') {
      if (tickRef.current) clearInterval(tickRef.current);
      tickRef.current = setInterval(() => setElapsed(currentElapsed(session)), 1000);
    }
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, [session]);

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

  return (
    <div className="immersive-room" style={{ background: sceneBackground(scene, 0.5) }}>
      {scene.video && (
        <>
          <video className="immersive-video-bg" src={scene.video} autoPlay loop muted playsInline />
          <div className="immersive-video-dim" />
        </>
      )}
      <div className="immersive-top">
        <span className="immersive-scene-name">{SCENE_ICON[scene.id] ?? <Sunrise size={15} />} {scene.name}</span>
        <span className="immersive-status">{session.status === 'PAUSED' ? '· 已暂停' : '· 专注中'}</span>
        <button type="button" className="immersive-fs-btn" onClick={toggleFullscreen}
          title={fullscreen ? '退出沉浸式' : '沉浸式'}>
          {fullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
          <span>{fullscreen ? '退出沉浸' : '沉浸式'}</span>
        </button>
      </div>

      <div className="immersive-center">
        <div className={`immersive-clock${session.status === 'RUNNING' ? ' is-running' : ''}`}>
          <div className="immersive-time">{fmt(elapsed)}</div>
          <div className="immersive-sub">{session.status === 'PAUSED' ? 'PAUSED · 已暂停' : 'FOCUSING · 专注中'}</div>
        </div>

        <div className="immersive-goal">
          <Target size={14} />
          <input
            className="immersive-goal-input"
            placeholder="写下今天想做的事…（可选）"
            value={prefs.goal}
            maxLength={120}
            onChange={e => onPrefsPatch({ goal: e.target.value })}
          />
        </div>
      </div>

      <div className="immersive-bottom">
        <div className="immersive-audio">{audioControls}</div>
        <div className="immersive-controls">
          {/* 声音：开启 / 静音 合并为一个开关按钮 */}
          <button className="im-btn" onClick={() => audio.playing ? audio.stop() : audio.start()}
            title={audio.playing ? '静音' : '开启声音'}>
            {audio.playing ? <><VolumeX size={18} /> 静音</> : <><Volume2 size={18} /> 开启声音</>}
          </button>
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
            <CheckCircle2 size={18} /> 结束
          </button>
          <button className="im-btn im-btn-ghost" disabled={busy} onClick={() => act(() => api.finishFocus(session.id, 'aborted'))}>
            <XCircle size={18} /> 放弃
          </button>
        </div>
        {actError && <p className="immersive-error">{actError}</p>}
      </div>
    </div>
  );
}

/**
 * 【自习室页面主组件】
 * @param onImmersiveChange 通知外壳进入/退出全屏沉浸态（隐藏侧边栏与顶栏）
 */
export function FocusPage({ onImmersiveChange }: { onImmersiveChange?: (v: boolean) => void } = {}) {
  const { prefs, update } = useStudyRoomPrefs();
  const [active, setActive] = useState<FocusSession | null>(null);
  const [tasks, setTasks] = useState<StudyTask[]>([]);
  // 仅当本地提示"专注中"时才让首屏等待恢复沉浸态；否则设置态直接渲染，杜绝加载闪屏
  const [loading, setLoading] = useState(readActiveHint);
  const [loadError, setLoadError] = useState('');

  const [linkTaskId, setLinkTaskId] = useState<number | ''>('');
  const [starting, setStarting] = useState(false);
  const [startErr, setStartErr] = useState('');

  // 自定义素材（IndexedDB）
  const [customItems, setCustomItems] = useState<CustomItem[]>([]);
  const urlsRef = useRef<string[]>([]);
  const [addingScene, setAddingScene] = useState(false);

  const reloadCustom = useCallback(async () => {
    try { setCustomItems(await listCustom()); } catch { /* IndexedDB 不可用则忽略 */ }
  }, []);
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
  useEffect(() => { void load(); }, []);

  function handleUpdate(session: FocusSession) {
    if (session.status === 'COMPLETED' || session.status === 'ABORTED') {
      audio.stop();
      setActive(null);
      writeActiveHint(false);
      void load(); // 刷新会话/任务状态
    } else {
      setActive(session);
    }
  }

  const ACTIVE_STATUSES: StudyTask['status'][] = ['TODO', 'DOING', 'PAUSED', 'NEED_MORE', 'AI_REJECTED'];
  const linkable = tasks.filter(t => ACTIVE_STATUSES.includes(t.status));

  async function enterRoom() {
    setStarting(true); setStartErr('');
    audio.start(); // 在点击手势内开启声音，规避浏览器自动播放拦截
    const linked = linkTaskId === '' ? null : tasks.find(t => t.id === linkTaskId);
    const title = (prefs.goal.trim() || linked?.title || `${scene.name} · 自习`).slice(0, 128);
    try {
      const session = await api.startFocus(title, PLACEHOLDER_MINUTES, linkTaskId === '' ? null : linkTaskId);
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
    await addCustom({ id, kind: 'scene', name: name || file.name.replace(/\.[^.]+$/, ''), blob: file, ambient, createdAt: Date.now() });
    await reloadCustom();
    update({ sceneId: id });
    setAddingScene(false);
  }

  /** 【上传自定义音乐】 */
  async function addMusicFile(file: File) {
    const id = `custom-music-${Date.now()}`;
    await addCustom({ id, kind: 'music', name: file.name.replace(/\.[^.]+$/, ''), blob: file, createdAt: Date.now() });
    await reloadCustom();
    update({ musicTrackId: id });
  }

  /** 【上传自定义背景音】如雨天的淅沥声，循环作为环境音 */
  async function addAmbientFile(file: File) {
    const id = `custom-ambient-${Date.now()}`;
    await addCustom({ id, kind: 'ambient', name: file.name.replace(/\.[^.]+$/, ''), blob: file, createdAt: Date.now() });
    await reloadCustom();
    update({ ambientTrackId: id });
  }

  /** 【删除自定义素材】若正选中则回退默认 */
  async function removeCustom(id: string) {
    await deleteCustom(id);
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

  if (loading) return <div className="notice">加载中...</div>;
  if (loadError) return <div className="notice error-notice">{loadError}</div>;

  // ===== 沉浸态（全屏） =====
  if (active) {
    return (
      <ImmersiveRoom
        session={active} scene={scene} prefs={prefs}
        audio={audio} audioControls={audioControls}
        onUpdate={handleUpdate} onPrefsPatch={update}
      />
    );
  }

  // ===== 设置态 =====
  return (
    <div className="studyroom-setup">
      <div className="studyroom-main">
        {/* 选场景 */}
        <section className="sr-step">
          <div className="sr-step-head"><h3>选择场景</h3></div>
          <div className="scene-grid">
            {allScenes.map(s => (
              <SceneCard
                key={s.id} scene={s}
                selected={s.id === prefs.sceneId}
                custom={s.id.startsWith('custom-')}
                onPick={() => update({ sceneId: s.id })}
                onDelete={() => void removeCustom(s.id)}
              />
            ))}
            {/* 自定义场景卡 */}
            {addingScene ? (
              <AddSceneForm onCancel={() => setAddingScene(false)} onSave={addSceneFile} />
            ) : (
              <button type="button" className="scene-card scene-card-add" onClick={() => setAddingScene(true)}>
                <Plus size={22} />
                <span>自定义场景</span>
              </button>
            )}
          </div>
        </section>

        {/* 声音氛围 */}
        <section className="sr-step">
          <div className="sr-step-head">
            <h3>声音氛围</h3>
            <button type="button" className="sr-preview-btn" onClick={() => audio.playing ? audio.stop() : audio.start()}>
              {audio.playing ? <><Pause size={14} /> 停止试听</> : <><Volume2 size={14} /> 开启声音 / 试听</>}
            </button>
          </div>
          <div className="sr-audio card">
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
        </section>

        {/* 目标 + 关联任务 + 进入 */}
        <section className="sr-step">
          <div className="sr-pomodoro card">
            <label className="field-label">今日目标（可选）</label>
            <input
              className="text-input"
              placeholder="例如：读完第三章、随便看看…"
              value={prefs.goal} maxLength={120}
              onChange={e => update({ goal: e.target.value })}
            />
            <label className="field-label">关联任务（可选）</label>
            <select
              className="text-input" value={linkTaskId}
              onChange={e => setLinkTaskId(e.target.value === '' ? '' : Number(e.target.value))}
            >
              <option value="">不关联任务（仅记录专注）</option>
              {linkable.map(t => <option key={t.id} value={t.id}>{t.title}</option>)}
            </select>
            {startErr && <p className="notice error-notice">{startErr}</p>}
            <button type="button" className="sr-enter-btn" disabled={starting} onClick={enterRoom}
              style={{ background: scene.accent }}>
              <Maximize2 size={18} /> 进入自习室
            </button>
          </div>
        </section>
      </div>
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
