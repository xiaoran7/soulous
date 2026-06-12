/**
 * 【每日复盘页面】DailyReviewPage
 * 本页面负责展示 AI 生成的每日学习复盘报告，包括：
 * - 今日完成度（任务完成环形进度）
 * - AI 流式解读（打字机效果展示复盘文字）
 * - 课程分布饼图
 * - 关键指标（完成任务数、提交凭证、学习时长、获得经验）
 * - 亮点/风险/建议列表
 * - 宠物反馈
 *
 * 核心交互：用户点击"开始复盘"按钮后，页面会通过流式接口实时展示 AI 生成的复盘内容，
 * 如果流式接口失败会自动降级到普通接口。复盘结果会缓存在父组件状态中，切换页面后不会丢失。
 */
import React, { Suspense, lazy, useEffect, useState } from 'react';
import { CheckCircle2, ClipboardList, PawPrint, Play, RefreshCw, Sparkles, Timer } from 'lucide-react';
import { api } from '../api';
import type { DailyReview, Summary } from '../types';
import { Metric, ProgressRing } from '../components/shared';

/** 【懒加载饼图组件】仅在用户滚动到课程分布区域时才加载，减少首屏体积 */
const CoursePie = lazy(() => import('../components/ReviewCharts'));
/** 【懒加载趋势图组件】原统计页合并而来，展示近 7 天学习时长 */
const TrendChart = lazy(() => import('../components/TrendChart'));

/**
 * 【复盘列表组件】用于展示亮点、风险、建议等字符串列表
 * @param title - 列表标题（如"今日亮点"、"需要留意"）
 * @param items - 列表项数组，为空时显示默认提示
 */
function ReviewList({ title, items }: { title: string; items: string[] }) {
  return (
    <section className="panel review-list-panel">
      <div className="panel-title"><h2>{title}</h2></div>
      <ul className="review-list">
        {items.map((item, index) => <li key={`${item}-${index}`}>{item}</li>)}
      </ul>
    </section>
  );
}

/**
 * 【AI 叙事散文组件】将 AI 生成的长文本按空行分段渲染
 * 设计思路：AI 生成的复盘文本通常包含多个段落，用空行分隔可读性更好，
 * 因此按 `\n\s*\n` 拆分后逐段渲染为 <p> 标签。
 *
 * Render a multi-paragraph narrative — split on blank lines so the AI's prose breathes.
 */
function NarrativeProse({ text }: { text: string }) {
  // 【按空行拆分段落】过滤空白内容，确保每段都有实际文字
  const paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  if (paragraphs.length === 0) return null;
  return (
    <div className="daily-review-prose">
      {paragraphs.map((p, i) => <p key={i}>{p}</p>)}
    </div>
  );
}

/**
 * 【每日复盘页面主组件】
 * @param summary - 今日摘要数据（来自父组件 App），包含今日任务数、提交数等
 * @param review - 已生成的复盘数据（父组件维护），页面挂载时会尝试恢复
 * @param onReviewChange - 复盘数据变更回调，通知父组件更新状态
 *
 * 状态管理：
 * - loading: 是否正在生成复盘
 * - error: 错误信息
 * - narrative: AI 流式输出的复盘文字（即使 loading 结束也会保留供用户重读）
 */
export function DailyReviewPage({ summary, review, onReviewChange }: {
  summary: Summary | null;
  review: DailyReview | null;
  onReviewChange: (review: DailyReview | null) => void;
}) {
  /** 【加载状态】控制按钮禁用和加载动画 */
  const [loading, setLoading] = useState(false);
  /** 【错误信息】展示接口调用失败的原因 */
  const [error, setError] = useState('');
  /**
   * 【流式叙事文本】在流式传输过程中逐步拼接，加载结束后保留供用户重读。
   * 只有当用户再次点击"生成"时才会清空重新开始。
   *
   * The streamed prose is preserved past loading=false so the user can re-read it
   * after the structured panels populate. Only cleared when a new generate() starts.
   */
  const [narrative, setNarrative] = useState(review?.summary ?? '');

  /**
   * 【恢复叙事文本】当用户从其他页面切换回来时，如果 narrative 为空但 review 有数据，
   * 则从 review 中恢复叙事文本。这是因为 review 状态保存在父组件，页面可能被重新挂载。
   *
   * Re-hydrate from the parent-held review when the user returns to this page after
   * navigating away (review state lives in App; the page may remount).
   */
  useEffect(() => {
    if (!loading && review?.summary && narrative === '') setNarrative(review.summary);
  }, [review?.summary, loading]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * 【生成复盘】核心业务逻辑：
   * 1. 清空旧数据，进入加载状态
   * 2. 优先使用流式接口（dailyReviewStream），实时展示打字机效果
   * 3. 流式完成后，如果服务端返回的最终摘要更丰富，则替换本地流式文本
   * 4. 如果流式接口失败，自动降级到普通接口（dailyReview）
   * 5. 两种接口都失败时，展示错误信息
   */
  async function generate() {
    setLoading(true);
    setError('');
    setNarrative('');
    try {
      /**
       * 【流式接口调用】通过回调函数逐步拼接 AI 输出的 token，
       * 实现打字机效果。chunk 是每次回调收到的文本片段。
       *
       * Streaming endpoint: typing-effect tokens while the structured envelope assembles.
       */
      const next = await api.dailyReviewStream((chunk) => {
        setNarrative((prev) => prev + chunk);
      });
      onReviewChange(next);
      /**
       * 【最终摘要替换】如果服务端返回的完整摘要比流式拼接的更长更丰富，
       * 则使用服务端版本（可能包含格式化处理等）。
       *
       * Prefer the server's final summary if it's richer than what we streamed.
       */
      if (next?.summary && next.summary.length > 0) setNarrative(next.summary);
    } catch (streamErr) {
      // 【降级处理】流式接口失败时，尝试普通接口
      try {
        const fallback = await api.dailyReview();
        onReviewChange(fallback);
        if (fallback?.summary) setNarrative(fallback.summary);
      } catch (err) {
        setError(err instanceof Error ? err.message : (streamErr instanceof Error ? streamErr.message : '复盘生成失败'));
      }
    } finally {
      setLoading(false);
    }
  }

  // ===== 【计算指标数据】用于展示完成度环形图和指标卡片 =====
  const metrics = review?.metrics;
  /** 【已完成任务数】从复盘指标中获取，默认为 0 */
  const completed = metrics?.completedTasks ?? 0;
  /** 【今日总任务数】优先使用 summary 的 todayTasks，否则取 completed 的最小值 */
  const totalToday = summary?.todayTasks ?? Math.max(completed, 0);
  /** 【环形图最大值】取 totalToday 和 completed 的较大值，至少为 1 避免除零 */
  const completionRingMax = Math.max(totalToday, completed, 1);
  /** 【完成百分比】用于环形图中央显示 */
  const completionPercent = Math.round((completed / completionRingMax) * 100);

  return (
    // 固定外框：顶部横幅锁定，AI 报告区在 .dr-scroll 内部滚动（用户确认「AI 报告区内部滚动」）
    <div className="dr-fixed">
      {/* ===== 【复盘首屏】整宽横幅：日期 / 标题 / 摘要 + 生成按钮（固定不滚动）===== */}
      <section className="panel daily-review-hero dr-top">
        <div className="daily-review-hero-copy">
          <p className="page-eyebrow" style={{ marginBottom: 6 }}>{review?.date ?? new Date().toISOString().slice(0, 10)} · Reflection</p>
          <h2>{review?.title ?? (loading ? '正在生成今日复盘' : '今日复盘')}</h2>
          <p>{review?.summary ?? '点击右侧按钮，Soulous 会根据今天的任务、提交、学习时长、经验和宠物状态生成一份轻量复盘。生成后会保留在本页，切换栏目不会刷新。'}</p>
        </div>
        {/* 【生成/重新生成按钮】已有复盘时显示刷新图标，否则显示播放图标 */}
        <button className="primary-button" onClick={generate} disabled={loading}>
          {review ? <RefreshCw size={14} /> : <Play size={14} />}
          {loading ? '生成中' : review ? '重新生成' : '开始复盘'}
        </button>
      </section>

      {/* ===== AI 报告滚动区：解读 / 指标 / 图表 / 洞察 / 宠物反馈 ===== */}
      <div className="dr-scroll">
      {/* ===== 【错误提示】展示错误信息和重试按钮 ===== */}
      {error && (
        <section className="panel">
          <div className="form-error" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <span>{error}</span>
            <button className="secondary-button compact-button" onClick={generate}>
              <RefreshCw size={12} /> 重试
            </button>
          </div>
        </section>
      )}

      {/* ===== 【流式输出区域】加载中时展示打字机效果 ===== */}
      {loading && (
        <section className="panel daily-review-narrative">
          <div className="panel-title">
            <h2><Sparkles size={14} style={{ verticalAlign: '-2px', marginRight: 6 }} />AI 解读</h2>
          </div>
          <div className="planner-chat-row assistant">
            <span className="planner-chat-role">AI</span>
            <div className="planner-chat-bubble assistant streaming">
              {narrative ? (
                <>
                  {narrative}
                  {/* 【光标动画】模拟打字机效果的闪烁光标 */}
                  <span className="planner-chat-cursor" aria-hidden="true">▋</span>
                </>
              ) : (
                /* 【加载动画】等待首个 token 时显示跳动的点 */
                <span className="planner-chat-typing"><span /><span /><span /></span>
              )}
            </div>
          </div>
        </section>
      )}

      {/* ===== 【AI 解读面板】加载完成后展示完整的叙事文本 ===== */}
      {!loading && narrative && (
        <section className="panel daily-review-narrative">
          <div className="panel-title">
            <h2><Sparkles size={14} style={{ verticalAlign: '-2px', marginRight: 6 }} />AI 解读</h2>
          </div>
          <NarrativeProse text={narrative} />
        </section>
      )}

      {/* ===== 【关键指标行】4 个指标卡片，整宽密排 ===== */}
      <section className="metric-row">
        <Metric icon={<CheckCircle2 />} label="完成任务" value={metrics?.completedTasks ?? 0} />
        <Metric icon={<ClipboardList />} label="提交凭证" value={metrics?.submissions ?? summary?.todaySubmissions ?? 0} />
        <Metric icon={<Timer />} label="学习分钟" value={metrics?.studyMinutes ?? summary?.todayMinutes ?? 0} />
        <Metric icon={<Sparkles />} label="获得经验" value={metrics?.earnedExp ?? summary?.todayExp ?? 0} />
      </section>

      {/* ===== 【完成度 + 课程分布】两枚环形/饼图等宽并排 ===== */}
      <div className="dr-row dr-row-2">
        <section className="panel completion-panel">
          <div className="panel-title"><h2>今日完成度</h2></div>
          <div className="completion-body">
            <ProgressRing
              value={completed}
              max={completionRingMax}
              label={`${completionPercent}%`}
              sublabel={`${completed}/${completionRingMax} 任务`}
            />
            <div className="completion-meta">
              <p className="muted">完成任务 / 今日总任务</p>
              <strong>{completed} / {completionRingMax}</strong>
              <p className="muted">提交凭证：{metrics?.submissions ?? 0} 条 · 学习时长：{metrics?.studyMinutes ?? 0} 分</p>
            </div>
          </div>
        </section>

        <section className="panel course-pie-panel">
          <div className="panel-title"><h2>课程分布</h2></div>
          <Suspense fallback={<div className="muted">加载图表中...</div>}>
            <CoursePie courses={summary?.courses ?? {}} />
          </Suspense>
        </section>
      </div>

      {/* ===== 【学习时长趋势】近 7 天柱状图整宽（原统计页合并而来）===== */}
      <section className="panel">
        <div className="panel-title"><h2>学习时长趋势（近 7 天）</h2></div>
        <Suspense fallback={<div className="muted">加载图表中...</div>}>
          <TrendChart data={summary?.trend ?? []} />
        </Suspense>
      </section>

      {/* ===== 【亮点 / 风险 / 建议】三列等宽洞察 ===== */}
      <div className="dr-row dr-row-3">
        <ReviewList title="今日亮点" items={review?.highlights ?? ['还没有可展示的亮点，完成一个任务后再回来看看。']} />
        <ReviewList title="需要留意" items={review?.risks ?? ['暂无风险数据。']} />
        <ReviewList title="明日建议" items={review?.tomorrowSuggestions ?? ['创建一个 30 分钟以内的新学习任务。']} />
      </div>

      {/* ===== 【宠物反馈】展示宠物对今日学习的评价 ===== */}
      <section className="panel pet-feedback-panel">
        <div className="panel-title"><h2>宠物 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>反馈</em></h2><PawPrint size={16} /></div>
        <p className="pet-message">"{review?.petMessage ?? '宠物正在等待今天的学习凭证。'}"</p>
      </section>
      </div>
    </div>
  );
}
