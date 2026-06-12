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
import React, { useEffect, useState, useSyncExternalStore } from 'react';
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

export function SceneBackdrop({ userId }: { userId?: string | number | null }) {
  const sceneId = useSyncExternalStore(subscribe, getSceneIdSnapshot);
  const builtin = SCENES.find(s => s.id === sceneId) ?? null;
  /** 【自定义场景媒体】从 IndexedDB 解析出的临时 URL；内置场景时为 null */
  const [customMedia, setCustomMedia] = useState<{ url: string; isVideo: boolean } | null>(null);

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

  return (
    <div className="app-bg" aria-hidden="true" style={{ background: builtin?.gradient ?? CUSTOM_SCENE_GRADIENT }}>
      {builtin?.image && <img key={builtin.id} src={builtin.image} alt="" />}
      {builtin?.video && <video key={`${builtin.id}-v`} src={builtin.video} autoPlay loop muted playsInline />}
      {customMedia && (customMedia.isVideo
        ? <video key={customMedia.url} src={customMedia.url} autoPlay loop muted playsInline />
        : <img key={customMedia.url} src={customMedia.url} alt="" />)}
      <div className="app-bg-veil" />
    </div>
  );
}
