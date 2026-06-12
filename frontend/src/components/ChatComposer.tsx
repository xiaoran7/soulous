/**
 * 【聊天输入组件】ChatComposer + ChatWelcome
 *
 * 统一「欢迎页居中输入」与「对话内底部输入栏」两处的输入体验：左侧「+」点开菜单（仿 Gemini，
 * 当前支持「上传文件」md/pdf/txt），可附多份文件，回车/点发送把（含附件信封的）整条内容交给
 * onSubmit。附件提取复用 fileExtract。
 */
import React, { useRef, useState } from 'react';
import { Plus, Paperclip, Send, X, Bot } from 'lucide-react';
import { extractText } from '../fileExtract';

/** 已选附件：文件名 + 提取文本 + 是否截断 + 字符数 */
interface ChatAttachment { name: string; text: string; truncated: boolean; charCount: number; }

/** 把附件包成 <ATTACHMENT> 信封拼进消息正文（与后端/气泡解析格式严格对应）。 */
function buildContent(typed: string, attachments: ChatAttachment[]): string {
  if (attachments.length === 0) return typed;
  const blocks = attachments.map((a) => {
    const name = a.name.replace(/"/g, "'").replace(/[\r\n]+/g, ' ');
    const safeText = a.text.replace(/<\/ATTACHMENT>/gi, '<\\/ATTACHMENT>');
    return `<ATTACHMENT name="${name}" chars="${a.charCount}" truncated="${a.truncated}">\n${safeText}\n</ATTACHMENT>`;
  });
  const joined = blocks.join('\n\n');
  return typed ? `${joined}\n\n${typed}` : joined;
}

/**
 * 【输入栏】
 * @param onSubmit 发送回调，content 已包含附件信封
 * @param busy     发送中（禁用）
 * @param variant  'welcome' 居中欢迎态 / 'bar' 对话内底部栏
 */
export function ChatComposer({ onSubmit, busy, variant }: {
  onSubmit: (content: string) => void;
  busy: boolean;
  variant: 'welcome' | 'bar';
}) {
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<ChatAttachment[]>([]);
  const [attaching, setAttaching] = useState(false);
  const [attachError, setAttachError] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  async function handleAttachFiles(event: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (files.length === 0) return;
    setAttaching(true); setAttachError('');
    const added: ChatAttachment[] = [];
    const failed: string[] = [];
    for (const file of files) {
      try {
        const res = await extractText(file);
        if (res.charCount === 0) { failed.push(`${file.name}（未提取到文字，可能是扫描件）`); continue; }
        added.push({ name: file.name, text: res.text, truncated: res.truncated, charCount: res.charCount });
      } catch (err) {
        failed.push(`${file.name}（${err instanceof Error ? err.message : '解析失败'}）`);
      }
    }
    if (added.length > 0) setAttachments((prev) => [...prev, ...added]);
    if (failed.length > 0) setAttachError(`部分文件未能添加：${failed.join('；')}`);
    setAttaching(false);
  }

  function removeAttachment(idx: number) {
    setAttachments((prev) => prev.filter((_, i) => i !== idx));
  }

  function submit() {
    const typed = input.trim();
    if ((!typed && attachments.length === 0) || busy) return;
    onSubmit(buildContent(typed, attachments));
    setInput(''); setAttachments([]); setAttachError('');
  }

  const inputRowClass = variant === 'welcome' ? 'chat-welcome-input' : 'chat-input-bar';

  return (
    <div className={`chat-composer ${variant}`}>
      {attachments.length > 0 && (
        <div className="planner-attach-list">
          {attachments.map((a, i) => (
            <span key={`${a.name}-${i}`} className="planner-attach-chip" title={a.name}>
              <span className="planner-attach-name">📎 {a.name}</span>
              <span className="muted small">{a.charCount} 字{a.truncated ? ' · 已截断' : ''}</span>
              <button type="button" className="planner-attach-del" disabled={busy} onClick={() => removeAttachment(i)} title="移除附件"><X size={11} /></button>
            </span>
          ))}
        </div>
      )}
      {attachError && <div className="form-error" style={{ margin: '4px 0' }}>{attachError}</div>}

      <input ref={fileInputRef} type="file" multiple
        accept=".md,.markdown,.txt,.pdf,text/plain,text/markdown,application/pdf"
        style={{ display: 'none' }} onChange={(e) => void handleAttachFiles(e)} />

      <div className={inputRowClass}>
        <div className="chat-plus-wrap">
          <button type="button" className="chat-plus-btn" disabled={busy || attaching}
            onClick={() => setMenuOpen((o) => !o)} title="添加">
            <Plus size={18} />
          </button>
          {menuOpen && (
            <div className="chat-plus-menu" onMouseLeave={() => setMenuOpen(false)}>
              <button type="button" onClick={() => { setMenuOpen(false); fileInputRef.current?.click(); }}>
                <Paperclip size={15} />
                <span>上传文件</span>
                <span className="muted small">md / pdf / txt</span>
              </button>
            </div>
          )}
        </div>
        <input className="chat-input-field" value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } }}
          placeholder={variant === 'welcome' ? '例如：帮我制定 3 个月通过日语 N4 的学习计划' : ''}
          disabled={busy} />
        <button type="button" className="chat-send-btn" disabled={busy} onClick={submit}><Send size={16} /></button>
      </div>
    </div>
  );
}

/**
 * 【欢迎态】居中 hero + 输入栏。无选中对话、或新对话尚无消息时显示，始终是同一种样子。
 */
export function ChatWelcome({ onSubmit, busy }: { onSubmit: (content: string) => void; busy: boolean }) {
  return (
    <div className="chat-welcome">
      <div className="chat-welcome-inner">
        <div className="chat-welcome-icon"><Bot size={28} /></div>
        <h2>嗨，今天想拆解点什么？</h2>
        <ChatComposer variant="welcome" onSubmit={onSubmit} busy={busy} />
      </div>
    </div>
  );
}
