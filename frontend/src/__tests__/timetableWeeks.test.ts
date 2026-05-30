import { describe, it, expect } from 'vitest';
import {
  weekRangeMatcher, isCourseInWeek, maxWeekOf,
  mondayOf, parseISODate, toISODate, dateForWeekday, currentWeekOf, fmtMonthDay
} from '../timetableWeeks';
import type { CourseEntry } from '../types';

function course(p: Partial<CourseEntry>): CourseEntry {
  return { id: 1, courseName: 'x', dayOfWeek: 1, ...p } as CourseEntry;
}

describe('weekRangeMatcher', () => {
  it('解析连续区间', () => {
    const m = weekRangeMatcher('1-12');
    expect(m(1)).toBe(true);
    expect(m(12)).toBe(true);
    expect(m(13)).toBe(false);
  });
  it('解析多段+单点', () => {
    const m = weekRangeMatcher('2-6,8-11,13-15');
    expect(m(2)).toBe(true);
    expect(m(7)).toBe(false);
    expect(m(8)).toBe(true);
    expect(m(12)).toBe(false);
    expect(m(15)).toBe(true);
  });
  it('忽略"周"后缀与中文逗号', () => {
    const m = weekRangeMatcher('1-8，10-16周');
    expect(m(9)).toBe(false);
    expect(m(16)).toBe(true);
  });
  it('空值视为每周', () => {
    expect(weekRangeMatcher(null)(99)).toBe(true);
  });
});

describe('isCourseInWeek', () => {
  it('叠加单双周', () => {
    const odd = course({ weeks: '1-16', weekParity: 'ODD' });
    expect(isCourseInWeek(odd, 1)).toBe(true);
    expect(isCourseInWeek(odd, 2)).toBe(false);
    const even = course({ weeks: '1-16', weekParity: 'EVEN' });
    expect(isCourseInWeek(even, 2)).toBe(true);
    expect(isCourseInWeek(even, 3)).toBe(false);
  });
  it('区间外不上', () => {
    expect(isCourseInWeek(course({ weeks: '1-12' }), 13)).toBe(false);
  });
});

describe('maxWeekOf', () => {
  it('取所有课程周次上界最大值', () => {
    expect(maxWeekOf([course({ weeks: '1-12' }), course({ weeks: '2-6,8-11,13-15' })])).toBe(15);
  });
  it('无周次信息回退 20', () => {
    expect(maxWeekOf([course({ weeks: null })])).toBe(20);
  });
});

describe('日期换算', () => {
  it('mondayOf 把周日也归到本周一', () => {
    // 2026-05-31 是周日
    expect(toISODate(mondayOf(new Date(2026, 4, 31)))).toBe('2026-05-25');
    // 2026-05-25 是周一
    expect(toISODate(mondayOf(new Date(2026, 4, 25)))).toBe('2026-05-25');
  });
  it('dateForWeekday 按周次+周几推日期', () => {
    const start = parseISODate('2026-02-23')!; // 第1周周一
    expect(toISODate(dateForWeekday(start, 1, 0))).toBe('2026-02-23'); // 第1周周一
    expect(toISODate(dateForWeekday(start, 1, 6))).toBe('2026-03-01'); // 第1周周日
    expect(toISODate(dateForWeekday(start, 3, 0))).toBe('2026-03-09'); // 第3周周一
  });
  it('currentWeekOf 计算今天第几周', () => {
    const start = parseISODate('2026-02-23')!;
    expect(currentWeekOf(start, new Date(2026, 1, 23))).toBe(1);
    expect(currentWeekOf(start, new Date(2026, 1, 24))).toBe(1);
    expect(currentWeekOf(start, new Date(2026, 2, 2))).toBe(2); // +7 天
    expect(currentWeekOf(start, new Date(2026, 0, 1))).toBe(1); // 开学前夹到第1周
  });
  it('fmtMonthDay', () => {
    expect(fmtMonthDay(new Date(2026, 2, 9))).toBe('3/9');
  });
});
