/**
 * 【课程分布饼图组件】
 * 使用 recharts 的 PieChart 展示用户任务按课程名的分布情况。
 * 空数据时显示引导文案。支持自定义颜色盘和图例展示。
 */
import React from 'react';
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';

/** 【颜色盘：8 色暖色调配色方案，与项目整体设计风格一致】 */
const PALETTE = ['#c98a3e', '#7a9d6b', '#c97a5a', '#8a6db8', '#c9a83e', '#5c8a7a', '#b86b8a', '#3a342d'];

/**
 * 【课程饼图组件】
 * 将课程名-任务数的 Record 转换为饼图数据，过滤掉数量为 0 的课程。
 * 使用环形饼图（innerRadius + outerRadius）展示，带自定义 Tooltip 和图例。
 *
 * @param courses - 课程名到任务数量的映射，如 { "数学": 5, "英语": 3 }
 */
export function CoursePie({ courses }: { courses: Record<string, number> }) {
  const data = Object.entries(courses ?? {})
    .filter(([, count]) => count > 0)
    .map(([name, count]) => ({ name: name || '未分类', value: count }));

  if (data.length === 0) {
    return <p className="muted">还没有任务数据，建一个任务后这里会出现课程分布。</p>;
  }

  return (
    <div className="chart" style={{ height: 240 }}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={data}
            dataKey="value"
            nameKey="name"
            cx="50%"
            cy="50%"
            innerRadius={50}
            outerRadius={90}
            paddingAngle={2}
            stroke="#fff"
            strokeWidth={2}
            isAnimationActive={false}
          >
            {data.map((entry, index) => (
              <Cell key={entry.name} fill={PALETTE[index % PALETTE.length]} />
            ))}
          </Pie>
          <Tooltip formatter={(value: number, name: string) => [`${value} 个任务`, name]} />
        </PieChart>
      </ResponsiveContainer>
      <div className="chart-legend">
        {data.map((entry, index) => (
          <span key={entry.name} className="legend-item" style={{ color: PALETTE[index % PALETTE.length] }}>
            ● {entry.name} · {entry.value}
          </span>
        ))}
      </div>
    </div>
  );
}

export default CoursePie;
