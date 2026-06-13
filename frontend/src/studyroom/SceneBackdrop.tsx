/**
 * 【全局场景背景层】SceneBackdrop
 *
 * 整个 App（登录后所有页面 + 登录页）的"墙纸"：铺满视口的自习室场景大图，
 * 上面盖一层薄白雾保证玻璃卡上的暖墨文字可读。
 *
 * 设计承诺：在自习室底部场景坞里点一下场景缩略图，**整个应用的背景立刻切换**——
 * 这是「你已经身处自习室」体验的核心。
 *
 * 实现：
 * - 通过 useSyncExternalStore 订阅 localStorage 偏好（useStudyRoomPrefs 写回时
 *   会派发 STUDYROOM_PREFS_EVENT），跨组件即时同步，无需提升状态。
 * - 内置场景直接用 /studyroom/*.jpg；自定义场景从 IndexedDB 取 Blob 生成
 *   object URL（图片或视频都支持），加载前由场景渐变兜底，永不白屏。
 */
import React, { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { CUSTOM_SCENE_GRADIENT, SCENES } from './scenes';
import { listCustom } from './customStore';
import { readStudyRoomPrefs, STUDYROOM_PREFS_EVENT } from './useStudyRoomPrefs';

/** 【订阅偏好变更】本页 hook 写回时派发的自定义事件 + 跨标签页的 storage 事件 */
function subscribe(onChange: () => void): () => void {
  window.addEventListener(STUDYROOM_PREFS_EVENT, onChange);
  window.addEventListener('storage', onChange);
  return () => {
    window.removeEventListener(STUDYROOM_PREFS_EVENT, onChange);
    window.removeEventListener('storage', onChange);
  };
}

/** 【当前场景 ID 快照】供 useSyncExternalStore 使用 */
function getSceneIdSnapshot(): string {
  return readStudyRoomPrefs().sceneId;
}

/** 【是否允许动态背景】prefers-reduced-motion / Save-Data / 极慢网 → 只用静态底图，
 *  防晕动、省流量、弱网不卡。任一命中即降级为静态图。 */
function computeMotionAllowed(): boolean {
  if (typeof window === 'undefined') return true;
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false;
  const conn = (navigator as { connection?: { saveData?: boolean; effectiveType?: string } }).connection;
  if (conn?.saveData) return false;
  if (conn?.effectiveType && /^(slow-2g|2g)$/.test(conn.effectiveType)) return false;
  return true;
}

/** 订阅 reduced-motion 媒体查询变化，实时反映系统无障碍设置切换 */
function useMotionAllowed(): boolean {
  const [allowed, setAllowed] = useState(computeMotionAllowed);
  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => setAllowed(computeMotionAllowed());
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);
  return allowed;
}

export function SceneBackdrop({ userId }: { userId?: string | number | null }) {
  const sceneId = useSyncExternalStore(subscribe, getSceneIdSnapshot);
  const builtin = SCENES.find(s => s.id === sceneId) ?? null;
  /** 弱网/无障碍降级开关：false 时不加载视频，只显示静态底图 */
  const motionAllowed = useMotionAllowed();
  /** 【自定义场景媒体】从 IndexedDB 解析出的临时 URL；内置场景时为 null */
  const [customMedia, setCustomMedia] = useState<{ url: string; isVideo: boolean } | null>(null);
  /** 【当前可见视频槽】null=两路都透明（先露静态底图），'a'/'b'=当前淡入的那一路。
   *   双视频交叉淡入用它驱动 opacity；只在首帧出画与每次循环换路时更新，无逐帧 setState。 */
  const [visibleId, setVisibleId] = useState<'a' | 'b' | null>(null);
  // 切场景时先回到「无可见视频」，让新场景的静态底图即时顶上，视频就绪后再淡入
  useEffect(() => { setVisibleId(null); }, [sceneId]);

  /** 【双路视频元素引用】A/B 交替播放做无缝循环，规避原生 loop 接缝黑帧/跳变 */
  const videoARef = useRef<HTMLVideoElement | null>(null);
  const videoBRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    if (builtin || !sceneId.startsWith('custom-')) {
      setCustomMedia(null);
      return;
    }
    let revoked = false;
    let objectUrl: string | null = null;
    void listCustom(userId)
      .then(items => {
        const item = items.find(i => i.id === sceneId && i.kind === 'scene');
        if (!item || revoked) return;
        objectUrl = URL.createObjectURL(item.blob);
        setCustomMedia({ url: objectUrl, isVideo: item.blob.type.startsWith('video') });
      })
      .catch(() => { /* IndexedDB 不可用时落在渐变兜底上 */ });
    return () => {
      revoked = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      setCustomMedia(null);
    };
  }, [sceneId, builtin, userId]);

  /** 【静态底图源】内置场景图，或自定义图片场景的 object URL */
  const imgSrc = builtin?.image ?? (customMedia && !customMedia.isVideo ? customMedia.url : undefined);
  /** 【动态视频源】内置场景视频，或自定义视频场景的 object URL；
   *   弱网/无障碍降级时为 undefined → 不渲染视频，仅留静态底图 */
  const videoSrc = motionAllowed
    ? (builtin?.video ?? (customMedia?.isVideo ? customMedia.url : undefined))
    : undefined;

  /**
   * 【无缝循环 = 预滚 + 双视频交叉淡入】
   * 原生 `loop`、单视频「seek 回 0」都会在接缝处闪一帧黑：Chrome 在 seek/EOS 时 flush
   * 解码管线，那一帧合成器拿到的是不透明黑，盖住下层永远亮着的静态底图 `.app-bg img`。
   * 这里用 A/B 两路同源视频，关键是【预滚】——退场路还差 LEAD 秒到结尾时，先**悄悄起播**
   * 待命路（仍 opacity:0），用它自己的 requestVideoFrameCallback 确认「已经稳定吐出真实帧」
   * （≥2 帧且 currentTime 在推进，说明早过了冷启动黑帧）后，**才**抬它的不透明度淡入。
   * 于是淡入进来的一定是真实画面，绝不是冷启动黑；退场路等淡出到 opacity:0 后再暂停复位，
   * 它的 seek 黑帧发生在不可见时。A↔B 切换也顺带盖掉「首帧≠末帧」的内容跳变。
   * 同源第二路命中浏览器/CDN 缓存（mp4 带 Cache-Control），不重复下载；稳态仅 1 路解码。
   */
  useEffect(() => {
    const a = videoARef.current;
    const b = videoBRef.current;
    if (!a || !b || !videoSrc) return;
    setVisibleId(null);

    const LEAD = 1.2;     // 距结尾多少秒开始预滚（须 > 预滚耗时 + FADE，留足余量）
    const FADE_MS = 600;  // 与 CSS .app-bg video 的 opacity 过渡时长保持一致
    type RVFC = HTMLVideoElement & {
      requestVideoFrameCallback?: (cb: () => void) => number;
      cancelVideoFrameCallback?: (id: number) => void;
    };
    const hasRvfc = typeof (a as RVFC).requestVideoFrameCallback === 'function';

    let front = a, back = b;
    let frontId: 'a' | 'b' = 'a';
    let cancelled = false;
    let fh = 0, bh = 0;
    let swapTimer: ReturnType<typeof setTimeout> | undefined;
    let prerollTimer: ReturnType<typeof setTimeout> | undefined;

    front.currentTime = 0;
    void front.play();
    back.pause();
    back.currentTime = 0;
    const onFirstPlaying = () => { if (!cancelled) setVisibleId('a'); };
    a.addEventListener('playing', onFirstPlaying, { once: true });

    // 待命路已确认出真帧 → 抬不透明度淡入，交换角色，复位退场路（其黑帧此刻不可见）
    const startCrossfade = () => {
      if (cancelled) return;
      frontId = frontId === 'a' ? 'b' : 'a';
      setVisibleId(frontId);
      const retiring = front;
      swapTimer = setTimeout(() => {
        if (cancelled) return;
        retiring.pause();
        retiring.currentTime = 0;
      }, FADE_MS + 80);
      const t = front; front = back; back = t;
      monitorFront();
    };

    // 预滚：悄悄起播待命路，等它稳定吐帧再淡入
    const beginPreroll = () => {
      back.currentTime = 0;
      void back.play();
      if (hasRvfc) {
        let frames = 0;
        const onBackFrame = () => {
          if (cancelled) return;
          frames++;
          if (frames >= 2 && back.currentTime > 0.03) { startCrossfade(); return; }
          bh = (back as RVFC).requestVideoFrameCallback!(onBackFrame);
        };
        bh = (back as RVFC).requestVideoFrameCallback!(onBackFrame);
      } else {
        prerollTimer = setTimeout(startCrossfade, 250); // 降级：固定延时等起播
      }
    };

    // 盯住可见路，临近结尾触发预滚（一次循环只触发一次：预滚后即停止本轮监控）
    function monitorFront() {
      if (cancelled) return;
      const d = front.duration;
      if (d && Number.isFinite(d) && d - front.currentTime <= LEAD) { beginPreroll(); return; }
      if (hasRvfc) fh = (front as RVFC).requestVideoFrameCallback!(monitorFront);
    }

    if (hasRvfc) {
      monitorFront();
    } else {
      front.loop = true; // 极老浏览器无 rVFC：退回原生 loop（可能仍有接缝），不再交叉淡入
      const onFb = () => { if (!cancelled) setVisibleId('a'); };
      a.addEventListener('playing', onFb, { once: true });
    }

    // 标签页隐藏时暂停两路视频，停止解码省电省 CPU（背景视频是全局的，每个页面都在跑）；
    // 回到前台恢复可见路播放并重启监控
    const onVisibility = () => {
      if (cancelled) return;
      if (document.hidden) {
        a.pause();
        b.pause();
        if (hasRvfc) {
          (a as RVFC).cancelVideoFrameCallback?.(fh);
          (a as RVFC).cancelVideoFrameCallback?.(bh);
          (b as RVFC).cancelVideoFrameCallback?.(fh);
          (b as RVFC).cancelVideoFrameCallback?.(bh);
        }
      } else {
        void front.play();
        if (hasRvfc) monitorFront();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      cancelled = true;
      if (swapTimer) clearTimeout(swapTimer);
      if (prerollTimer) clearTimeout(prerollTimer);
      document.removeEventListener('visibilitychange', onVisibility);
      a.removeEventListener('playing', onFirstPlaying);
      if (hasRvfc) {
        (a as RVFC).cancelVideoFrameCallback?.(fh);
        (a as RVFC).cancelVideoFrameCallback?.(bh);
        (b as RVFC).cancelVideoFrameCallback?.(fh);
        (b as RVFC).cancelVideoFrameCallback?.(bh);
      }
      a.pause();
      b.pause();
    };
  }, [videoSrc]);

  return (
    <div className="app-bg" aria-hidden="true" style={{ background: builtin?.gradient ?? CUSTOM_SCENE_GRADIENT }}>
      {/* 静态底图：始终铺底（内置场景图 / 自定义图片）。动态场景里它同时充当
          视频的「即时底图」，视频淡入前先顶住画面，杜绝静→动跳变与白屏。 */}
      {imgSrc && <img key={imgSrc} src={imgSrc} alt="" />}
      {/* 动态视频：A/B 两路同源错峰播放做无缝循环（见上方控制器 effect）。覆盖在底图之上，
          preload 静默缓冲；首帧出画/换路时由 visibleId 驱动 opacity 交叉淡入，接缝黑帧与
          内容跳变都被淡入淡出盖住。不挂 autoPlay/loop——播放与回绕全由 effect 接管。 */}
      {videoSrc && (
        <>
          <video
            ref={videoARef}
            src={videoSrc}
            poster={imgSrc ?? undefined}
            className={visibleId === 'a' ? 'is-ready' : ''}
            muted
            playsInline
            preload="auto"
          />
          <video
            ref={videoBRef}
            src={videoSrc}
            poster={imgSrc ?? undefined}
            className={visibleId === 'b' ? 'is-ready' : ''}
            muted
            playsInline
            preload="auto"
          />
        </>
      )}
      <div className="app-bg-veil" />
    </div>
  );
}
