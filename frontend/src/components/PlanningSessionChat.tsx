/**
 * 【AI 拆解会话聊天组件】
 * 实现用户与 AI 的多轮对话交互，用于目标拆解和推进。
 * 核心功能：
 * - 流式消息接收（SSE）+ 非流式降级
 * - 计划草案的内联编辑（标题、描述、时长、难度、类型）
 * - 计划草案的单条删除
 * - 计划确认提交（落地为真实任务）
 * - 会话放弃
 * - 重复目标检测提示
 * - 蒸馏记忆超限警告
 * - 乐观用户消息渲染（不等服务端响应立即显示）
 *
 * 会话状态机：DRAFTING → PLAN_PROPOSED → COMMITMENT → COMMITTED / ABANDONED / CLOSED
 */
import React, { useEffect, useRef, useState } from 'react';
import { Send, X, CheckCircle2, Pencil, Trash2, Save, XCircle, ListChecks } from 'lucide-react';
import { api } from '../api';
import type { PendingClarifyQuestion, PendingPlanTask, SessionView } from '../types';

/**
 * Strip the PLAN_JSON envelope from an AI message so the user sees prose, not raw JSON.
 * Handles three shapes — they all matter during SSE streaming:
 *  1. Complete `<PLAN_JSON>...</PLAN_JSON>`        → replace with placeholder
 *  2. Open tag without close yet (mid-stream)      → drop from tag onwards, append placeholder
 *  3. Partial opening tag at the tail (`<P`, `<PLAN_JS`, …) → drop it silently; no placeholder
 *     yet because we don't know if it'll turn into PLAN_JSON or something else.
 *
 * 【剥离 PLAN_JSON 信封函数】
 * AI 消息中可能包含 `<PLAN_JSON>...</PLAN_JSON>` 标签包裹的 JSON 数据，
 * 此函数将其替换为用户友好的占位文本，使聊天界面只显示自然语言部分。
 * 需要处理 SSE 流式传输中的三种边界情况：
 * 1. 完整的标签对 → 直接替换为占位符
 * 2. 只有开标签（流式传输中途）→ 从开标签开始截断，追加占位符
 * 3. 部分开标签（如 `<P`、`<PLAN_JS`）→ 静默丢弃，不加占位符
 */
// Matches a partial opening tag at the tail — any prefix of `<PLAN_JSON>` OR `<CLARIFY_JSON>`.
const PARTIAL_OPEN_RE = /<(?:P(?:L(?:A(?:N(?:_(?:J(?:S(?:O(?:N)?)?)?)?)?)?)?)?|C(?:L(?:A(?:R(?:I(?:F(?:Y(?:_(?:J(?:S(?:O(?:N)?)?)?)?)?)?)?)?)?)?)?)?$/;
/**
 * Strip both the PLAN_JSON and CLARIFY_JSON envelopes from an AI message so the user sees prose,
 * not raw JSON. PLAN_JSON collapses to `placeholder` (a "(plan drafted ↓)" hint); CLARIFY_JSON is
 * dropped silently because the selectable question card renders separately below the bubble.
 * Handles complete tags, an open tag without its close (mid-stream), and a partial opening tag at
 * the tail (e.g. `<CLAR`) — the same three shapes that matter during SSE streaming.
 */
function stripEnvelopes(text: string, placeholder: string): string {
  let out = text
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

/**
 * 【AI 拆解会话聊天组件】
 *
 * @param session - 初始会话数据（会随交互更新）
 * @param onClose - 关闭/返回的回调
 * @param onCommitted - 计划确认提交后的回调（通知父组件刷新数据）
 */
export function PlanningSessionChat({ session: initial, onClose, onCommitted }: {
  session: SessionView;
  onClose: () => void;
  onCommitted: () => void;
}) {
  /** 【当前会话数据，随交互实时更新】 */
  const [session, setSession] = useState<SessionView>(initial);
  /** 【用户输入框的文本】 */
  const [input, setInput] = useState('');
  /** 【是否正在执行操作（发送/提交）】 */
  const [busy, setBusy] = useState(false);
  /** 【当前忙碌操作类型：send 为发送消息，commit 为确认计划】 */
  const [busyKind, setBusyKind] = useState<'send' | 'commit' | null>(null);
  /** 【错误提示信息】 */
  const [error, setError] = useState('');
  /** 【正在编辑的任务索引（null 表示无编辑状态）】 */
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  /** 【编辑中的任务草稿数据】 */
  const [editDraft, setEditDraft] = useState<PendingPlanTask>({ title: '' });
  /** 【澄清问题的已选项：key 为问题 id 或索引，value 为选中的 label 列表】 */
  const [clarifyPick, setClarifyPick] = useState<Record<string, string[]>>({});
  /** 【澄清问题的"其他"自由文本：key 为问题 id 或索引】 */
  const [clarifyOther, setClarifyOther] = useState<Record<string, string>>({});

  /**
   * 【开始编辑任务】
   * 将指定索引的任务数据填充到编辑草稿中，进入编辑模式。
   *
   * @param idx - 任务在 pendingPlan.tasks 中的索引
   * @param t - 任务数据
   */
  function startEdit(idx: number, t: PendingPlanTask) {
    setEditDraft({
      title: t.title,
      description: t.description ?? '',
      estimatedMinutes: t.estimatedMinutes ?? 30,
      difficulty: t.difficulty ?? 'NORMAL',
      taskType: t.taskType ?? 'STUDY'
    });
    setEditingIdx(idx);
    setError('');
  }

  /**
   * 【保存编辑】
   * 校验标题非空后调用 API 更新计划草案中的任务，
   * 成功后更新本地会话数据并退出编辑模式。
   *
   * @param idx - 任务索引
   */
  async function saveEdit(idx: number) {
    if (!editDraft.title.trim()) { setError('标题不能为空'); return; }
    setBusy(true); setError('');
    try {
      const next = await api.sessionEditPlanTask(session.id, idx, {
        title: editDraft.title.trim(),
        description: editDraft.description ?? '',
        estimatedMinutes: editDraft.estimatedMinutes,
        difficulty: editDraft.difficulty,
        taskType: editDraft.taskType
      });
      setSession(next);
      setEditingIdx(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  /**
   * 【删除待定任务】
   * 确认后调用 API 删除计划草案中的指定任务，
   * 如果正在编辑该任务则同时退出编辑模式。
   *
   * @param idx - 任务索引
   */
  async function deletePendingTask(idx: number) {
    if (!confirm('删除这个任务？')) return;
    setBusy(true); setError('');
    try {
      const next = await api.sessionDeletePlanTask(session.id, idx);
      setSession(next);
      if (editingIdx === idx) setEditingIdx(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }
  /** 【聊天消息列表的滚动容器引用，用于自动滚到底部】 */
  const scrollRef = useRef<HTMLDivElement>(null);
  /** Transient streamed text for the in-flight assistant turn (renders as a ghost bubble). */
  /**
   * 【流式传输中的 AI 回复文本】
   * SSE 流式传输期间，增量追加 AI 的回复文本。
   * 以"幽灵气泡"形式实时渲染在聊天列表底部，
   * 传输完成后由服务端返回的完整 turn 数据替换。
   */
  const [streamingText, setStreamingText] = useState<string>('');

  /**
   * 【自动滚动效果】
   * 消息数量或流式文本变化时，自动滚动到聊天列表底部。
   * 使用 scrollTo API 实现平滑滚动。
   */
  useEffect(() => {
    const el = scrollRef.current;
    if (el && typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight });
    }
  }, [session.turns.length, streamingText]);

  /**
   * 【发送消息】
   * 核心消息发送逻辑，采用流式优先 + 非流式降级策略：
   * 1. 乐观渲染用户消息（不等服务端响应）
   * 2. 尝试 SSE 流式请求，实时显示 AI 回复
   * 3. 流式失败时降级到普通请求
   *
   * @param text - 可选的文本内容（预设操作如"直接给我计划"），不传则使用输入框内容
   */
  async function send(text?: string) {
    const content = (text ?? input).trim();
    if (!content || busy) return;
    setBusy(true); setBusyKind('send'); setError('');
    setInput('');
    setStreamingText('');
    try {
      // Optimistically render the user's message so the UI doesn't sit empty until the
      // stream's first token. We'll get the authoritative copy on `done`.
      /**
       * 【乐观渲染用户消息】
       * 使用负数时间戳作为临时 ID（服务端返回的正式数据会替换），
       * 让用户消息立即显示在聊天列表中，避免等待流式响应时的空白感。
       */
      const optimisticUserTurnId = -Date.now();
      setSession((s) => ({
        ...s,
        turns: [
          ...s.turns,
          { id: optimisticUserTurnId, idx: s.turnCount, role: 'USER', content }
        ]
      }));
      const next = await api.sessionPostMessageStream(session.id, content, (chunk) => {
        setStreamingText((prev) => prev + chunk);
      });
      setSession(next);
    } catch (err) {
      // Stream failed mid-flight (or before first byte). Try the non-streaming fallback
      // so the user's message doesn't dangle without an answer.
      /**
       * 【流式失败降级】
       * 如果 SSE 流式请求失败（中途断开或首字节前失败），
       * 自动降级到非流式接口，确保用户消息不会得不到回复。
       */
      try {
        const next = await api.sessionPostMessage(session.id, content);
        setSession(next);
      } catch (fallbackErr) {
        setError(fallbackErr instanceof Error ? fallbackErr.message : (err instanceof Error ? err.message : '发送失败'));
      }
    } finally {
      setBusy(false); setBusyKind(null);
      setStreamingText('');
    }
  }

  /**
   * 【确认计划】
   * 将当前计划草案落地为真实任务并更新目标记忆。
   * 成功后触发 onCommitted 回调通知父组件刷新。
   */
  async function commit() {
    setBusy(true); setBusyKind('commit'); setError('');
    try {
      const next = await api.sessionCommit(session.id);
      setSession(next);
      onCommitted();
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败');
    } finally {
      setBusy(false); setBusyKind(null);
    }
  }

  /**
   * 【放弃会话】
   * 确认后放弃当前拆解会话，关闭面板。
   */
  async function abandon() {
    if (!confirm('确认放弃本次拆解会话？')) return;
    setBusy(true);
    try {
      await api.sessionAbandon(session.id);
      onClose();
    } finally {
      setBusy(false);
    }
  }

  /** 【澄清问题的稳定 key：优先用后端给的 id，缺失时回退到索引】 */
  function clarifyKey(q: PendingClarifyQuestion, i: number) {
    return q.id ?? String(i);
  }

  /**
   * 【切换澄清选项的选中态】
   * 单选时点选替换/取消；多选时累加/移除。
   */
  function toggleClarify(q: PendingClarifyQuestion, i: number, label: string) {
    const key = clarifyKey(q, i);
    setClarifyPick((prev) => {
      const cur = prev[key] ?? [];
      if (q.multiSelect) {
        return { ...prev, [key]: cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label] };
      }
      return { ...prev, [key]: cur.includes(label) ? [] : [label] };
    });
    setError('');
  }

  /**
   * 【提交澄清选择】
   * 把每题的「问题 + 选中项（含"其他"自由文本）」拼成一条中文消息，
   * 复用普通发送链路（流式优先）回灌给 AI 继续对话，替代用户手动打字作答。
   */
  async function submitClarify(questions: PendingClarifyQuestion[]) {
    const parts = questions
      .map((q, i) => {
        const key = clarifyKey(q, i);
        const picked = [...(clarifyPick[key] ?? [])];
        const other = (clarifyOther[key] ?? '').trim();
        if (other) picked.push(other);
        if (picked.length === 0) return null;
        return `${q.question} ${picked.join('、')}`;
      })
      .filter((p): p is string => p !== null);
    if (parts.length === 0) {
      setError('请至少选择一个选项，或在下方输入框直接回复');
      return;
    }
    setClarifyPick({});
    setClarifyOther({});
    await send(parts.join('\n'));
  }

  /** 【是否为终态（已提交/已放弃/已关闭），终态下隐藏输入区域】 */
  const terminal = session.state === 'COMMITTED' || session.state === 'ABANDONED' || session.state === 'CLOSED';

  return (
    <div className="panel" style={{ marginTop: 12 }}>
      <div className="panel-title">
        <h3>{session.kind === 'NEW_GOAL' ? '拆解新目标' : '继续推进目标'} · {session.goalTitle}</h3>
        <div className="row-actions">
          <span className="chip">{session.state}</span>
          <button className="secondary-button" onClick={onClose}><X size={14} /> 关闭</button>
        </div>
      </div>

      {session.duplicateCandidates && session.duplicateCandidates.length > 0 && (
        /**
         * 【重复目标检测提示】
         * 当 AI 检测到可能重复的已有目标时，在聊天区域顶部显示警告面板，
         * 列出重复目标的标题和原因，引导用户使用"继续推进目标"而非创建新目标。
         */
        <div className="panel" style={{ background: 'var(--surface-2)', marginBottom: 8 }}>
          <div className="muted small">检测到可能重复的已有目标：</div>
          {session.duplicateCandidates.map((d) => (
            <div key={d.goalId} className="task-row">
              <strong>{d.title}</strong>
              <span className="muted small">— {d.reason}</span>
            </div>
          ))}
          <div className="muted small">如果是同一个目标，建议关闭后改用「继续推进目标」。</div>
        </div>
      )}

      <div ref={scrollRef} className="planner-chat-scroll">
        {session.turns.map((t) => {
          const isUser = t.role === 'USER';
          const text = stripEnvelopes(t.content, '（已生成计划草案 ↓）');
          /** 【内容审核拦截检测】 */
          const blocked = !isUser && (text.includes('暂不可用') || text.includes('内容暂时无法展示') || text.includes('未通过内容审核'));
          return (
            <div key={t.id} className={`planner-chat-row ${isUser ? 'user' : 'assistant'}`}>
              <span className="planner-chat-role">{isUser ? '你' : 'AI'}</span>
              <div className={`planner-chat-bubble ${isUser ? 'user' : 'assistant'}${blocked ? ' blocked' : ''}`}>
                {text}
              </div>
            </div>
          );
        })}
        {streamingText && (
          // Ghost bubble for the in-flight assistant reply. Replaced by a real turn from
          // the final `done` payload, which resolves session.turns with the persisted row.
          /**
           * 【流式传输幽灵气泡】
           * SSE 流式传输期间显示的临时 AI 回复气泡，
           * 带有闪烁光标动画。传输完成后会被服务端返回的正式 turn 数据替换。
           */
          <div className="planner-chat-row assistant">
            <span className="planner-chat-role">AI</span>
            <div className="planner-chat-bubble assistant streaming">
              {stripEnvelopes(streamingText, '（计划草案生成中…）')}
              <span className="planner-chat-cursor" aria-hidden="true">▋</span>
            </div>
          </div>
        )}
        {busyKind === 'send' && !streamingText && (
          /**
           * 【等待首字节的打字动画】
           * 发送消息后、收到第一个流式 token 前，显示三点跳动的打字动画，
           * 让用户知道 AI 正在思考中。
           */
          <div className="planner-chat-row assistant">
            <span className="planner-chat-role">AI</span>
            <div className="planner-chat-bubble assistant streaming">
              <span className="planner-chat-typing">
                <span /><span /><span />
              </span>
            </div>
          </div>
        )}
      </div>

      {!busy && session.pendingClarify && session.pendingClarify.questions
        && session.pendingClarify.questions.length > 0 && (
        /**
         * 【澄清选项卡片】
         * AI 需要补充信息时给出的结构化"选择题"，用户点选即可作答，
         * 替代以往纯文字一问一答。单选高亮一个、多选可勾选多个，
         * 每题附带"其他"自由文本框。点"提交选择"把选择拼成消息回灌继续对话。
         */
        <div className="panel" style={{ marginTop: 8 }}>
          <div className="panel-title">
            <h4><ListChecks size={14} style={{ verticalAlign: '-2px', marginRight: 4 }} />帮 AI 补充几个信息</h4>
            <span className="muted small">点选即可，下方也可直接打字回复</span>
          </div>
          {session.pendingClarify.questions.map((q, i) => {
            const key = clarifyKey(q, i);
            const picked = clarifyPick[key] ?? [];
            return (
              <div key={key} className="task-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}>
                <strong>{q.question}{q.multiSelect ? ' （可多选）' : ''}</strong>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  {q.options.map((opt) => {
                    const active = picked.includes(opt.label);
                    return (
                      <button
                        key={opt.label}
                        type="button"
                        disabled={busy}
                        className={active ? 'primary-button small' : 'secondary-button small'}
                        title={opt.hint ?? ''}
                        onClick={() => toggleClarify(q, i, opt.label)}
                      >
                        {opt.label}{opt.hint ? <span className="muted small" style={{ marginLeft: 4 }}>· {opt.hint}</span> : null}
                      </button>
                    );
                  })}
                </div>
                <input
                  className="text-input"
                  placeholder="其他（自己补充，可选）"
                  value={clarifyOther[key] ?? ''}
                  onChange={(e) => setClarifyOther({ ...clarifyOther, [key]: e.target.value })}
                  disabled={busy}
                />
              </div>
            );
          })}
          <div className="row-actions" style={{ marginTop: 6 }}>
            <button
              className="primary-button"
              disabled={busy}
              onClick={() => submitClarify(session.pendingClarify!.questions)}
            >
              <Send size={14} /> 提交选择
            </button>
          </div>
        </div>
      )}

      {session.pendingPlan && session.pendingPlan.tasks && session.pendingPlan.tasks.length > 0 && (
        /**
         * 【计划草案面板】
         * AI 生成的计划草案展示区域，每个任务支持：
         * - 查看模式：显示标题、时长、难度、类型和描述，以及编辑/删除按钮
         * - 编辑模式：内联表单编辑所有字段，支持保存和取消
         */
        <div className="panel" style={{ marginTop: 8 }}>
          <div className="panel-title">
            <h4>计划草案</h4>
            <span className="muted small">可直接编辑或删除单条后再确认</span>
          </div>
          {session.pendingPlan.tasks.map((t, i) => (
            editingIdx === i ? (
              <div key={i} className="task-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}>
                <input
                  className="text-input"
                  value={editDraft.title}
                  onChange={e => setEditDraft({ ...editDraft, title: e.target.value })}
                  placeholder="标题"
                  maxLength={128}
                />
                <textarea
                  className="text-input"
                  value={editDraft.description ?? ''}
                  onChange={e => setEditDraft({ ...editDraft, description: e.target.value })}
                  placeholder="描述"
                  rows={2}
                />
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <label className="muted small">
                    分钟
                    <input
                      type="number" min={5} max={240}
                      style={{ width: 70, marginLeft: 4 }}
                      value={editDraft.estimatedMinutes ?? 30}
                      onChange={e => setEditDraft({ ...editDraft, estimatedMinutes: Number(e.target.value) })}
                    />
                  </label>
                  <label className="muted small">
                    难度
                    <select
                      style={{ marginLeft: 4 }}
                      value={editDraft.difficulty ?? 'NORMAL'}
                      onChange={e => setEditDraft({ ...editDraft, difficulty: e.target.value as PendingPlanTask['difficulty'] })}
                    >
                      <option value="EASY">EASY</option>
                      <option value="NORMAL">NORMAL</option>
                      <option value="HARD">HARD</option>
                      <option value="CHALLENGE">CHALLENGE</option>
                    </select>
                  </label>
                  <label className="muted small">
                    类型
                    <select
                      style={{ marginLeft: 4 }}
                      value={editDraft.taskType ?? 'STUDY'}
                      onChange={e => setEditDraft({ ...editDraft, taskType: e.target.value as PendingPlanTask['taskType'] })}
                    >
                      <option value="STUDY">STUDY</option>
                      <option value="CODING">CODING</option>
                      <option value="NOTE">NOTE</option>
                      <option value="MEMORY">MEMORY</option>
                      <option value="REVIEW">REVIEW</option>
                      <option value="SIMPLE">SIMPLE</option>
                    </select>
                  </label>
                </div>
                <div className="row-actions" style={{ gap: 6 }}>
                  <button className="primary-button small" disabled={busy} onClick={() => saveEdit(i)}>
                    <Save size={12} /> 保存
                  </button>
                  <button className="secondary-button small" disabled={busy} onClick={() => setEditingIdx(null)}>
                    <XCircle size={12} /> 取消
                  </button>
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
                  <button className="secondary-button small" disabled={busy} onClick={() => startEdit(i, t)} title="编辑">
                    <Pencil size={12} />
                  </button>
                  <button className="danger-button small" disabled={busy} onClick={() => deletePendingTask(i)} title="删除">
                    <Trash2 size={12} />
                  </button>
                </div>
              </div>
            )
          ))}
        </div>
      )}

      {busyKind === 'commit' && (
        <div className="muted small" style={{ marginTop: 8 }}>
          正在落地计划并创建任务…
        </div>
      )}

      {error && <div className="form-error">{error}</div>}

      {!terminal && (
        <>
          <div className="row-actions" style={{ marginTop: 8, flexWrap: 'wrap', gap: 6 }}>
            {session.suggestedActions.includes('just_give_me_a_plan') && (
              <button className="secondary-button" disabled={busy} onClick={() => send('直接给我计划')}>直接给我计划</button>
            )}
            {session.suggestedActions.includes('commit') && (
              <button className="primary-button" disabled={busy} onClick={() => commit()}><CheckCircle2 size={14} /> 确认计划</button>
            )}
            {session.suggestedActions.includes('adjust') && (
              <button className="secondary-button" disabled={busy} onClick={() => setInput('我想调整一下：')}>调整</button>
            )}
            <button className="secondary-button" disabled={busy} onClick={abandon}>放弃</button>
          </div>

          {editingIdx !== null && (
            // Issue #6: while user is mid-edit on a pending plan task, freeze the chat
            // input. Otherwise sending a message would silently drop the entire hand-edited
            // pendingPlanJson on the backend (postMessage resets PLAN_PROPOSED → DRAFTING).
            /**
             * 【编辑锁定提示】
             * Issue #6 修复：当用户正在编辑计划草案任务时，冻结聊天输入框。
             * 因为发送消息会触发 postMessage，后端会将状态从 PLAN_PROPOSED 重置为 DRAFTING，
             * 导致用户手写的编辑内容被静默丢弃。
             */
            <div className="muted small" style={{ marginTop: 8 }}>
              正在编辑计划草案 — 请先保存或取消编辑，再继续与 AI 对话。
            </div>
          )}
          <div className="planner-input" style={{ marginTop: 8 }}>
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder={editingIdx !== null
                ? '先保存或取消正在编辑的计划任务'
                : session.kind === 'NEW_GOAL' ? '继续描述你的目标或回答 AI 的问题…' : '说说你的进展或阻塞…'}
              disabled={busy || editingIdx !== null}
            />
            <button
              className="primary-button"
              disabled={busy || editingIdx !== null || !input.trim()}
              onClick={() => send()}
            >
              <Send size={14} /> 发送
            </button>
          </div>
        </>
      )}

      {session.state === 'COMMITTED' && (
        <div className="panel" style={{ background: 'var(--surface-2)', marginTop: 8 }}>
          ✅ 已根据计划创建任务并写入目标记忆。
        </div>
      )}

      {session.distillationWarning && (
        // Issue #7: surface the silent-drop so the user knows the long-term memory was
        // not updated this round and can capture critical points manually if needed.
        /**
         * 【蒸馏记忆超限警告】
         * Issue #7 修复：当 AI 的蒸馏记忆超过 4KB 上限时，
         * 后端会静默丢弃本轮记忆更新。此横幅告知用户这一情况，
         * 让用户可以手动记录关键信息。
         */
        <div className="planner-distill-banner">
          ⚠️ {session.distillationWarning}
        </div>
      )}
    </div>
  );
}
