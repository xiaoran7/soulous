import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 【Mock 接口层】只需课表/考试/成绩三个读接口返回多学期数据，
 * 其余接口给空实现避免组件引用报错。
 */
vi.mock('../api', () => ({
  api: {
    timetable: vi.fn(),
    exams: vi.fn(),
    grades: vi.fn(),
    syncTimetable: vi.fn(),
    createCourse: vi.fn(),
    deleteCourse: vi.fn(),
    clearTimetable: vi.fn()
  }
}));

import { api } from '../api';
import { TimetablePage, resetTimetableCache } from '../pages/TimetablePage';

const COURSES = [
  { id: 1, courseName: '高等数学', dayOfWeek: 1, startSection: 1, endSection: 2, weeks: '1-16', weekParity: 'ALL', semester: '2025-2026-1' },
  { id: 2, courseName: '大学物理', dayOfWeek: 2, startSection: 3, endSection: 4, weeks: '1-16', weekParity: 'ALL', semester: '2024-2025-2' }
];
const GRADES = [
  { id: 1, courseName: '思想道德', semester: '2024-2025-1', score: '90', credit: '3', gpa: '4.0' }
];

/**
 * 【TimetablePage 课表分学期测试】
 * 验证「课表固定本学期」改造：默认落到最新学期、没有「全部学期」混排、切学期可单独查看旧学期。
 */
describe('TimetablePage 学期固定本学期', () => {
  beforeEach(() => {
    resetTimetableCache();
    vi.mocked(api.timetable).mockReset().mockResolvedValue(COURSES as never);
    vi.mocked(api.exams).mockReset().mockResolvedValue([] as never);
    vi.mocked(api.grades).mockReset().mockResolvedValue(GRADES as never);
  });

  it('默认显示最新学期、无「全部学期」选项、不混排旧学期课程', async () => {
    render(<TimetablePage onRefresh={() => {}} />);

    // 数据加载后标题出现课程数：仅最新学期(2025-2026-1)的 1 节，而非混排的 2 节
    await waitFor(() => expect(screen.getByRole('heading', { name: /我的课表/ })).toHaveTextContent('1 节'));

    // 学期下拉默认选中最新学期，且不再有「全部学期」选项
    const semSelect = screen.getByTitle('切换学期（课表 / 考试 / 成绩同步切换）') as HTMLSelectElement;
    expect(semSelect.value).toBe('2025-2026-1');
    expect(within(semSelect).queryByRole('option', { name: '全部学期' })).toBeNull();
  });

  it('切到旧学期可单独查看（该学期无课 → 0 节，证明未混排）', async () => {
    render(<TimetablePage onRefresh={() => {}} />);
    await waitFor(() => expect(screen.getByRole('heading', { name: /我的课表/ })).toHaveTextContent('1 节'));

    const semSelect = screen.getByTitle('切换学期（课表 / 考试 / 成绩同步切换）');
    await userEvent.selectOptions(semSelect, '2024-2025-1'); // 仅有成绩、无课表的旧学期

    await waitFor(() => expect(screen.getByRole('heading', { name: /我的课表/ })).toHaveTextContent('0 节'));
  });
});
