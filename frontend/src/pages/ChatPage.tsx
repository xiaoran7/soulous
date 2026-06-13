/**
 * 【AI 拆解页面】ChatPage（Gemini 式重构）
 *
 * 左侧可收起的侧边栏按用户自建分类组织对话（未分类进「默认」组），主区是连续聊天。
 * 取代旧的目标列表 / 推进 / 详情交互。无选中对话时显示居中欢迎输入框（仿 Gemini）。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  PanelLeftClose, PanelLeftOpen, Plus, FolderPlus, MoreHorizontal,
  Pencil, Trash2, FolderInput, MessageSquarePlus,
} from 'lucide-react';
import { api } from '../api';
import type { ChatConversationView, ChatTree } from '../types';
import { ChatConversation } from '../components/ChatConversation';
import { ChatWelcome } from '../components/ChatComposer';

const COLLAPSE_KEY = 'soulous.chat.sidebar.collapsed.v1';

/**
 * 【AI 拆解模块级缓存】记住侧边栏树与当前打开的对话，切到别的页再切回时直接恢复，
 * 不再每次重拉树、也不丢失正在看的对话。对话本身服务端持久化，这里只为消除切换闪烁。
 * 登出由 resetChatCache() 清空，避免串户看到上一个账号的对话。
 */
let chatTreeCache: ChatTree | null = null;
let chatActiveConvCache: ChatConversationView | null = null;
export function resetChatCache() { chatTreeCache = null; chatActiveConvCache = null; }

/** @param onTasksCommitted 计划落地为任务后回调（透传给 ChatConversation），上层用来刷新全局任务列表 */
export function ChatPage({ onTasksCommitted }: { onTasksCommitted?: () => void } = {}) {
  const [tree, setTree] = useState<ChatTree>(() => chatTreeCache ?? { categories: [], conversations: [] });
  const [activeConv, setActiveConv] = useState<ChatConversationView | null>(() => chatActiveConvCache);
  const [pendingInitial, setPendingInitial] = useState<{ id: number; text: string } | null>(null);
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem(COLLAPSE_KEY) === '1'; } catch { return false; }
  });
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState(false);
  const [menuConvId, setMenuConvId] = useState<number | null>(null);

  const reloadTree = useCallback(async () => {
    try { const t = await api.chatTree(); chatTreeCache = t; setTree(t); }
    catch (err) { setLoadError(err instanceof Error ? err.message : '加载失败'); }
  }, []);

  useEffect(() => { void reloadTree(); }, [reloadTree]);

  // 同步当前打开的对话到模块缓存，切走再切回时恢复，不丢正在看的对话。
  useEffect(() => { chatActiveConvCache = activeConv; }, [activeConv]);

  useEffect(() => {
    try { localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0'); } catch { /* ignore */ }
  }, [collapsed]);

  async function openConversation(id: number) {
    setMenuConvId(null);
    try {
      const conv = await api.chatGetConversation(id);
      setActiveConv(conv);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '打开对话失败');
    }
  }

  async function newConversation(categoryId?: number | null) {
    setBusy(true);
    try {
      const conv = await api.chatCreateConversation(categoryId ?? null);
      setActiveConv(conv);
      setPendingInitial(null);
      await reloadTree();
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '新建对话失败');
    } finally { setBusy(false); }
  }

  /** 欢迎页：创建对话并自动发送首条消息（content 已含附件信封，来自 ChatComposer） */
  async function handleWelcomeSubmit(content: string) {
    if (!content.trim() || busy) return;
    setBusy(true);
    try {
      const conv = await api.chatCreateConversation(null);
      setActiveConv(conv);
      setPendingInitial({ id: conv.id, text: content });
      await reloadTree();
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : '新建对话失败');
    } finally { setBusy(false); }
  }

  async function newCategory() {
    const name = window.prompt('新建分类名称');
    if (!name || !name.trim()) return;
    try { await api.chatCreateCategory(name.trim()); await reloadTree(); }
    catch (err) { setLoadError(err instanceof Error ? err.message : '创建分类失败'); }
  }

  async function renameCategory(id: number, current: string) {
    const name = window.prompt('重命名分类', current);
    if (!name || !name.trim() || name.trim() === current) return;
    try { await api.chatRenameCategory(id, name.trim()); await reloadTree(); }
    catch (err) { setLoadError(err instanceof Error ? err.message : '重命名失败'); }
  }

  async function deleteCategory(id: number) {
    if (!window.confirm('删除该分类？其下对话会移到「默认」，不会被删除。')) return;
    try { await api.chatDeleteCategory(id); await reloadTree(); }
    catch (err) { setLoadError(err instanceof Error ? err.message : '删除分类失败'); }
  }

  async function renameConversation(id: number, current: string) {
    setMenuConvId(null);
    const title = window.prompt('重命名对话', current);
    if (!title || !title.trim() || title.trim() === current) return;
    try {
      const next = await api.chatUpdateConversation(id, { title: title.trim() });
      if (activeConv?.id === id) setActiveConv(next);
      await reloadTree();
    } catch (err) { setLoadError(err instanceof Error ? err.message : '重命名失败'); }
  }

  async function moveConversation(id: number, categoryId: number | null) {
    setMenuConvId(null);
    try {
      const next = categoryId === null
        ? await api.chatUpdateConversation(id, { clearCategory: true })
        : await api.chatUpdateConversation(id, { categoryId });
      if (activeConv?.id === id) setActiveConv(next);
      await reloadTree();
    } catch (err) { setLoadError(err instanceof Error ? err.message : '移动失败'); }
  }

  async function deleteConversation(id: number) {
    setMenuConvId(null);
    if (!window.confirm('删除该对话？此操作不可恢复。')) return;
    try {
      await api.chatDeleteConversation(id);
      if (activeConv?.id === id) setActiveConv(null);
      await reloadTree();
    } catch (err) { setLoadError(err instanceof Error ? err.message : '删除对话失败'); }
  }

  const defaultConvs = tree.conversations.filter((c) => c.categoryId == null);

  /** 单条对话行 + 操作菜单 */
  function ConvRow({ id, title }: { id: number; title: string }) {
    return (
      <div className={`chat-conv-row${activeConv?.id === id ? ' active' : ''}`}>
        <button type="button" className="chat-conv-open" onClick={() => openConversation(id)} title={title}>
          <MessageSquarePlus size={13} /> <span className="chat-conv-title">{title}</span>
        </button>
        <button type="button" className="chat-conv-menu-btn" title="更多"
          onClick={() => setMenuConvId(menuConvId === id ? null : id)}>
          <MoreHorizontal size={14} />
        </button>
        {menuConvId === id && (
          <div className="chat-conv-menu" onMouseLeave={() => setMenuConvId(null)}>
            <button type="button" onClick={() => renameConversation(id, title)}><Pencil size={12} /> 重命名</button>
            <div className="chat-menu-sub">
              <span className="chat-menu-label"><FolderInput size={12} /> 移动到</span>
              <button type="button" onClick={() => moveConversation(id, null)}>默认</button>
              {tree.categories.map((c) => (
                <button type="button" key={c.id} onClick={() => moveConversation(id, c.id)}>{c.name}</button>
              ))}
            </div>
            <button type="button" className="danger-text" onClick={() => deleteConversation(id)}><Trash2 size={12} /> 删除</button>
          </div>
        )}
      </div>
    );
  }

  return (
    <div className={`chat-layout${collapsed ? ' collapsed' : ''}`}>
      <aside className="chat-sidebar">
        <div className="chat-sidebar-head">
          <button type="button" className="chat-icon-btn" title={collapsed ? '展开侧边栏' : '收起侧边栏'}
            onClick={() => setCollapsed((v) => !v)}>
            {collapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
          </button>
          {!collapsed && (
            <button type="button" className="chat-new-btn" disabled={busy} onClick={() => newConversation(null)}>
              <Plus size={15} /> 发起新对话
            </button>
          )}
        </div>

        {!collapsed && (
          <div className="chat-sidebar-scroll">
            <div className="chat-cat-header">
              <span className="chat-cat-label">分类</span>
              <button type="button" className="chat-icon-btn small" title="新建分类" onClick={newCategory}>
                <FolderPlus size={15} />
              </button>
            </div>

            {tree.categories.map((cat) => {
              const convs = tree.conversations.filter((c) => c.categoryId === cat.id);
              return (
                <div key={cat.id} className="chat-cat-group">
                  <div className="chat-cat-row">
                    <span className="chat-cat-name">{cat.name}</span>
                    <span className="chat-cat-actions">
                      <button type="button" title="新建对话到此分类" onClick={() => newConversation(cat.id)}><Plus size={13} /></button>
                      <button type="button" title="重命名分类" onClick={() => renameCategory(cat.id, cat.name)}><Pencil size={12} /></button>
                      <button type="button" title="删除分类" onClick={() => deleteCategory(cat.id)}><Trash2 size={12} /></button>
                    </span>
                  </div>
                  {convs.map((c) => <ConvRow key={c.id} id={c.id} title={c.title} />)}
                  {convs.length === 0 && <div className="chat-empty-hint">（空）</div>}
                </div>
              );
            })}

            <div className="chat-cat-group">
              <div className="chat-cat-row"><span className="chat-cat-name">默认</span></div>
              {defaultConvs.map((c) => <ConvRow key={c.id} id={c.id} title={c.title} />)}
              {defaultConvs.length === 0 && <div className="chat-empty-hint">（暂无对话）</div>}
            </div>
          </div>
        )}
      </aside>

      <main className="chat-content">
        {loadError && <div className="notice error-notice" style={{ margin: 12 }}>{loadError}</div>}
        {activeConv ? (
          <ChatConversation
            key={activeConv.id}
            conversation={activeConv}
            initialMessage={pendingInitial && pendingInitial.id === activeConv.id ? pendingInitial.text : null}
            onChanged={() => { setPendingInitial(null); void reloadTree(); }}
            onTasksCommitted={onTasksCommitted}
          />
        ) : (
          <ChatWelcome onSubmit={handleWelcomeSubmit} busy={busy} />
        )}
      </main>
    </div>
  );
}
