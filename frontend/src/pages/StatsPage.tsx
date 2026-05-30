/**
 * 【统计页面】StatsPage
 * 本页面展示用户的学习统计数据：
 * - 关键指标：连续学习天数、累计通过/打回、AI 通过率、专注统计
 * - 学习趋势：近 7 天的学习时长趋势图
 * - 课程占比：各课程的任务数量分布
 * - 任务完成率：已完成任务的百分比和进度条
 *
 * 设计思路：StatsPage 是纯展示页面，不包含任何交互操作。
 * 所有数据都从父组件的 summary 中获取，页面本身不发起 API 请求。
 */
import React, { Suspense, lazy } from 'react';
import { CalendarCheck, CheckCircle2, ShieldCheck, Target, Timer, XCircle } from 'lucide-react';
import type { Summary } from '../types';
import { Metric } from '../components/shared';

/** 【懒加载趋势图组件】仅在滚动到图表区域时加载 */
const TrendChart = lazy(() => import('../components/TrendChart'));

/**
 * 【统计页面主组件】
 * @param summary - 学习摘要数据（从父组件传入）
 *
 * 数据结构说明：
 * - summary.consecutiveDays: 连续学习天数
 * - summary.approvedCount: 累计通过的任务数
 * - summary.rejectedCount: 累计打回的任务数
 * - summary.aiApprovalRate: AI 审核通过率（百分比）
 * - summary.todayFocusMinutes: 今日专注分钟数
 * - summary.todayFocusSessions: 今日专注次数
 * - summary.trend: 近 7 天的学习趋势数据
 * - summary.courses: 课程名称到任务数量的映射
 * - summary.completionRate: 任务完成率（百分比）
 */
export function StatsPage({ summary }: { summary: Summary | null }) {
  /**
   * 【课程数据】将 courses 对象转换为 [name, count] 数组
   * 用于遍历展示课程占比列表
   */
  const courses = Object.entries(summary?.courses ?? {});

  return (
    <div className="content-grid">
      {/* ===== 【关键指标行】6 个核心统计指标 ===== */}
      <section className="metric-row">
        <Metric icon={<CalendarCheck />} label="连续学习天数" value={summary?.consecutiveDays ?? 0} />
        <Metric icon={<CheckCircle2 />} label="累计通过" value={summary?.approvedCount ?? 0} />
        <Metric icon={<XCircle />} label="累计打回" value={summary?.rejectedCount ?? 0} />
        <Metric icon={<ShieldCheck />} label="AI 通过率 %" value={summary?.aiApprovalRate ?? 0} />
        <Metric icon={<Target />} label="今日专注分钟" value={summary?.todayFocusMinutes ?? 0} />
        <Metric icon={<Timer />} label="今日专注次数" value={summary?.todayFocusSessions ?? 0} />
      </section>

      {/* ===== 【学习趋势图】近 7 天的学习时长柱状图 ===== */}
      <section className="panel wide">
        <div className="panel-title"><h2>学习时长趋势（近 7 天）</h2></div>
        <Suspense fallback={<div className="muted">加载图表中...</div>}>
          <TrendChart data={summary?.trend ?? []} />
        </Suspense>
      </section>

      {/* ===== 【课程占比】展示各课程的任务数量分布 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>课程占比</h2></div>
        <div className="status-stack">
          {courses.map(([name, count]) => (
            <div className="status-line" key={name}>
              <span>{name}</span>
              <strong>{count}</strong>
            </div>
          ))}
          {courses.length === 0 && <p className="muted">还没有任务数据。</p>}
        </div>
      </section>

      {/* ===== 【任务完成率】百分比数字 + 进度条 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>任务完成率</h2></div>
        <div className="status-stack">
          <div className="status-line"><span>完成率</span><strong>{summary?.completionRate ?? 0}%</strong></div>
          <div className="progress" style={{ marginTop: 8 }}>
            <span style={{ width: `${summary?.completionRate ?? 0}%` }} />
          </div>
        </div>
      </section>
    </div>
  );
}
