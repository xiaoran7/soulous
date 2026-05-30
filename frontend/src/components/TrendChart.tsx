/**
 * 【学习趋势面积图组件】
 * 使用 recharts 的 AreaChart 展示每日学习分钟数趋势。
 * 当存在专注计时数据时，同时叠加展示专注分钟数的虚线曲线，
 * 帮助用户对比学习打卡和专注计时两种模式的数据。
 */
import React from 'react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

/**
 * 【学习趋势图组件】
 * 展示每日学习时间趋势，支持双曲线：
 * - 学习分钟（实线，深色）：来自任务提交记录
 * - 专注分钟（虚线，金色）：来自专注计时器记录
 *
 * @param data - 按日期排列的学习数据数组，每项包含 date、minutes 和可选的 focusMinutes
 */
export function TrendChart({ data }: { data: { date: string; minutes: number; focusMinutes?: number }[] }) {
  /** 【检测是否存在专注数据，有则显示第二条曲线和图例】 */
  const hasFocus = data.some(d => (d.focusMinutes ?? 0) > 0);
  return (
    <div className="chart">
      <ResponsiveContainer width="100%" height={220}>
        <AreaChart data={data}>
          <defs>
            <linearGradient id="minutes" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#1c1916" stopOpacity={0.18} />
              <stop offset="95%" stopColor="#1c1916" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="focusMinutes" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#c98a3e" stopOpacity={0.28} />
              <stop offset="95%" stopColor="#c98a3e" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#efe7d6" />
          <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fill: '#7a7368', fontSize: 11, fontFamily: 'JetBrains Mono, ui-monospace, monospace' }} />
          <YAxis axisLine={false} tickLine={false} tick={{ fill: '#7a7368', fontSize: 11, fontFamily: 'JetBrains Mono, ui-monospace, monospace' }} />
          <Tooltip formatter={(value, name) => [value, name === 'minutes' ? '学习分钟' : '专注分钟']} contentStyle={{ background: '#fffdf7', border: '1px solid #e5dccb', borderRadius: 6, fontSize: 12 }} />
          <Area type="monotone" dataKey="minutes" name="minutes" stroke="#1c1916" fill="url(#minutes)" strokeWidth={2} />
          {hasFocus && (
            <Area type="monotone" dataKey="focusMinutes" name="focusMinutes" stroke="#c98a3e" fill="url(#focusMinutes)" strokeWidth={2} strokeDasharray="4 2" />
          )}
        </AreaChart>
      </ResponsiveContainer>
      {hasFocus && (
        <div className="chart-legend">
          <span className="legend-item study">学习打卡</span>
          <span className="legend-item focus">专注计时</span>
        </div>
      )}
    </div>
  );
}

export default TrendChart;
