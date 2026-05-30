import { describe, it, expect } from 'vitest';
import { buildBlocks } from '../components/TimetableGrid';
import type { CourseEntry } from '../types';

let id = 1;
function c(p: Partial<CourseEntry>): CourseEntry {
  return { id: id++, courseName: 'x', dayOfWeek: 1, ...p } as CourseEntry;
}

describe('buildBlocks 合并连堂课', () => {
  it('同课被拆成 3节/4节 两行 → 合并成 3-4 一格', () => {
    const blocks = buildBlocks([
      c({ courseName: '大学英语A2', dayOfWeek: 1, startSection: 3, endSection: 3, teacher: '舒敏', location: '公共124' }),
      c({ courseName: '大学英语A2', dayOfWeek: 1, startSection: 4, endSection: 4, teacher: '舒敏', location: '公共124' })
    ]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ day: 1, start: 3, end: 4 });
  });

  it('教师/地点略有差异也能合并（只看课名）', () => {
    const blocks = buildBlocks([
      c({ courseName: '大学英语A2', dayOfWeek: 1, startSection: 3, endSection: 3, teacher: '舒敏讲师', location: '公共楼(公共124)' }),
      c({ courseName: '大学英语A2', dayOfWeek: 1, startSection: 4, endSection: 4, teacher: '舒敏', location: null })
    ]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ start: 3, end: 4 });
  });

  it('重叠/嵌套写法 3-4 与 4-4 也合并', () => {
    const blocks = buildBlocks([
      c({ courseName: '大学物理C1', dayOfWeek: 4, startSection: 3, endSection: 4 }),
      c({ courseName: '大学物理C1', dayOfWeek: 4, startSection: 4, endSection: 4 })
    ]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ start: 3, end: 4 });
  });

  it('不同课相邻不合并', () => {
    const blocks = buildBlocks([
      c({ courseName: '高等数学A2', dayOfWeek: 1, startSection: 1, endSection: 2 }),
      c({ courseName: '大学英语A2', dayOfWeek: 1, startSection: 3, endSection: 4 })
    ]);
    expect(blocks).toHaveLength(2);
  });

  it('同格不同课冲突 → 堆进一个块（不被合并掉）', () => {
    const blocks = buildBlocks([
      c({ courseName: '大学英语A2', dayOfWeek: 2, startSection: 3, endSection: 4 }),
      c({ courseName: '离散数学', dayOfWeek: 2, startSection: 3, endSection: 4 })
    ]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0].items).toHaveLength(2);
  });

  it('跨天同名不合并', () => {
    const blocks = buildBlocks([
      c({ courseName: '高等数学A2', dayOfWeek: 1, startSection: 9, endSection: 10 }),
      c({ courseName: '高等数学A2', dayOfWeek: 5, startSection: 7, endSection: 8 })
    ]);
    expect(blocks).toHaveLength(2);
  });
});
