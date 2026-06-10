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
            {/* Luminous Ethereal：琥珀主线 + 向下淡出的渐变（design/stitch/soulous_4 趋势卡） */}
            <linearGradient id="minutes" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#FFBF00" stopOpacity={0.3} />
              <stop offset="100%" stopColor="#FFBF00" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="focusMinutes" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#845400" stopOpacity={0.22} />
              <stop offset="95%" stopColor="#845400" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="rgba(94,94,92,0.18)" />
          <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fill: '#454742', fontSize: 11, fontFamily: 'JetBrains Mono, ui-monospace, monospace' }} />
          <YAxis axisLine={false} tickLine={false} tick={{ fill: '#454742', fontSize: 11, fontFamily: 'JetBrains Mono, ui-monospace, monospace' }} />
          <Tooltip formatter={(value, name) => [value, name === 'minutes' ? '学习分钟' : '专注分钟']} contentStyle={{ background: 'rgba(255,255,255,0.85)', backdropFilter: 'blur(12px)', border: '1px solid rgba(255,255,255,0.6)', borderRadius: 12, fontSize: 12 }} />
          <Area type="monotone" dataKey="minutes" name="minutes" stroke="#FFBF00" fill="url(#minutes)" strokeWidth={2}
            style={{ filter: 'drop-shadow(0 0 8px rgba(255, 191, 0, 0.4))' }} />
          {hasFocus && (
            <Area type="monotone" dataKey="focusMinutes" name="focusMinutes" stroke="#845400" fill="url(#focusMinutes)" strokeWidth={2} strokeDasharray="4 2" />
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
