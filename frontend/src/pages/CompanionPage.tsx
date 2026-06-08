/**
 * 【陪伴宠物聊天页】CompanionPage
 *
 * 一个全新的产品表面：有记忆、会陪伴的宠物。大脑跑在独立的 Anima agent 服务里
 * （带多层记忆 + 人格），前端只负责把用户的话发给 /api/companion/chat 并展示回复。
 * 与「AI 拆解」的 ChatPage 完全独立 —— 这里没有计划草案/任务落地，只是单纯陪你聊。
 *
 * 布局对齐「AI 拆解」：左侧栏展示宠物「记得你什么」（Anima 画像事实），右侧是铺满的对话区。
 * 历史与记忆都由服务端按用户持久化（每用户一条长期宠物会话 + 一份画像），所以刷新 / 切页
 * 回来后消息和记忆都还在。审核委托留下的「提交 + 反馈」也会出现在历史里。
 */
import React, { useEffect, useRef, useState } from 'react';
import { Heart, PawPrint, Send, Sparkles } from 'lucide-react';
import { api } from '../api';
import { PetSprite } from '../PetSprite';
import type { Pet } from '../types';

interface CompanionMessage {
  role: 'user' | 'pet';
  text: string;
}

interface MemoryFact {
  category: string;
  text: string;
}

/**
 * 极简富文本渲染：仅把 **粗体** 转成 <strong>，其余原样输出。
 * 换行由 CSS（white-space: pre-wrap）处理。构造 React 节点而非 innerHTML，天然防 XSS。
 */
function renderRich(text: string): React.ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, i) => {
    const m = /^\*\*([^*]+)\*\*$/.exec(part);
    return m ? <strong key={i}>{m[1]}</strong> : <React.Fragment key={i}>{part}</React.Fragment>;
  });
}

export function CompanionPage({ pet }: { pet: Pet | null }) {
  const name = pet?.name?.trim() || 'Feixue';
  const [messages, setMessages] = useState<CompanionMessage[]>([]);
  const [facts, setFacts] = useState<MemoryFact[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  /** 加载宠物记得你的事实（画像）。发完消息后也刷新一次（记忆是异步提炼的，可能稍后才出现）。 */
  function loadMemory() {
    api.companionMemory()
      .then((d) => setFacts(d.facts ?? []))
      .catch(() => { /* 记忆加载失败不影响聊天 */ });
  }

  /** 挂载时加载宠物会话历史（含审核留下的"提交 + 飞雪反馈"）与记忆。 */
  useEffect(() => {
    let alive = true;
    api.companionHistory()
      .then((d) => {
        if (alive && d.messages?.length) {
          setMessages(d.messages.map((m) => ({ role: m.role === 'pet' ? 'pet' : 'user', text: m.text })));
        }
      })
      .catch(() => { /* 历史加载失败不影响新对话 */ });
    loadMemory();
    return () => { alive = false; };
  }, []);

  /** 新消息/思考态时自动滚到底 */
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, sending]);

  async function send() {
    const text = input.trim();
    if (!text || sending) return;
    setInput('');
    setError(null);
    setMessages((m) => [...m, { role: 'user', text }]);
    setSending(true);
    try {
      const { reply } = await api.companionChat(text);
      setMessages((m) => [...m, { role: 'pet', text: reply }]);
      loadMemory(); // 聊完刷新「它记得你」
    } catch (e) {
      setError(e instanceof Error ? e.message : '出错了，待会儿再试试');
    } finally {
      setSending(false);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
  }

  return (
    <div className="companion-layout">
      {/* 左侧：宠物记忆面板（对标 AI 拆解的会话侧栏） */}
      <aside className="companion-sidebar">
        <div className="companion-side-head">
          <PetSprite state="idle" size={64} label={name} />
          <div className="companion-side-id">
            <strong>{name}</strong>
            <span className="muted small"><Heart size={11} /> 会陪你 · 也会记得你</span>
          </div>
        </div>

        <div className="companion-side-section">
          <div className="companion-side-title"><Sparkles size={13} /> 它记得你</div>
          <div className="companion-facts">
            {facts.length === 0 ? (
              <div className="muted small companion-facts-empty">
                还没什么记忆～多聊几句，{name} 会慢慢记住你。
              </div>
            ) : (
              facts.map((f, i) => (
                <div key={i} className="companion-fact">
                  {f.category && <span className="companion-fact-cat">{f.category}</span>}
                  <span className="companion-fact-text">{f.text}</span>
                </div>
              ))
            )}
          </div>
        </div>

        <div className="companion-side-foot muted small">记忆由 Anima 提供</div>
      </aside>

      {/* 右侧：铺满的对话区 */}
      <main className="companion-main">
        <div className="companion-main-head">
          <h4><PawPrint size={16} /> 和 {name} 聊天</h4>
        </div>

        <div className="companion-messages" ref={listRef}>
          {messages.length === 0 ? (
            <div className="companion-stage">
              <PetSprite state={sending ? 'running' : 'idle'} size={140} label={name} />
              <div className="muted small companion-empty">
                跟 {name} 说点什么吧～它会记住你。
              </div>
            </div>
          ) : (
            <>
              {messages.map((m, i) => (
                <div key={i} className={`companion-bubble ${m.role}`}>{renderRich(m.text)}</div>
              ))}
              {sending && <div className="companion-bubble pet thinking">{name} 在想…</div>}
            </>
          )}
        </div>

        {error && <div className="notice error">{error}</div>}

        <div className="companion-composer">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={`对 ${name} 说点什么…（Enter 发送，Shift+Enter 换行）`}
            rows={2}
            disabled={sending}
          />
          <button
            className="primary-button"
            onClick={() => void send()}
            disabled={sending || !input.trim()}
          >
            <Send size={14} /> 发送
          </button>
        </div>
      </main>
    </div>
  );
}
