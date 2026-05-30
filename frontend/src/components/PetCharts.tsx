/**
 * 【宠物经验值趋势图组件】
 * 使用 recharts 的 BarChart 展示宠物近 N 天的经验值获取趋势。
 * 采用渐变色柱状图，空数据时显示引导文案。
 */
import React, { useMemo } from 'react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { ExpLog } from '../types';

/**
 * 【日期键提取函数】
 * 将 ISO 日期字符串转换为 'YYYY-MM-DD' 格式的日期键，
 * 用于按天聚合经验值数据。解析失败时截取前 10 个字符作为兜底。
 *
 * @param value - ISO 日期字符串
 * @returns 'YYYY-MM-DD' 格式的日期键
 */
function dayKey(value: string): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value.slice(0, 10);
  return date.toISOString().slice(0, 10);
}

/**
 * 【生成最近 N 天的日期键数组】
 * 从今天往前推 N 天，生成按时间正序排列的日期键数组，
 * 作为图表 X 轴的数据骨架，确保无数据的日期也显示为 0。
 *
 * @param n - 天数
 * @returns 日期键数组（YYYY-MM-DD 格式）
 */
function lastNDays(n: number): string[] {
  const out: string[] = [];
  const today = new Date();
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(today.getDate() - i);
    out.push(d.toISOString().slice(0, 10));
  }
  return out;
}

/**
 * 【经验值趋势柱状图组件】
 * 将经验值日志按天聚合，展示近 N 天每天获得的经验值总量。
 * 使用 useMemo 缓存计算结果，仅在 logs 或 days 变化时重新计算。
 *
 * @param logs - 经验值变动日志数组
 * @ days - 展示的天数，默认 7 天
 */
export function ExpTrendChart({ logs, days = 7 }: { logs: ExpLog[]; days?: number }) {
  const data = useMemo(() => {
    const buckets = new Map<string, number>();
    for (const day of lastNDays(days)) buckets.set(day, 0);
    for (const log of logs) {
      const key = dayKey(log.createdAt);
      if (buckets.has(key)) buckets.set(key, (buckets.get(key) ?? 0) + Math.max(0, log.expAmount ?? 0));
    }
    return Array.from(buckets.entries()).map(([date, exp]) => ({
      date: date.slice(5),
      exp
    }));
  }, [logs, days]);

  const hasData = data.some(d => d.exp > 0);
  if (!hasData) {
    return <p className="muted">最近 {days} 天还没有获得经验，完成一个任务就会出现在这里。</p>;
  }

  return (
    <div className="chart">
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data}>
          <defs>
            <linearGradient id="expGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#c98a3e" stopOpacity={0.95} />
              <stop offset="100%" stopColor="#8a5a26" stopOpacity={0.85} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#efe7d6" />
          <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fill: '#7a7368', fontSize: 11, fontFamily: 'JetBrains Mono, ui-monospace, monospace' }} />
          <YAxis axisLine={false} tickLine={false} allowDecimals={false} tick={{ fill: '#7a7368', fontSize: 11, fontFamily: 'JetBrains Mono, ui-monospace, monospace' }} />
          <Tooltip formatter={(value: number) => [`${value} exp`, '获得经验']} contentStyle={{ background: '#fffdf7', border: '1px solid #e5dccb', borderRadius: 6, fontSize: 12 }} />
          <Bar dataKey="exp" fill="url(#expGrad)" radius={[6, 6, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export default ExpTrendChart;
