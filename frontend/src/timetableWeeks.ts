/**
 * 【课表周次 / 日期工具】
 * 教务课表里每节课带 `weeks`（开课周次原文，如 "1-12"、"2-6,8-11,13-15"）和 `weekParity`（单/双/每周）。
 * 配合一个"开学第一周的周一"锚点（weekStart），就能：
 *  - 判断某门课在第 N 周是否上（区间 + 单双周）
 *  - 算出整学期最大周数（周次选择器的上界）
 *  - 把"第 N 周 + 周几"换算成真实日期（几月几号）
 *  - 根据今天算出当前是第几周
 *
 * 锚点 weekStart 教务导出文件里不一定有，故由前端让用户设置一次并按学期存 localStorage。
 */
import type { CourseEntry } from './types';

/** 安全的整学期最大周数上限，防止脏数据把选择器撑爆 */
const MAX_WEEK_CAP = 30;

/**
 * 【把 weeks 原文解析成"判断第 w 周是否在区间内"的函数】
 * 支持 "1-12" / "2-6,8-11,13-15" / "15" / 含"周"后缀 / 中英文逗号与连接号。
 * 解析不出任何区间（或为空）时返回恒真（视为每周都上）。
 */
export function weekRangeMatcher(weeks?: string | null): (w: number) => boolean {
  if (!weeks) return () => true;
  const parts = weeks.split(/[，,、;；]/).map((s) => s.trim()).filter(Boolean);
  const ranges: Array<[number, number]> = [];
  for (const p of parts) {
    const m = p.match(/(\d+)\s*[-–~～到至]\s*(\d+)/);
    if (m) {
      ranges.push([Number(m[1]), Number(m[2])]);
    } else {
      const n = p.match(/\d+/);
      if (n) ranges.push([Number(n[0]), Number(n[0])]);
    }
  }
  if (ranges.length === 0) return () => true;
  return (w: number) => ranges.some(([a, b]) => w >= Math.min(a, b) && w <= Math.max(a, b));
}

/** 【某门课在第 week 周是否上课：先看周次区间，再看单双周】 */
export function isCourseInWeek(c: CourseEntry, week: number): boolean {
  if (!weekRangeMatcher(c.weeks)(week)) return false;
  if (c.weekParity === 'ODD') return week % 2 === 1;
  if (c.weekParity === 'EVEN') return week % 2 === 0;
  return true;
}

/** 【整学期最大周数：取所有课程 weeks 区间上界的最大值；无数据时回退 20】 */
export function maxWeekOf(courses: CourseEntry[]): number {
  let max = 0;
  for (const c of courses) {
    if (!c.weeks) continue;
    const nums = c.weeks.match(/\d+/g);
    if (nums) for (const n of nums) max = Math.max(max, Number(n));
  }
  if (max <= 0) return 20;
  return Math.min(MAX_WEEK_CAP, max);
}

/** 【把任意日期归一为当周周一的本地零点】 */
export function mondayOf(d: Date): Date {
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const js = x.getDay() === 0 ? 7 : x.getDay(); // 周一=1 … 周日=7
  x.setDate(x.getDate() - (js - 1));
  return x;
}

/** 【yyyy-mm-dd 字符串 → 本地零点 Date；非法返回 null】 */
export function parseISODate(s?: string | null): Date | null {
  if (!s) return null;
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return null;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
}

/** 【Date → yyyy-mm-dd（本地）】 */
export function toISODate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/** 【在 base 上加 n 天，返回新 Date】 */
export function addDays(base: Date, n: number): Date {
  const x = new Date(base);
  x.setDate(x.getDate() + n);
  return x;
}

/** 【第 week 周、第 dayIdx 天（0=周一…6=周日）对应的真实日期】 */
export function dateForWeekday(weekStart: Date, week: number, dayIdx: number): Date {
  return addDays(weekStart, (week - 1) * 7 + dayIdx);
}

/** 【今天是第几周（相对 weekStart）；不足第 1 周按 1 计】 */
export function currentWeekOf(weekStart: Date, today: Date = new Date()): number {
  const t = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const diffDays = Math.round((t.getTime() - weekStart.getTime()) / 86_400_000);
  return Math.max(1, Math.floor(diffDays / 7) + 1);
}

/** 【格式化成 "M/D"】 */
export function fmtMonthDay(d: Date): string {
  return `${d.getMonth() + 1}/${d.getDate()}`;
}
