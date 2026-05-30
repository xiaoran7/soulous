/**
 * 【目标详情面板组件】
 * 展示单个学习目标的完整信息，包括：
 * - 目标基本信息（标题、状态、目标日期、任务统计）
 * - 历史会话列表（支持打开查看、删除操作）
 * - Distilled Memory（AI 蒸馏的目标记忆，JSON 格式）
 * - 关联任务列表
 *
 * 从 PlannerPage 打开，支持返回列表和打开会话的导航。
 */
import React, { useEffect, useState } from 'react';
import { ArrowLeft, MessageSquare, Trash2 } from 'lucide-react';
import { api } from '../api';
import type { GoalDetail, SessionSummary, SessionView } from '../types';

/**
 * 【日期格式化函数】
 * 将 ISO 日期字符串转换为本地化的日期时间显示。
 * 空值时返回 '—' 占位符。
 *
 * @param iso - ISO 日期字符串（可为 null/undefined）
 * @returns 格式化后的本地日期时间字符串
 */
function fmt(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString();
}

/**
 * 【蒸馏记忆格式化函数】
 * 将 JSON 字符串格式化为可读的缩进格式。
 * 解析失败时返回原始字符串（可能是纯文本格式的记忆）。
 *
 * @param json - JSON 字符串（可为 null）
 * @returns 格式化后的 JSON 或提示文本
 */
function formatMemory(json: string | null): string {
  if (!json) return '（暂无 distilled memory）';
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

/**
 * 【目标详情面板组件】
 *
 * @param goalId - 目标 ID
 * @param onBack - 返回目标列表的回调
 * @param onOpenSession - 打开会话详情的回调，传入完整的 SessionView 数据
 * @param onChanged - 数据变更通知回调（删除会话后触发，通知父组件刷新列表）
 */
export function GoalDetailPanel({ goalId, onBack, onOpenSession, onChanged }: {
  goalId: number;
  onBack: () => void;
  onOpenSession: (view: SessionView) => void;
  onChanged: () => void;
}) {
  /** 【目标详情数据】 */
  const [detail, setDetail] = useState<GoalDetail | null>(null);
  /** 【历史会话列表】 */
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  /** 【错误信息】 */
  const [error, setError] = useState('');
  /** 【加载状态】 */
  const [loading, setLoading] = useState(false);
  /** 【正在打开的会话 ID（用于按钮 loading 状态）】 */
  const [openingSessionId, setOpeningSessionId] = useState<number | null>(null);
  /** 【正在删除的会话 ID（用于按钮 loading 状态）】 */
  const [deletingSessionId, setDeletingSessionId] = useState<number | null>(null);
  /** 【待确认删除的会话 ID（二次确认机制）】 */
  const [confirmDeleteSessionId, setConfirmDeleteSessionId] = useState<number | null>(null);

  /**
   * 【加载目标详情和会话列表】
   * 并行请求目标详情和会话列表，任一失败则显示错误。
   */
  async function load() {
    setLoading(true); setError('');
    try {
      const [d, sList] = await Promise.all([
        api.goalDetail(goalId),
        api.sessionListForGoal(goalId)
      ]);
      setDetail(d);
      setSessions(sList);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }

  /** 【goalId 变化时重新加载数据】 */
  useEffect(() => { void load(); }, [goalId]);

  /**
   * 【打开会话详情】
   * 从 API 获取完整的 SessionView 数据后传递给父组件。
   *
   * @param id - 会话 ID
   */
  async function openSession(id: number) {
    setOpeningSessionId(id);
    try {
      const view = await api.sessionGet(id);
      onOpenSession(view);
    } catch (err) {
      setError(err instanceof Error ? err.message : '打开失败');
    } finally {
      setOpeningSessionId(null);
    }
  }

  /**
   * 【删除会话】
   * 确认删除后调用 API 删除会话，成功后重新加载数据并通知父组件。
   * 已 commit 的会话在 UI 上禁用删除按钮（后端也会拒绝）。
   *
   * @param id - 会话 ID
   */
  async function removeSession(id: number) {
    setConfirmDeleteSessionId(null);
    setDeletingSessionId(id);
    try {
      await api.deleteSession(id);
      await load();
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setDeletingSessionId(null);
    }
  }

  return (
    <div className="panel" style={{ marginTop: 12 }}>
      <div className="panel-title">
        <h2>目标详情</h2>
        <button className="secondary-button" onClick={onBack}>
          <ArrowLeft size={13} /> 返回列表
        </button>
      </div>

      {error && <div className="form-error">{error}</div>}
      {loading && !detail && <div className="muted small">加载中…</div>}

      {detail && (
        <>
          <div style={{ display: 'grid', gap: 6, marginTop: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <strong style={{ fontSize: 16 }}>{detail.title}</strong>
              <span className="chip">{detail.status}</span>
              {detail.targetDate && <span className="chip">📅 {detail.targetDate}</span>}
            </div>
            <div className="muted small">
              任务 {detail.completedTasks}/{detail.totalTasks} · 待办 {detail.openTasks} · 已对话 {detail.sessionCount} 次
              {' · '}创建 {fmt(detail.createdAt)} · 更新 {fmt(detail.updatedAt)}
            </div>
          </div>

          <h3 style={{ marginTop: 14 }}>历史会话（{sessions.length}）</h3>
          {sessions.length === 0 && <div className="muted small">还没有任何会话。</div>}
          {sessions.map((s) => (
            <div key={s.id} className="task-card" style={{ marginTop: 6 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                    <span className="chip">{s.kind}</span>
                    <span className="chip">{s.state}</span>
                    <span className="muted small">{s.turnCount} 轮</span>
                    {s.committedTaskCount > 0 && <span className="chip">commit {s.committedTaskCount} 项</span>}
                  </div>
                  <div className="muted small" style={{ marginTop: 4 }}>
                    开始 {fmt(s.startedAt)} · 最近活动 {fmt(s.lastActivityAt)}
                    {s.endedAt ? ` · 结束 ${fmt(s.endedAt)}` : ''}
                  </div>
                </div>
                <div className="row-actions">
                  <button
                    className="secondary-button"
                    disabled={openingSessionId === s.id}
                    onClick={() => openSession(s.id)}
                  >
                    <MessageSquare size={13} /> {openingSessionId === s.id ? '打开中…' : '查看'}
                  </button>
                  {confirmDeleteSessionId === s.id ? (
                    <>
                      <span className="muted small">确认删除？</span>
                      <button className="secondary-button" style={{ color: 'var(--danger, #e55)' }}
                        disabled={deletingSessionId === s.id}
                        onClick={() => removeSession(s.id)}>
                        确认
                      </button>
                      <button className="secondary-button"
                        onClick={() => setConfirmDeleteSessionId(null)}>
                        取消
                      </button>
                    </>
                  ) : (
                    <button
                      className="secondary-button"
                      disabled={s.state === 'COMMITTED' || deletingSessionId === s.id}
                      onClick={() => setConfirmDeleteSessionId(s.id)}
                      title={s.state === 'COMMITTED' ? '已 commit 的会话不可删除（保留历史）' : '删除会话'}
                    >
                      <Trash2 size={13} /> {deletingSessionId === s.id ? '删除中…' : '删除'}
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}

          <h3 style={{ marginTop: 14 }}>Distilled Memory</h3>
          <pre style={{
            background: 'var(--surface-2)', padding: 10, borderRadius: 6,
            fontSize: 12, overflow: 'auto', maxHeight: 240, whiteSpace: 'pre-wrap', wordBreak: 'break-word'
          }}>
            {formatMemory(detail.distilledMemoryJson)}
          </pre>

          {(() => {
            const active = detail.tasks;
            return (
              <>
                <h3 style={{ marginTop: 14 }}>关联任务（{active.length}）</h3>
                {active.length === 0 && (
                  <div className="muted small">没有关联任务（可能已被解绑）。</div>
                )}
                {active.map((t) => (
                  <div key={t.id} className="task-card" style={{ marginTop: 6 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
                      <div style={{ minWidth: 0 }}>
                        <strong>{t.title}</strong>
                        <div className="muted small" style={{ marginTop: 2 }}>
                          {t.status} · {t.taskType} · {t.difficulty} · {t.estimatedMinutes} 分钟
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </>
            );
          })()}
        </>
      )}
    </div>
  );
}
