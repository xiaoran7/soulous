/**
 * 【通知铃铛组件】
 * 顶部导航栏的通知入口，包含铃铛图标和未读数量角标。
 * 采用双通道消息接收策略：
 * 1. 主通道：SSE（Server-Sent Events）长连接，实时推送新通知
 * 2. 兜底通道：60 秒轮询，防止 SSE 连接静默断开时丢失通知
 *
 * 点击铃铛展开下拉面板，展示最近 20 条通知，支持：
 * - 单条标记已读
 * - 全部标记已读
 * - 点击外部区域自动关闭
 * - 页面可见性变化时自动刷新未读数
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Bell } from 'lucide-react';
import { api, UnauthorizedError, type NotificationItem } from '../api';

/** 【轮询间隔（毫秒）：SSE 连接正常时作为安全网，SSE 断开时作为主通道】 */
const POLL_MS = 60_000; // fallback heartbeat when SSE is connected; primary path is push

/**
 * Bell-icon badge in the topbar. Primary delivery channel is SSE — a long-lived
 * EventSource on /api/notifications/stream that emits a `notification` event for
 * every push. We still keep a slow background poll as a safety net: if the SSE
 * connection silently dies (proxy timeout, suspend/resume, etc.) the badge will
 * recover within a minute. When the dropdown is opened we also fetch the latest
 * 20 items, so the user always sees current data on click regardless of stream
 * state.
 */
export function NotificationBell() {
  /** 【未读通知数量】 */
  const [unread, setUnread] = useState<number>(0);
  /** 【下拉面板是否展开】 */
  const [open, setOpen] = useState(false);
  /** 【通知列表数据】 */
  const [items, setItems] = useState<NotificationItem[]>([]);
  /** 【列表加载状态】 */
  const [loading, setLoading] = useState(false);
  /** 【SSE 连接是否存活】 */
  const [live, setLive] = useState(false);
  /** 【下拉面板 DOM 引用，用于点击外部关闭检测】 */
  const popupRef = useRef<HTMLDivElement | null>(null);

  /**
   * 【刷新未读数量】
   * 仅在页面可见时执行，避免后台标签页浪费请求资源。
   * 401 错误静默处理（会话过期时 App 主流程会处理认证状态）。
   */
  const refreshCount = useCallback(async () => {
    if (document.hidden) return;
    try {
      const { unreadCount } = await api.notificationUnreadCount();
      setUnread(unreadCount);
    } catch (err) {
      // 401 means session expired — leave the bell quiet, App's main flows will
      // surface the auth state. Other errors: silent (bell is non-critical UI).
      if (err instanceof UnauthorizedError) return;
    }
  }, []);

  /**
   * 【初始化效果：启动轮询 + 监听页面可见性】
   * 组件挂载时立即拉取一次未读数，然后每 60 秒轮询一次。
   * 同时监听 visibilitychange 事件，页面恢复可见时立即刷新。
   */
  useEffect(() => {
    void refreshCount();
    const id = window.setInterval(() => void refreshCount(), POLL_MS);
    const onVisible = () => { if (!document.hidden) void refreshCount(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(id);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refreshCount]);

  // Live push stream. Uses native EventSource: cookies auto-attached same-origin,
  // browser handles reconnect with backoff. We don't aggressively reconnect from
  // here — onerror just flips the `live` indicator off and the slow poll loop
  // above keeps the badge fresh until the browser re-establishes the stream.
  /**
   * 【SSE 实时推送流效果】
   * 建立到 /api/notifications/stream 的 EventSource 长连接。
   * 浏览器自动处理 cookie 携带和断线重连（指数退避）。
   * 收到 notification 事件时：未读数 +1、更新列表、派发全局事件。
   * 连接断开时仅翻转 live 状态，由轮询兜底保持数据新鲜。
   */
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.EventSource === 'undefined') return;
    const es = new EventSource('/api/notifications/stream', { withCredentials: true });
    const onReady = () => setLive(true);
    const onNotification = (ev: MessageEvent) => {
      try {
        const item = JSON.parse(ev.data) as NotificationItem;
        setUnread((u) => u + 1);
        setItems((prev) => {
          if (prev.some((p) => p.id === item.id)) return prev;
          // Keep the dropdown's view fresh even when closed — cheap because the
          // list is capped at 20 by the API anyway.
          return [item, ...prev].slice(0, 20);
        });
        // Fan out to any page listening — TasksPage uses this to re-fetch the
        // submission whose AI review just landed, instead of polling.
        /**
         * 【派发全局自定义事件】
         * 其他页面（如 TasksPage）可监听此事件，在收到 AI 审核完成通知时
         * 立即刷新相关数据，避免额外的轮询开销。
         */
        window.dispatchEvent(new CustomEvent('soulous:notification', { detail: item }));
      } catch {
        // Malformed payload — fall back to a count refresh so the badge stays accurate.
        void refreshCount();
      }
    };
    es.addEventListener('ready', onReady as EventListener);
    es.addEventListener('notification', onNotification as EventListener);
    es.onerror = () => setLive(false);
    return () => {
      es.removeEventListener('ready', onReady as EventListener);
      es.removeEventListener('notification', onNotification as EventListener);
      es.close();
      setLive(false);
    };
  }, [refreshCount]);

  // Click-outside to close
  /**
   * 【点击外部关闭效果】
   * 下拉面板展开时监听 mousedown 事件，
   * 如果点击位置不在面板内部则自动关闭。
   */
  useEffect(() => {
    if (!open) return;
    const onDocClick = (e: MouseEvent) => {
      if (popupRef.current && !popupRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);

  /**
   * 【加载通知列表】
   * 下拉面板打开时拉取最新 20 条通知，同时更新未读数。
   * 加载失败时保留旧数据，角标仍可正常显示。
   */
  async function loadItems() {
    setLoading(true);
    try {
      const data = await api.notifications(false, 0, 20);
      setItems(data.items);
      setUnread(data.unreadCount);
    } catch { /* keep stale list, badge will still show */ }
    finally { setLoading(false); }
  }

  /**
   * 【切换下拉面板开关】
   * 打开时自动拉取最新通知数据。
   */
  function toggle() {
    const next = !open;
    setOpen(next);
    if (next) void loadItems();
  }

  /**
   * 【点击单条通知】
   * 如果是未读通知，调用 API 标记已读并同步更新本地状态。
   */
  async function onItemClick(n: NotificationItem) {
    if (n.readAt) return;
    try {
      await api.markNotificationRead(n.id);
      setItems(prev => prev.map(it => it.id === n.id ? { ...it, readAt: new Date().toISOString() } : it));
      setUnread(prev => Math.max(0, prev - 1));
    } catch { /* swallow */ }
  }

  /**
   * 【全部标记已读】
   * 调用 API 批量标记所有未读通知为已读，同步更新本地状态。
   */
  async function onMarkAll() {
    try {
      await api.markAllNotificationsRead();
      setItems(prev => prev.map(it => it.readAt ? it : { ...it, readAt: new Date().toISOString() }));
      setUnread(0);
    } catch { /* swallow */ }
  }

  return (
    <div ref={popupRef} className="notification-bell-wrap" style={{ position: 'relative' }}>
      <button
        type="button"
        className="user-chip notification-bell-trigger"
        onClick={toggle}
        title="通知"
        aria-label={unread > 0 ? `通知 (${unread} 条未读)` : '通知'}
        style={{ padding: '6px 10px', position: 'relative' }}
      >
        <Bell size={16} />
        {unread > 0 && (
          <span style={{
            position: 'absolute', top: 2, right: 2,
            minWidth: 16, height: 16, padding: '0 4px',
            borderRadius: 8, background: '#e0532f', color: '#fff',
            fontSize: 10, lineHeight: '16px', fontWeight: 700,
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center'
          }}>
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="通知列表"
          style={{
            position: 'absolute', top: 'calc(100% + 8px)', right: 0,
            width: 340, maxHeight: 480, overflowY: 'auto',
            background: '#fff', border: '1px solid var(--line)', borderRadius: 10,
            boxShadow: '0 12px 32px rgba(0,0,0,0.12)', zIndex: 50
          }}
        >
          <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            padding: '10px 14px', borderBottom: '1px solid var(--line)', position: 'sticky', top: 0, background: '#fff'
          }}>
            <strong style={{ fontSize: 14 }}>通知</strong>
            {unread > 0 && (
              <button type="button" onClick={() => void onMarkAll()}
                      style={{ background: 'none', border: 'none', color: 'var(--ink-2)', fontSize: 12, cursor: 'pointer' }}>
                全部已读
              </button>
            )}
          </div>
          {loading && <div style={{ padding: 16, color: 'var(--ink-3)', fontSize: 12 }}>加载中…</div>}
          {!loading && items.length === 0 && (
            <div style={{ padding: 24, color: 'var(--ink-3)', fontSize: 12, textAlign: 'center' }}>
              暂无通知
            </div>
          )}
          {!loading && items.map(n => (
            <button
              key={n.id}
              type="button"
              onClick={() => void onItemClick(n)}
              style={{
                display: 'block', width: '100%', textAlign: 'left',
                padding: '10px 14px', border: 'none', borderBottom: '1px solid var(--line-light, #f0ece6)',
                background: n.readAt ? 'transparent' : 'rgba(224, 83, 47, 0.06)',
                cursor: n.readAt ? 'default' : 'pointer'
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, marginBottom: 4 }}>
                <span style={{ fontSize: 13, fontWeight: n.readAt ? 400 : 600, color: 'var(--ink-1)' }}>
                  {n.title}
                </span>
                <span style={{ fontSize: 11, color: 'var(--ink-3)', flexShrink: 0 }}>
                  {formatRelative(n.createdAt)}
                </span>
              </div>
              {n.body && (
                <div style={{ fontSize: 12, color: 'var(--ink-2)', lineHeight: 1.5 }}>
                  {truncate(n.body, 140)}
                </div>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * 【文本截断函数】
 * 超过指定长度时截断并添加省略号，用于通知正文的摘要展示。
 *
 * @param s - 原始文本
 * @ n - 最大字符数
 * @returns 截断后的文本
 */
function truncate(s: string, n: number) {
  return s.length <= n ? s : s.slice(0, n) + '…';
}

/**
 * 【相对时间格式化函数】
 * 将 ISO 日期字符串转换为用户友好的相对时间描述，
 * 如"刚刚"、"5 分钟前"、"2 小时前"、"3 天前"，
 * 超过 7 天则显示具体日期。
 *
 * @param iso - ISO 8601 日期字符串
 * @returns 相对时间描述文本
 */
function formatRelative(iso: string) {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  const diffSec = Math.round((Date.now() - t) / 1000);
  if (diffSec < 60) return '刚刚';
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分钟前`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小时前`;
  if (diffSec < 86400 * 7) return `${Math.floor(diffSec / 86400)} 天前`;
  return new Date(t).toLocaleDateString();
}
