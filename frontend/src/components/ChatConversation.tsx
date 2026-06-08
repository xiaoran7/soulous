/**
 * 【AI 拆解对话主区组件】ChatConversation
 *
 * Gemini 式连续聊天。新对话（尚无消息）始终显示居中欢迎态（与首页一致），有消息后切换为
 * 消息列表 + 底部输入栏。输入与附件统一由 ChatComposer 承载（左侧「+」菜单上传 md/pdf/txt）。
 * 保留：流式回复 + 打字动画、PLAN_JSON 计划草案卡片（编辑/删除/确认落地为任务）、CLARIFY_JSON
 * 结构化选项卡、用户消息里附件的可折叠展示。
 */
import React, { useEffect, useRef, useState } from 'react';
import { Send, CheckCircle2, Pencil, Trash2, Save, XCircle, ListChecks, ChevronDown, ChevronUp } from 'lucide-react';
import { api } from '../api';
import type { ChatConversationView, PendingClarifyQuestion, PendingPlanTask } from '../types';
import { ChatComposer, ChatWelcome } from './ChatComposer';

// ----- 信封剥离（AI 回复里 PLAN/CLARIFY 的 JSON 不展示给用户） -----
const PARTIAL_OPEN_RE = /<(?:P(?:L(?:A(?:N(?:_(?:J(?:S(?:O(?:N)?)?)?)?)?)?)?)?|C(?:L(?:A(?:R(?:I(?:F(?:Y(?:_(?:J(?:S(?:O(?:N)?)?)?)?)?)?)?)?)?)?)?)?$/;
function stripEnvelopes(text: string, placeholder: string): string {
  const out = text
    .replace(/<PLAN_JSON>[\s\S]*?<\/PLAN_JSON>/g, placeholder)
    .replace(/<CLARIFY_JSON>[\s\S]*?<\/CLARIFY_JSON>/g, '');
  const planOpen = out.indexOf('<PLAN_JSON>');
  if (planOpen >= 0) return out.slice(0, planOpen) + placeholder;
  const clarifyOpen = out.indexOf('<CLARIFY_JSON>');
  if (clarifyOpen >= 0) return out.slice(0, clarifyOpen);
  const m = out.match(PARTIAL_OPEN_RE);
  if (m && m.index !== undefined) return out.slice(0, m.index);
  return out;
}

// ----- 用户消息里的附件信封 → 可折叠卡片 -----
interface ContentSegment { kind: 'text' | 'attachment'; text: string; name?: string; chars?: number; truncated?: boolean; }
const ATTACHMENT_RE = /<ATTACHMENT name="([^"]*)" chars="(\d+)" truncated="(true|false)">\n([\s\S]*?)\n<\/ATTACHMENT>/g;
function parseUserContent(content: string): ContentSegment[] {
  const segs: ContentSegment[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  ATTACHMENT_RE.lastIndex = 0;
  while ((m = ATTACHMENT_RE.exec(content)) !== null) {
    const pre = content.slice(last, m.index).trim();
    if (pre) segs.push({ kind: 'text', text: pre });
    segs.push({ kind: 'attachment', name: m[1], chars: Number(m[2]), truncated: m[3] === 'true', text: m[4] });
    last = ATTACHMENT_RE.lastIndex;
  }
  const rest = content.slice(last).trim();
  if (rest) segs.push({ kind: 'text', text: rest });
  return segs;
}

function AttachmentCard({ name, chars, truncated, text }: { name: string; chars: number; truncated: boolean; text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="attach-card">
      <button type="button" className="attach-card-head" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        <span className="attach-card-name">📎 {name}</span>
        <span className="muted small">{chars} 字{truncated ? ' · 已截断' : ''}</span>
        {open ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
      </button>
      {open && <pre className="attach-card-body">{text}</pre>}
    </div>
  );
}

function UserBubbleContent({ content }: { content: string }) {
  const segs = parseUserContent(content);
  return (
    <>
      {segs.map((s, i) => s.kind === 'attachment'
        ? <AttachmentCard key={i} name={s.name ?? '附件'} chars={s.chars ?? s.text.length} truncated={!!s.truncated} text={s.text} />
        : <span key={i} className="attach-prose">{s.text}</span>)}
    </>
  );
}

/**
 * 【对话主区】
 * @param conversation 初始对话视图（切换对话时由父组件传新值）
 * @param onChanged    对话内容变化（标题/计划落地）后通知父组件刷新侧边栏/任务
 * @param initialMessage 可选首条消息（已含附件信封）：挂载即自动发送一次
 */
export function ChatConversation({ conversation: initial, onChanged, initialMessage }: {
  conversation: ChatConversationView;
  onChanged?: () => void;
  initialMessage?: string | null;
}) {
  const [conv, setConv] = useState<ChatConversationView>(initial);
  const [busy, setBusy] = useState(false);
  const [busyKind, setBusyKind] = useState<'send' | 'commit' | null>(null);
  const [error, setError] = useState('');
  const [streamingText, setStreamingText] = useState('');
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [editDraft, setEditDraft] = useState<PendingPlanTask>({ title: '' });
  const [clarifyPick, setClarifyPick] = useState<Record<string, string[]>>({});
  const [clarifyOther, setClarifyOther] = useState<Record<string, string>>({});
  const scrollRef = useRef<HTMLDivElement>(null);

  // 切换对话时重置内部状态
  useEffect(() => {
    setConv(initial);
    setError(''); setStreamingText('');
    setEditingIdx(null); setClarifyPick({}); setClarifyOther({});
  }, [initial]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el && typeof el.scrollTo === 'function') el.scrollTo({ top: el.scrollHeight });
  }, [conv.messages.length, streamingText]);

  // 欢迎态居中输入创建对话后，挂载即自动发送首条消息（ref 守卫防严格模式重复发送）
  const autoSentRef = useRef(false);
  useEffect(() => {
    if (initialMessage && initialMessage.trim() && !autoSentRef.current) {
      autoSentRef.current = true;
      void send(initialMessage);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 发送（content 已含附件信封，由 ChatComposer 构建） */
  async function send(content: string) {
    if (!content.trim() || busy) return;
    setBusy(true); setBusyKind('send'); setError(''); setStreamingText('');
    try {
      const optimisticId = -Date.now();
      setConv((c) => ({ ...c, messages: [...c.messages, { id: optimisticId, idx: c.messages.length, role: 'USER', content }] }));
      const next = await api.chatPostMessageStream(conv.id, content, (chunk) => setStreamingText((p) => p + chunk));
      setConv(next);
      onChanged?.();
    } catch (err) {
      try {
        const next = await api.chatPostMessage(conv.id, content);
        setConv(next);
        onChanged?.();
      } catch (fallbackErr) {
        setError(fallbackErr instanceof Error ? fallbackErr.message : (err instanceof Error ? err.message : '发送失败'));
      }
    } finally {
      setBusy(false); setBusyKind(null); setStreamingText('');
    }
  }

  // ----- 计划草案 -----
  function startEdit(idx: number, t: PendingPlanTask) {
    setEditDraft({
      title: t.title, description: t.description ?? '',
      estimatedMinutes: t.estimatedMinutes ?? 30,
      difficulty: t.difficulty ?? 'NORMAL', taskType: t.taskType ?? 'STUDY',
    });
    setEditingIdx(idx); setError('');
  }

  async function saveEdit(idx: number) {
    if (!editDraft.title.trim()) { setError('标题不能为空'); return; }
    setBusy(true); setError('');
    try {
      const next = await api.chatEditPlanTask(conv.id, idx, {
        title: editDraft.title.trim(), description: editDraft.description ?? '',
        estimatedMinutes: editDraft.estimatedMinutes, difficulty: editDraft.difficulty, taskType: editDraft.taskType,
      });
      setConv(next); setEditingIdx(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally { setBusy(false); }
  }

  async function deletePendingTask(idx: number) {
    if (!confirm('删除这个任务？')) return;
    setBusy(true); setError('');
    try {
      const next = await api.chatDeletePlanTask(conv.id, idx);
      setConv(next);
      if (editingIdx === idx) setEditingIdx(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally { setBusy(false); }
  }

  async function commit() {
    setBusy(true); setBusyKind('commit'); setError('');
    try {
      const next = await api.chatCommit(conv.id);
      setConv(next);
      onChanged?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败');
    } finally { setBusy(false); setBusyKind(null); }
  }

  // ----- 澄清 -----
  function clarifyKey(q: PendingClarifyQuestion, i: number) { return q.id ?? String(i); }
  function toggleClarify(q: PendingClarifyQuestion, i: number, label: string) {
    const key = clarifyKey(q, i);
    setClarifyPick((prev) => {
      const cur = prev[key] ?? [];
      if (q.multiSelect) return { ...prev, [key]: cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label] };
      return { ...prev, [key]: cur.includes(label) ? [] : [label] };
    });
    setError('');
  }
  async function submitClarify(questions: PendingClarifyQuestion[]) {
    const parts = questions.map((q, i) => {
      const key = clarifyKey(q, i);
      const picked = [...(clarifyPick[key] ?? [])];
      const other = (clarifyOther[key] ?? '').trim();
      if (other) picked.push(other);
      if (picked.length === 0) return null;
      return `${q.question} ${picked.join('、')}`;
    }).filter((p): p is string => p !== null);
    if (parts.length === 0) { setError('请至少选择一个选项，或在下方输入框直接回复'); return; }
    setClarifyPick({}); setClarifyOther({});
    await send(parts.join('\n'));
  }

  const plan = conv.pendingPlan;
  const clarify = conv.pendingClarify;
  const isEmpty = conv.messages.length === 0 && !streamingText && busyKind !== 'send';

  // 新对话尚无消息：始终显示居中欢迎态（与首页一致）
  if (isEmpty) {
    return (
      <div className="chat-main">
        <ChatWelcome onSubmit={(c) => void send(c)} busy={busy} />
      </div>
    );
  }

  return (
    <div className="chat-main">
      <div ref={scrollRef} className="chat-scroll">
        {conv.messages.map((m) => {
          const isUser = m.role === 'USER';
          const assistantText = isUser ? '' : stripEnvelopes(m.content, '（已生成计划草案 ↓）');
          const blocked = !isUser && (assistantText.includes('暂不可用') || assistantText.includes('内容暂时无法展示') || assistantText.includes('未通过内容审核'));
          return (
            <div key={m.id} className={`planner-chat-row ${isUser ? 'user' : 'assistant'}`}>
              <span className="planner-chat-role">{isUser ? '你' : 'AI'}</span>
              <div className={`planner-chat-bubble ${isUser ? 'user' : 'assistant'}${blocked ? ' blocked' : ''}`}>
                {isUser ? <UserBubbleContent content={m.content} /> : assistantText}
              </div>
            </div>
          );
        })}
        {streamingText && (
          <div className="planner-chat-row assistant">
            <span className="planner-chat-role">AI</span>
            <div className="planner-chat-bubble assistant streaming">
              {stripEnvelopes(streamingText, '（计划草案生成中…）')}
              <span className="planner-chat-cursor" aria-hidden="true">▋</span>
            </div>
          </div>
        )}
        {busyKind === 'send' && !streamingText && (
          <div className="planner-chat-row assistant">
            <span className="planner-chat-role">AI</span>
            <div className="planner-chat-bubble assistant streaming">
              <span className="planner-chat-typing"><span /><span /><span /></span>
            </div>
          </div>
        )}
      </div>

      {/* 澄清选项卡 */}
      {!busy && clarify && clarify.questions && clarify.questions.length > 0 && (
        <div className="panel chat-inset">
          <div className="panel-title">
            <h4><ListChecks size={14} style={{ verticalAlign: '-2px', marginRight: 4 }} />帮 AI 补充几个信息</h4>
            <span className="muted small">点选即可，下方也可直接打字回复</span>
          </div>
          {clarify.questions.map((q, i) => {
            const key = clarifyKey(q, i);
            const picked = clarifyPick[key] ?? [];
            return (
              <div key={key} className="task-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}>
                <strong>{q.question}{q.multiSelect ? ' （可多选）' : ''}</strong>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {q.options.map((opt) => {
                    const active = picked.includes(opt.label);
                    return (
                      <button key={opt.label} type="button" disabled={busy}
                        className={active ? 'primary-button small' : 'secondary-button small'}
                        title={opt.hint ?? ''} onClick={() => toggleClarify(q, i, opt.label)}>
                        {opt.label}{opt.hint ? <span className="muted small" style={{ marginLeft: 4 }}>· {opt.hint}</span> : null}
                      </button>
                    );
                  })}
                </div>
                <input className="text-input" placeholder="其他（自己补充，可选）"
                  value={clarifyOther[key] ?? ''} disabled={busy}
                  onChange={(e) => setClarifyOther({ ...clarifyOther, [key]: e.target.value })} />
              </div>
            );
          })}
          <div className="row-actions" style={{ marginTop: 6 }}>
            <button className="primary-button" disabled={busy} onClick={() => submitClarify(clarify.questions)}>
              <Send size={14} /> 提交选择
            </button>
          </div>
        </div>
      )}

      {/* 计划草案卡片 */}
      {plan && plan.tasks && plan.tasks.length > 0 && (
        <div className="panel chat-inset">
          <div className="panel-title">
            <h4>计划草案{plan.category ? <span className="muted small" style={{ marginLeft: 6, fontWeight: 400 }}>· 大类：{plan.category}</span> : null}</h4>
            <span className="muted small">可编辑/删除后再确认落地为任务</span>
          </div>
          {plan.tasks.map((t, i) => (
            editingIdx === i ? (
              <div key={i} className="task-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}>
                <input className="text-input" value={editDraft.title} maxLength={128} placeholder="标题"
                  onChange={(e) => setEditDraft({ ...editDraft, title: e.target.value })} />
                <textarea className="text-input" value={editDraft.description ?? ''} rows={2} placeholder="描述"
                  onChange={(e) => setEditDraft({ ...editDraft, description: e.target.value })} />
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <label className="muted small">分钟
                    <input type="number" min={5} max={240} style={{ width: 70, marginLeft: 4 }}
                      value={editDraft.estimatedMinutes ?? 30}
                      onChange={(e) => setEditDraft({ ...editDraft, estimatedMinutes: Number(e.target.value) })} />
                  </label>
                  <label className="muted small">难度
                    <select style={{ marginLeft: 4 }} value={editDraft.difficulty ?? 'NORMAL'}
                      onChange={(e) => setEditDraft({ ...editDraft, difficulty: e.target.value as PendingPlanTask['difficulty'] })}>
                      <option value="EASY">EASY</option><option value="NORMAL">NORMAL</option>
                      <option value="HARD">HARD</option><option value="CHALLENGE">CHALLENGE</option>
                    </select>
                  </label>
                  <label className="muted small">类型
                    <select style={{ marginLeft: 4 }} value={editDraft.taskType ?? 'STUDY'}
                      onChange={(e) => setEditDraft({ ...editDraft, taskType: e.target.value as PendingPlanTask['taskType'] })}>
                      <option value="STUDY">STUDY</option><option value="CODING">CODING</option>
                      <option value="NOTE">NOTE</option><option value="MEMORY">MEMORY</option>
                      <option value="REVIEW">REVIEW</option><option value="SIMPLE">SIMPLE</option>
                    </select>
                  </label>
                </div>
                <div className="row-actions" style={{ gap: 6 }}>
                  <button className="primary-button small" disabled={busy} onClick={() => saveEdit(i)}><Save size={12} /> 保存</button>
                  <button className="secondary-button small" disabled={busy} onClick={() => setEditingIdx(null)}><XCircle size={12} /> 取消</button>
                </div>
              </div>
            ) : (
              <div key={i} className="task-row" style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                <div style={{ flex: 1 }}>
                  <strong>{t.title}</strong>
                  <span className="muted small" style={{ marginLeft: 6 }}>
                    {t.estimatedMinutes ?? 30} 分钟 · {t.difficulty ?? 'NORMAL'} · {t.taskType ?? 'STUDY'}
                  </span>
                  {t.description && <div className="muted small">{t.description}</div>}
                </div>
                <div className="row-actions" style={{ gap: 4 }}>
                  <button className="secondary-button small" disabled={busy} onClick={() => startEdit(i, t)} title="编辑"><Pencil size={12} /></button>
                  <button className="danger-button small" disabled={busy} onClick={() => deletePendingTask(i)} title="删除"><Trash2 size={12} /></button>
                </div>
              </div>
            )
          ))}
          <div className="row-actions" style={{ marginTop: 8 }}>
            <button className="primary-button" disabled={busy || editingIdx !== null} onClick={commit}>
              <CheckCircle2 size={14} /> 确认并创建任务
            </button>
            {busyKind === 'commit' && <span className="muted small">正在创建任务…</span>}
          </div>
        </div>
      )}

      {error && <div className="form-error" style={{ margin: '0 16px' }}>{error}</div>}

      <ChatComposer variant="bar" onSubmit={(c) => void send(c)} busy={busy} />
    </div>
  );
}
