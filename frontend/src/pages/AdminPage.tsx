/**
 * 【管理员审核后台页面】AdminPage
 * 本页面是管理员专用的审核后台，提供以下核心功能：
 * - 提交队列：查看待审核的学习凭证，进行人工通过/要求补充/驳回操作
 * - 申诉队列：处理用户对 AI 审核结果的申诉
 * - 运行指标：监控系统运行状态（LLM 调用、限流、内容审核、安全告警等）
 * - 新建账号：管理员创建新用户或管理员账号
 *
 * 权限控制：只有 role === 'ADMIN' 的用户才能访问此页面
 * 数据流：提交队列和申诉队列都支持"待办/全部"两种视图切换
 */
import React, { useEffect, useState } from 'react';
import { Activity, AlertTriangle, RefreshCw, Shield, ShieldAlert, Sparkles, Trash2 } from 'lucide-react';
import { api } from '../api';
import type { AiReview, Appeal, MetricsSnapshot, StudyTask, Submission, User } from '../types';
import { Empty } from '../components/shared';
import { ProofThumbStrip } from '../components/ProofUploader';

/**
 * 【默认管理员评语】根据操作类型生成默认的复核意见
 * @param status - 当前提交状态
 * @returns 对应的默认评语文本
 */
function defaultAdminComment(status: string) {
  if (status === 'NEED_MORE') return '请补充更具体的学习过程、截图或代码片段。';
  if (status === 'AI_REJECTED' || status === 'MANUAL_REJECTED') return '凭证暂不足以证明任务完成，请重新提交更完整材料。';
  return '管理员确认凭证有效。';
}

/**
 * 【提交队列项类型】包含提交记录和宠物等级（用于辅助判断）
 */
type QueueItem = { submission: Submission; petLevel: number | null };
/**
 * 【申诉队列项类型】包含申诉记录及其关联的提交、任务、用户、AI 审核等完整上下文
 */
type AppealItem = {
  appeal: Appeal;
  submission: Submission | null;
  task: StudyTask | null;
  user: User | null;
  petLevel: number | null;
  aiReview: AiReview | null;
};

/**
 * 【管理员页面主组件】
 * @param user - 当前登录用户信息，用于权限校验
 * @param onRefresh - 刷新父组件数据的回调
 *
 * 核心状态：
 * - queue: 提交队列数据
 * - appeals: 申诉队列数据
 * - detail: 当前查看的提交详情（含 AI 审核结果）
 * - reviewForm: 复核表单（经验数额 + 评语）
 * - scope/appealScope: 队列视图切换（todo=待办/all=全部）
 */
export function AdminPage({ user, onRefresh }: { user: User; onRefresh: () => void }) {
  const [queue, setQueue] = useState<QueueItem[]>([]);
  const [scope, setScope] = useState<'todo' | 'all'>('todo');
  const [appealScope, setAppealScope] = useState<'todo' | 'all'>('todo');
  const [appeals, setAppeals] = useState<AppealItem[]>([]);
  /** 【申诉经验覆盖】管理员可以为每个申诉单独设置通过时发放的经验值 */
  const [appealExpById, setAppealExpById] = useState<Record<number, number>>({});
  /** 【当前查看的提交详情】包含提交、AI 审核、任务、用户、宠物等级等完整信息 */
  const [detail, setDetail] = useState<{ submission: Submission; review: AiReview; task: StudyTask; user: User; petLevel: number | null } | null>(null);
  /** 【复核表单】管理员填写的经验数额和评语 */
  const [reviewForm, setReviewForm] = useState({ expAmount: 0, comment: '管理员确认通过' });
  /** 【操作忙碌状态】防止重复点击 */
  const [acting, setActing] = useState(false);
  /** 【申诉操作中的 ID 集合】防止重复操作 */
  const [appealActingIds, setAppealActingIds] = useState<Set<number>>(new Set());
  const [adminError, setAdminError] = useState('');
  /** 【权限校验】只有管理员才能操作 */
  const canAdmin = user.role === 'ADMIN';

  /**
   * 【加载队列数据】同时加载提交队列和申诉队列
   * @param nextScope - 提交队列视图
   * @param nextAppealScope - 申诉队列视图
   *
   * 数据结构：API 返回的是嵌套对象，需要拆解为前端类型
   */
  async function load(nextScope: 'todo' | 'all' = scope, nextAppealScope: 'todo' | 'all' = appealScope) {
    if (!canAdmin) return;
    setAdminError('');
    try {
      const data = await api.adminSubmissions(nextScope);
      const items: QueueItem[] = Array.isArray(data)
        ? (data as any[]).map((it) => ({
            submission: it.submission as Submission,
            petLevel: (it.petLevel ?? null) as number | null
          }))
        : [];
      setQueue(items);
      const appealData = await api.adminAppeals(nextAppealScope);
      const aItems: AppealItem[] = Array.isArray(appealData)
        ? (appealData as any[]).map((it) => ({
            appeal: it.appeal as Appeal,
            submission: (it.submission ?? null) as Submission | null,
            task: (it.task ?? null) as StudyTask | null,
            user: (it.user ?? null) as User | null,
            petLevel: (it.petLevel ?? null) as number | null,
            aiReview: (it.aiReview ?? null) as AiReview | null
          }))
        : [];
      setAppeals(aItems);
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : '加载管理数据失败');
    }
  }

  // 【初始化加载】依赖权限和视图切换时重新加载数据
  useEffect(() => { void load(scope, appealScope); }, [canAdmin, scope, appealScope]);

  /**
   * 【打开提交详情】加载单条提交的完整审核信息
   * @param id - 提交 ID
   * 同时初始化复核表单的默认值（经验数额取 AI 推荐值或任务基础经验）
   */
  async function open(id: number) {
    setAdminError('');
    try {
      const nextDetail = await api.adminSubmission(id) as { submission: Submission; review: AiReview; task: StudyTask; user: User; petLevel: number | null };
      setDetail(nextDetail);
      setReviewForm({
        expAmount: nextDetail.review?.recommendedExp ?? nextDetail.task.baseExp,
        comment: defaultAdminComment(nextDetail.submission.status)
      });
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : '加载提交详情失败');
    }
  }

  /**
   * 【执行审核操作】管理员对提交进行人工通过/要求补充/驳回
   * @param kind - 操作类型：approve=通过, reject=驳回, need=要求补充
   * 操作完成后刷新队列并关闭详情面板
   */
  async function act(kind: 'approve' | 'reject' | 'need') {
    if (!detail) return;
    setAdminError('');
    setActing(true);
    try {
      const comment = reviewForm.comment.trim() || defaultAdminComment(detail.submission.status);
      if (kind === 'approve') await api.approveSubmission(detail.submission.id, Math.max(0, reviewForm.expAmount), comment);
      if (kind === 'reject') await api.rejectSubmission(detail.submission.id, comment);
      if (kind === 'need') await api.needMoreSubmission(detail.submission.id, comment);
      await Promise.all([load(), onRefresh()]);
      setDetail(null);
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setActing(false);
    }
  }

  /**
   * 【处理申诉】管理员对用户申诉进行复核
   * @param item - 申诉项
   * @param status - 复核结果：APPROVED=通过, REJECTED=驳回, NEED_MORE=要求补充
   *
   * 通过时发放经验，默认值为 AI 推荐值或任务基础经验，管理员可手动调整
   */
  async function reviewAppeal(item: AppealItem, status: 'APPROVED' | 'REJECTED' | 'NEED_MORE') {
    const id = item.appeal.id;
    setAdminError('');
    setAppealActingIds((prev) => new Set(prev).add(id));
    try {
      const comment = status === 'APPROVED' ? '申诉通过，管理员已记录。'
        : status === 'NEED_MORE' ? '申诉信息不足，请补充更具体的凭证。'
        : '经复核维持原判定。';
      const expAmount = status === 'APPROVED'
        ? (appealExpById[id] ?? item.aiReview?.recommendedExp ?? item.task?.baseExp ?? 0)
        : undefined;
      await api.reviewAppeal(id, status, comment, expAmount);
      await Promise.all([load(), onRefresh()]);
    } catch (err) {
      setAdminError(err instanceof Error ? err.message : '申诉处理失败');
    } finally {
      setAppealActingIds((prev) => { const next = new Set(prev); next.delete(id); return next; });
    }
  }

  // 【权限不足提示】非管理员用户看到的页面
  if (!canAdmin) {
    return <section className="panel"><Empty text="当前账号不是管理员，无法进入审核后台。" /></section>;
  }

  return (
    <div className="two-column">
      {/* 【运行指标面板】显示系统运行状态 */}
      <MetricsPanel />

      {/* ===== 【提交队列】查看和处理待审核的学习凭证 ===== */}
      <section className="panel">
        <div className="panel-title">
          <h2>提交队列</h2>
          <div className="row-actions">
            <button
              className={scope === 'todo' ? 'primary-button' : 'secondary-button'}
              onClick={() => setScope('todo')}
            >待办</button>
            <button
              className={scope === 'all' ? 'primary-button' : 'secondary-button'}
              onClick={() => setScope('all')}
            >全部</button>
            <button className="icon-button" onClick={() => void load(scope)}><RefreshCw size={16} /></button>
          </div>
        </div>
        {adminError && !detail && <div className="form-error">{adminError}</div>}
        <div className="task-list">
          {queue.map(({ submission, petLevel }) => (
            <button className="task-card" key={submission.id} onClick={() => open(submission.id)}>
              <div className="task-row">
                <strong>提交 #{submission.id}</strong>
                <span className={`badge ${submission.status}`}>{submission.status}</span>
              </div>
              <div className="muted small">
                {submission.user?.nickname ?? '未知用户'}
                {petLevel != null && <> · 宠物 Lv.{petLevel}</>}
              </div>
              <p className="muted">{submission.textProof}</p>
            </button>
          ))}
          {queue.length === 0 && <Empty text={scope === 'todo' ? '暂无待办提交。' : '暂无提交记录。'} />}
        </div>
      </section>

      {/* ===== 【复核详情面板】查看提交详情并执行审核操作 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>复核详情</h2></div>
        {detail ? (
          <div className="submission-box">
            <h3>{detail.task.title}</h3>
            {/* 【提交元数据】用户、宠物等级、任务经验、学习时长 */}
            <div className="admin-meta-grid">
              <div><span>提交用户</span><strong>{detail.user.nickname}</strong></div>
              <div><span>宠物等级</span><strong>{detail.petLevel != null ? `Lv.${detail.petLevel}` : '-'}</strong></div>
              <div><span>任务经验</span><strong>{detail.task.baseExp}</strong></div>
              <div><span>学习时长</span><strong>{detail.submission.studyMinutes} 分钟</strong></div>
            </div>
            <p>{detail.submission.textProof}</p>
            {/* 【AI 审核结果】分数和建议经验 */}
            <div className="review-score">
              AI 分数 {detail.review?.score ?? '-'} / 建议经验 {detail.review?.recommendedExp ?? '-'}
            </div>
            {/* 【评分明细】相关性、完整度、质量 */}
            {detail.review && (
              <div className="score-breakdown">
                <span>相关性 {detail.review.relevanceScore}</span>
                <span>完整度 {detail.review.completenessScore}</span>
                <span>质量 {detail.review.qualityScore}</span>
              </div>
            )}
            <p className="muted">{detail.review?.reason}</p>
            {/* 【复核表单】管理员填写经验数额和评语 */}
            <div className="admin-review-form">
              <label className="field-label">
                <span>发放经验</span>
                <input
                  type="number"
                  min={0}
                  value={reviewForm.expAmount}
                  onChange={(e) => setReviewForm({ ...reviewForm, expAmount: Number(e.target.value) })}
                />
              </label>
              <label className="field-label">
                <span>复核意见</span>
                <textarea
                  value={reviewForm.comment}
                  onChange={(e) => setReviewForm({ ...reviewForm, comment: e.target.value })}
                  placeholder="写给用户看的复核说明"
                />
              </label>
            </div>
            {adminError && <div className="form-error">{adminError}</div>}
            {/* 【操作按钮】人工通过、要求补充、驳回 */}
            <div className="row-actions">
              <button className="primary-button" onClick={() => act('approve')} disabled={acting}>{acting ? '处理中...' : '人工通过'}</button>
              <button className="secondary-button" onClick={() => act('need')} disabled={acting}>要求补充</button>
              <button className="danger-button" onClick={() => act('reject')} disabled={acting}>驳回</button>
            </div>
          </div>
        ) : <Empty text="选择一条提交记录查看详情。" />}
      </section>

      {/* ===== 【申诉队列】处理用户对 AI 审核结果的申诉 ===== */}
      <section className="panel full">
        <div className="panel-title">
          <h2>申诉队列</h2>
          <div className="row-actions">
            <button
              className={appealScope === 'todo' ? 'primary-button' : 'secondary-button'}
              onClick={() => setAppealScope('todo')}
            >待办</button>
            <button
              className={appealScope === 'all' ? 'primary-button' : 'secondary-button'}
              onClick={() => setAppealScope('all')}
            >全部</button>
            <button className="icon-button" onClick={() => void load(scope, appealScope)}><RefreshCw size={16} /></button>
          </div>
        </div>
        <div className="task-list">
          {appeals.map((item) => {
            const { appeal, submission, task, user: appealUser, petLevel, aiReview } = item;
            const id = appeal.id;
            const busy = appealActingIds.has(id);
            const defaultExp = aiReview?.recommendedExp ?? task?.baseExp ?? 0;
            const expValue = appealExpById[id] ?? defaultExp;
            return (
              <div className="task-card" key={id}>
                <div className="task-row">
                  <div>
                    <strong>申诉 #{id}</strong>
                    {task && <span className="muted small"> · {task.title}</span>}
                  </div>
                  <span className={`badge ${appeal.status}`}>{appeal.status}</span>
                </div>
                <div className="muted small">
                  {appealUser?.nickname ?? '未知用户'}
                  {petLevel != null && <> · 宠物 Lv.{petLevel}</>}
                  {submission && <> · 提交 #{submission.id} <span className={`badge ${submission.status}`}>{submission.status}</span></>}
                </div>
                <p><strong>申诉理由：</strong>{appeal.appealReason}</p>
                {/* 【申诉截图】展示用户补充的证据 */}
                {appeal.screenshotUrls && (
                  <ProofThumbStrip urls={appeal.screenshotUrls.split(',').map((u) => u.trim()).filter(Boolean)} />
                )}
                {submission?.textProof && <p className="muted">原凭证：{submission.textProof}</p>}
                {aiReview && (
                  <p className="muted small">
                    AI 分数 {aiReview.score} / 建议经验 {aiReview.recommendedExp} · {aiReview.reason}
                  </p>
                )}
                {/* 【待处理申诉】显示经验输入和操作按钮 */}
                {appeal.status === 'PENDING' && (
                  <>
                    <label className="field-label">
                      <span>通过时发放经验</span>
                      <input
                        type="number"
                        min={0}
                        value={expValue}
                        onChange={(e) => setAppealExpById((prev) => ({ ...prev, [id]: Number(e.target.value) }))}
                      />
                    </label>
                    <div className="row-actions">
                      <button className="primary-button" onClick={() => reviewAppeal(item, 'APPROVED')} disabled={busy}>
                        {busy ? '处理中...' : '通过申诉'}
                      </button>
                      <button className="secondary-button" onClick={() => reviewAppeal(item, 'NEED_MORE')} disabled={busy}>要求补充</button>
                      <button className="danger-button" onClick={() => reviewAppeal(item, 'REJECTED')} disabled={busy}>驳回申诉</button>
                    </div>
                  </>
                )}
                {/* 【已处理申诉】显示管理员的复核意见 */}
                {appeal.status !== 'PENDING' && appeal.adminComment && (
                  <p className="muted small">复核意见：{appeal.adminComment}</p>
                )}
              </div>
            );
          })}
          {appeals.length === 0 && <Empty text={appealScope === 'todo' ? '暂无待办申诉。' : '暂无申诉记录。'} />}
        </div>
      </section>

      {/* 【新建账号面板】管理员创建新用户 */}
      <CreateAccountPanel />
    </div>
  );
}

/**
 * 【格式化运行时间】将秒数转换为人类可读的时间格式
 * @param seconds - 进程运行的总秒数
 * @returns 格式化的时间字符串，如 "2d 3h 15m"
 */
function formatUptime(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

/**
 * 【标签计数列表组件】通用的标签+计数展示组件
 * @param items - 标签数组，每项包含 label 和 count
 * @param empty - 空数据时的提示文本
 * 用于展示 LLM 调用统计、限流拦截、内容审核等分类数据
 */
function TagCountList({ items, empty }: { items: { label: string; count: number }[]; empty: string }) {
  if (!items || items.length === 0) {
    return <div className="muted small">{empty}</div>;
  }
  return (
    <ul className="metrics-tag-list">
      {items.map((it) => (
        <li key={it.label}>
          <span className="metrics-tag-label">{it.label}</span>
          <span className="metrics-tag-count">{it.count}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * 【运行指标面板组件】展示系统运行状态的各类指标
 *
 * 指标包括：
 * - LLM 调用统计：总调用数、成功/失败数、失败率、按 provider 分类
 * - 限流拦截：按规则分类的拦截次数
 * - 内容审核：按审核结果分类的统计
 * - Refresh-token replay：检测可能的令牌窃取攻击
 * - 孤儿文件 GC：累计清理的孤儿文件数
 * - 通知推送：按类型分类的推送统计
 */
function MetricsPanel() {
  const [data, setData] = useState<MetricsSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function load() {
    setLoading(true); setError('');
    try {
      setData(await api.adminMetrics());
    } catch (err) {
      setError(err instanceof Error ? err.message : '指标加载失败');
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { void load(); }, []);

  /** 【令牌重放次数】非零时可能有安全风险 */
  const replays = data?.refreshTokenReplayed ?? 0;
  /** 【LLM 失败率】超过 20% 时显示红色警告 */
  const llmFailRate = data && data.llm.total > 0 ? Math.round((data.llm.failure / data.llm.total) * 100) : 0;

  return (
    <section className="panel full metrics-panel">
      <div className="panel-title">
        <h2><Activity size={16} style={{ verticalAlign: '-2px', marginRight: 6 }} />运行指标</h2>
        <div className="row-actions">
          {data && <span className="muted small">运行 {formatUptime(data.uptimeSeconds)} · 自进程启动累计</span>}
          <button className="icon-button" onClick={() => void load()} disabled={loading} title="刷新">
            <RefreshCw size={16} />
          </button>
        </div>
      </div>
      {error && <div className="form-error">{error}</div>}
      {!data && !error && <div className="muted small">加载中…</div>}
      {data && (
        <div className="metrics-grid">
          {/* 【LLM 调用卡片】展示大模型调用统计 */}
          <div className="metrics-card">
            <div className="metrics-card-head">
              <Sparkles size={14} /> <span>LLM 调用</span>
            </div>
            <div className="metrics-big">{data.llm.total}</div>
            <div className="muted small">
              成功 {data.llm.success} · 失败 {data.llm.failure}
              {llmFailRate > 0 && <> · 失败率 <strong style={{ color: llmFailRate > 20 ? 'var(--danger)' : 'inherit' }}>{llmFailRate}%</strong></>}
            </div>
            <div style={{ marginTop: 6 }}>
              <TagCountList items={data.llm.byProvider} empty="按 provider 暂无数据" />
            </div>
          </div>

          {/* 【限流拦截卡片】展示各规则的拦截次数 */}
          <div className="metrics-card">
            <div className="metrics-card-head">
              <Shield size={14} /> <span>限流拦截</span>
            </div>
            <TagCountList items={data.rateLimitBlockedByRule} empty="尚未触发限流" />
          </div>

          {/* 【内容审核卡片】展示审核结果分布 */}
          <div className="metrics-card">
            <div className="metrics-card-head">
              <ShieldAlert size={14} /> <span>内容审核</span>
            </div>
            <TagCountList items={data.moderationByVerdict} empty="尚无审核记录" />
          </div>

          {/* 【令牌重放检测卡片】非零时显示危险样式和警告 */}
          <div className={`metrics-card${replays > 0 ? ' danger' : ''}`}>
            <div className="metrics-card-head">
              {replays > 0 ? <AlertTriangle size={14} /> : <Shield size={14} />}
              <span>Refresh-token replay</span>
            </div>
            <div className="metrics-big">{replays}</div>
            <div className="muted small">{replays > 0 ? '可能有令牌被窃取，检查 audit_log' : '未检测到重放攻击'}</div>
          </div>

          {/* 【孤儿文件 GC 卡片】展示累计清理的文件数 */}
          <div className="metrics-card">
            <div className="metrics-card-head">
              <Trash2 size={14} /> <span>孤儿文件 GC</span>
            </div>
            <div className="metrics-big">{data.storageGcDeleted}</div>
            <div className="muted small">累计清理</div>
          </div>

          {/* 【通知推送卡片】展示按类型分类的推送统计 */}
          <div className="metrics-card">
            <div className="metrics-card-head">
              <Activity size={14} /> <span>通知推送</span>
            </div>
            <TagCountList items={data.notificationsPushedByType} empty="尚未推送通知" />
          </div>
        </div>
      )}
    </section>
  );
}

/**
 * 【新建账号面板组件】管理员创建新用户或管理员账号
 *
 * 表单字段：用户名、初始密码、昵称（可选）、角色（普通用户/管理员）
 * 密码要求：至少 8 位、字母数字符号至少两类、不能包含用户名
 */
function CreateAccountPanel() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  /**
   * 【提交创建账号】校验必填字段后调用 API 创建账号
   * 成功后显示成功消息并清空表单
   */
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setMsg(''); setErr('');
    if (!username.trim() || !password) { setErr('用户名和密码必填'); return; }
    setBusy(true);
    try {
      const created = await api.adminCreateUser(username.trim(), password, nickname.trim(), role);
      setMsg(`已创建 ${role === 'ADMIN' ? '管理员' : '普通用户'}：${created.username}`);
      setUsername(''); setPassword(''); setNickname(''); setRole('USER');
    } catch (e2) {
      setErr(e2 instanceof Error ? e2.message : '创建失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel">
      <div className="panel-title"><h2>新建账号</h2></div>
      <form onSubmit={submit} className="auth-form" style={{ margin: 0 }}>
        <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" autoComplete="off" />
        <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="初始密码" type="password" autoComplete="new-password" />
        <input value={nickname} onChange={(e) => setNickname(e.target.value)} placeholder="昵称（可选）" autoComplete="off" />
        <label className="field-label">
          <span>角色</span>
          <select value={role} onChange={(e) => setRole(e.target.value as 'USER' | 'ADMIN')}>
            <option value="USER">普通用户</option>
            <option value="ADMIN">管理员</option>
          </select>
        </label>
        <p className="muted small">密码至少 8 位、字母数字符号至少两类、不能包含用户名。</p>
        {err && <div className="form-error">{err}</div>}
        {msg && <div className="muted small" style={{ color: 'var(--ink-3)' }}>{msg}</div>}
        <button className="primary-button full-width" disabled={busy}>{busy ? '处理中...' : '创建账号'}</button>
      </form>
    </section>
  );
}
