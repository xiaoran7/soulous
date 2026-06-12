/**
 * 【考试安排 / 成绩 面板】ExamPanel & GradePanel
 * 课表页内的两个子视图，纯展示组件——数据、加载态、当前学期都由父组件 TimetablePage 传入
 * （三视图共享同一份缓存与学期选择，切 tab/切学期零顿挫、且课表/考试/成绩一致对应）。
 * 两者都「分学期」分组渲染；成绩面板还按学期给出小结（门数 / 已修学分 / 加权平均绩点）。
 */
import React, { useMemo } from 'react';
import { CalendarClock, GraduationCap, MapPin, Armchair, User as UserIcon } from 'lucide-react';
import type { ExamEntry, GradeEntry } from '../types';

/** 按学期把记录分组，返回 [学期, 记录[]][]，学期按字符串倒序（最近的在前；空学期归到末尾） */
function groupBySemester<T extends { semester?: string | null }>(rows: T[]): Array<[string, T[]]> {
  const map = new Map<string, T[]>();
  for (const r of rows) {
    const key = r.semester || '未标注学期';
    const arr = map.get(key);
    if (arr) arr.push(r);
    else map.set(key, [r]);
  }
  return Array.from(map.entries()).sort((a, b) => {
    if (a[0] === '未标注学期') return 1;
    if (b[0] === '未标注学期') return -1;
    return b[0].localeCompare(a[0]);
  });
}

/* ============================ 考试安排 ============================ */

export function ExamPanel({ exams, loading, selectedSemester }: {
  exams: ExamEntry[];
  loading: boolean;
  selectedSemester: string;
}) {
  const groups = useMemo(() => groupBySemester(exams), [exams]);

  // 有缓存时切 tab 不会进 loading；仅真正首次加载且无数据时才显示「加载中」
  if (loading && exams.length === 0) {
    return <div className="muted small" style={{ padding: '8px 0' }}>加载中…</div>;
  }
  if (exams.length === 0) {
    return (
      <div className="muted small" style={{ padding: '8px 0', lineHeight: 1.6 }}>
        {selectedSemester
          ? `${selectedSemester} 学期暂无考试安排（考试未公布时教务系统也可能返回为空）。`
          : '还没有考试安排。在下方「同步教务系统课表」输入账号密码同步，会一并拉取本学期的考试安排。'}
      </div>
    );
  }

  return (
    <div>
      {groups.map(([sem, rows]) => (
        <section key={sem} style={{ marginBottom: 18 }}>
          <div className="muted small" style={{ fontWeight: 600, margin: '4px 0 8px' }}>{sem}（{rows.length} 场）</div>
          <div style={{ display: 'grid', gap: 8 }}>
            {rows.map((e) => <ExamCard key={e.id} exam={e} />)}
          </div>
        </section>
      ))}
    </div>
  );
}

function ExamCard({ exam }: { exam: ExamEntry }) {
  const place = [exam.campus, exam.room].filter(Boolean).join(' · ');
  return (
    <div style={{
      background: 'rgba(255, 255, 255, 0.92)',
      border: '1px solid rgba(255, 255, 255, 0.6)',
      borderRadius: 12, padding: '12px 14px',
      boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)'
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
        <strong style={{ fontSize: 14.5 }}>{exam.courseName || '未知课程'}</strong>
        {exam.session && <span className="chip small">{exam.session}</span>}
      </div>
      {exam.examTime && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 6, fontSize: 13, color: 'var(--amber-deep)' }}>
          <CalendarClock size={14} /> {exam.examTime}
        </div>
      )}
      <div className="muted small" style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 14px', marginTop: 6 }}>
        {place && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><MapPin size={12} /> {place}</span>}
        {exam.seatNo && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><Armchair size={12} /> 座位 {exam.seatNo}</span>}
        {exam.teacher && <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><UserIcon size={12} /> {exam.teacher}</span>}
      </div>
      {exam.remark && <div className="muted small" style={{ marginTop: 4 }}>备注：{exam.remark}</div>}
    </div>
  );
}

/* ============================ 成绩 ============================ */

/** 解析数值（学分/绩点），非数值返回 null */
function num(s?: string | null): number | null {
  if (!s) return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

/** 是否不及格：数值 <60 或含「不及格/不合格/缺考」等字样 */
function isFail(score?: string | null): boolean {
  if (!score) return false;
  const n = Number(score);
  if (Number.isFinite(n)) return n < 60;
  return /不及格|不合格|缺考|未通过|旷考/.test(score);
}

/** 一个学期的成绩小结：门数、已修学分（不及格不计）、加权平均绩点 */
function semesterSummary(rows: GradeEntry[]): { count: number; credits: number; gpa: number | null } {
  let credits = 0;
  let weighted = 0;
  let gpaCreditSum = 0;
  for (const g of rows) {
    const c = num(g.credit);
    const p = num(g.gpa);
    if (c != null && !isFail(g.score)) credits += c;
    if (c != null && p != null) { weighted += c * p; gpaCreditSum += c; }
  }
  return {
    count: rows.length,
    credits: Math.round(credits * 10) / 10,
    gpa: gpaCreditSum > 0 ? Math.round((weighted / gpaCreditSum) * 100) / 100 : null
  };
}

export function GradePanel({ grades, loading, selectedSemester }: {
  grades: GradeEntry[];
  loading: boolean;
  selectedSemester: string;
}) {
  const groups = useMemo(() => groupBySemester(grades), [grades]);

  if (loading && grades.length === 0) {
    return <div className="muted small" style={{ padding: '8px 0' }}>加载中…</div>;
  }
  if (grades.length === 0) {
    return (
      <div className="muted small" style={{ padding: '8px 0', lineHeight: 1.6 }}>
        {selectedSemester
          ? `${selectedSemester} 学期暂无成绩。`
          : '还没有成绩。在下方「同步教务系统课表」输入账号密码同步，会一并拉取你各学期的课程成绩。'}
      </div>
    );
  }

  return (
    <div>
      {groups.map(([sem, rows]) => {
        const s = semesterSummary(rows);
        return (
          <section key={sem} style={{ marginBottom: 18 }}>
            {/* 学期小结玻璃条（design/stitch/soulous_6 Semester Summary Bar）：门数 / 已修学分 / 加权绩点 */}
            <div className="grade-sem-bar glass-card">
              <div className="grade-sem-name">
                <GraduationCap size={18} />
                <span>{sem}</span>
              </div>
              <div className="grade-sem-stats">
                <div><span>总门数</span><strong>{s.count}</strong></div>
                <div><span>已修学分</span><strong>{s.credits}</strong></div>
                {s.gpa != null && <div className="grade-sem-gpa"><span>平均绩点</span><strong>{s.gpa}</strong></div>}
              </div>
            </div>
            <div style={{ display: 'grid', gap: 6 }}>
              {rows.map((g) => <GradeRow key={g.id} grade={g} />)}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function GradeRow({ grade }: { grade: GradeEntry }) {
  const fail = isFail(grade.score);
  const tags = [grade.courseAttr, grade.examNature].filter((t) => t && t !== '初修');
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      background: 'rgba(255, 255, 255, 0.92)',
      border: '1px solid rgba(255, 255, 255, 0.6)',
      borderRadius: 12, padding: '9px 12px',
      boxShadow: '0 2px 8px rgba(0, 0, 0, 0.04)'
    }}>
      <GraduationCap size={15} style={{ color: 'var(--ink-4)', flexShrink: 0 }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 13.5, fontWeight: 500 }}>{grade.courseName || '未知课程'}</span>
          {tags.map((t) => <span key={t} className="chip small">{t}</span>)}
        </div>
        <div className="muted small" style={{ marginTop: 2 }}>
          {[grade.credit ? `${grade.credit} 学分` : '', grade.gpa ? `绩点 ${grade.gpa}` : '', grade.assessMethod]
            .filter(Boolean).join(' · ')}
        </div>
      </div>
      {/* 分数：及格用琥珀深色突出（design/stitch/soulous_6 成绩列），不及格保持警示红 */}
      <span style={{
        fontSize: 17, fontWeight: 700, flexShrink: 0,
        color: fail ? 'var(--danger)' : '#845400'
      }}>
        {grade.score || '—'}
      </span>
    </div>
  );
}
