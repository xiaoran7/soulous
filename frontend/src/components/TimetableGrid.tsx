/**
 * 【课表周网格】TimetableGrid
 * 标准课程表布局：行 = 节次（小节），列 = 周一~周日，课程块按 startSection~endSection 跨行，
 * 同名课程固定同色（15 色调色盘）。
 *
 * 信息密度：网格块只显示课名 + 精简地点（保持清爽），点击块弹出详情卡查看完整信息
 * （教师、完整地点、周次、节次、时间、单双周），删除按钮也在详情卡里，避免误点。
 *
 * 数据驱动：节次行数（maxSection）由导入数据推出，不写死；教务系统常把一门连堂课按小节
 * 拆成多行，这里会把"同一天、同课、节次首尾相接"的块合并成一格（修复"周一英语被拆两格"）。
 *
 * 手动维护：传入 onAdd / semester 时，网格上方出现"添加课程"按钮，可手工补一节课（临时调课/活动占用）。
 *
 * 健壮性：当课程没有任何节次信息（只有时间或都没有）时，自动回退为"列内堆叠卡片"布局。
 */
import React, { useState } from 'react';
import { MapPin, Plus, Trash2, User, X } from 'lucide-react';
import type { CourseCreateInput, CourseEntry } from '../types';
import { dateForWeekday, fmtMonthDay, isCourseInWeek, toISODate } from '../timetableWeeks';
import { ModalShell } from './Modal';

const DAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/** 每小节行高（px） */
const ROW_H = 34;

/** 15 色柔和调色盘（同名课程取同色） */
const PALETTE = [
  '#e8f0fe', '#fde7e9', '#e6f4ea', '#fff4e5', '#f3e8fd',
  '#e0f7fa', '#fce4ec', '#f1f8e9', '#fff8e1', '#e8eaf6',
  '#e0f2f1', '#fbe9e7', '#ede7f6', '#f9fbe7', '#e3f2fd'
];
const PALETTE_FG = [
  '#1a56db', '#c0392b', '#1e7e4f', '#b9770e', '#7e3ff2',
  '#00838f', '#ad1457', '#558b2f', '#a37800', '#3949ab',
  '#00695c', '#bf360c', '#5e35b1', '#9e9d24', '#1565c0'
];

function colorIndex(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % PALETTE.length;
}

function slotText(c: CourseEntry): string {
  if (c.startTime && c.endTime) return `${c.startTime}-${c.endTime}`;
  if (c.startTime) return c.startTime;
  if (c.startSection && c.endSection) return `第${c.startSection}-${c.endSection}节`;
  if (c.startSection) return `第${c.startSection}节`;
  return '';
}

/** 网格里地点只取末段（房间号），完整地点在详情卡看。如"虚拟教学楼网络虚拟教室001" → "虚拟教室001" */
function shortLocation(loc?: string | null): string {
  if (!loc) return '';
  const s = loc.trim();
  // 末尾房间号（数字/字母组合，可带"室/厅/号"），连同紧邻的少量中文一起取
  const m = s.match(/[一-龥]{0,4}[0-9A-Za-z]{1,6}(室|厅|号)?$/);
  if (m && m[0].length >= 2 && m[0].length < s.length) return m[0];
  return s.length > 8 ? s.slice(-8) : s;
}

const PARITY_LABEL: Record<string, string> = { ODD: '单周', EVEN: '双周', ALL: '每周' };

export type Block = { day: number; start: number; end: number; items: CourseEntry[] };

/**
 * 两块是否同一门课：只比课程名。
 * 教务把连堂课按小节拆成多行时，两半的教师/地点偶尔解析得略有差异（如某半地点为空），
 * 若苛求三者全等会漏合并，故同一天、节次首尾相接、课名相同即视为同一门课。
 */
function sameCourse(a: CourseEntry, b: CourseEntry): boolean {
  return a.courseName.trim() === b.courseName.trim();
}

/**
 * 【把"带节次"的课程排成网格块】（纯函数，便于单测）
 * 1) 按 (day, startSection) 聚合：同一格里的冲突课（如单双周/不同课）堆进一个块；
 * 2) 合并同一天、同名、节次首尾相接或重叠的单门课块——修复教务把连堂课按小节拆成多行导致的"一门课两格"。
 */
export function buildBlocks(sectioned: CourseEntry[]): Block[] {
  const blockMap = new Map<string, Block>();
  for (const c of sectioned) {
    const day = c.dayOfWeek;
    if (day < 1 || day > 7) continue;
    const start = c.startSection!;
    const end = c.endSection ?? start;
    const key = `${day}-${start}`;
    const b = blockMap.get(key);
    if (b) {
      b.items.push(c);
      b.end = Math.max(b.end, end);
    } else {
      blockMap.set(key, { day, start, end, items: [c] });
    }
  }

  const byDay = new Map<number, Block[]>();
  for (const b of blockMap.values()) {
    const arr = byDay.get(b.day) ?? [];
    arr.push(b);
    byDay.set(b.day, arr);
  }
  const blocks: Block[] = [];
  for (const arr of byDay.values()) {
    arr.sort((a, b) => a.start - b.start);
    for (const b of arr) {
      const prev = blocks[blocks.length - 1];
      if (
        prev && prev.day === b.day &&
        // 首尾相接(end+1==start)或重叠/嵌套(start<=end+1)都合并，兼容教务把连堂课拆成多行/多段的各种写法
        b.start <= prev.end + 1 &&
        prev.items.length === 1 && b.items.length === 1 &&
        sameCourse(prev.items[0], b.items[0])
      ) {
        prev.end = Math.max(prev.end, b.end);
      } else {
        blocks.push({ ...b, items: [...b.items] });
      }
    }
  }
  return blocks;
}

export function TimetableGrid({ courses, onDelete, onAdd, semester, selectedWeek, weekStart, currentWeek }: {
  courses: CourseEntry[];
  onDelete?: (id: number) => void;
  /** 提供时显示"添加课程"入口；返回 Promise 以便表单等待落库 */
  onAdd?: (input: CourseCreateInput) => Promise<void> | void;
  /** 新增课程默认归属的学期（来自当前选中学期，可空） */
  semester?: string | null;
  /** 当前查看的周次（1-based）；提供时按周次/单双周过滤课程 */
  selectedWeek?: number;
  /** 第 1 周周一锚点；提供时表头显示每天日期、并高亮今天 */
  weekStart?: Date | null;
  /** 今天所在周次；与 selectedWeek 相等时才在表头高亮今天 */
  currentWeek?: number | null;
}) {
  /** 点击课程块后展示详情的那一组课（同格冲突课可能多门） */
  const [detail, setDetail] = useState<CourseEntry[] | null>(null);
  const [adding, setAdding] = useState(false);

  // 按选中周次过滤（无周次信息的课视为每周都上，恒显示）
  const visible = typeof selectedWeek === 'number'
    ? courses.filter((c) => isCourseInWeek(c, selectedWeek))
    : courses;

  // 今天对应的列索引（0=周一…6=周日）：仅当查看的就是本周、且设置了开学日期时才高亮
  const todayISO = toISODate(new Date());
  const todayIdx = (weekStart && typeof selectedWeek === 'number' && selectedWeek === currentWeek)
    ? Array.from({ length: 7 }, (_, i) => toISODate(dateForWeekday(weekStart, selectedWeek, i))).indexOf(todayISO)
    : -1;

  const sectioned = visible.filter((c) => typeof c.startSection === 'number' && c.startSection! >= 1);

  const addBar = onAdd && (
    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
      <button className="secondary-button" onClick={() => setAdding(true)}>
        <Plus size={14} /> 添加课程
      </button>
    </div>
  );

  // 没有任何节次信息 → 回退到列内堆叠布局
  if (sectioned.length === 0) {
    return (
      <>
        {addBar}
        <StackedFallback courses={visible} onOpen={setDetail} />
        {detail && <DetailModal items={detail} onClose={() => setDetail(null)} onDelete={onDelete} />}
        {adding && <AddCourseModal semester={semester} onClose={() => setAdding(false)} onAdd={onAdd!} />}
      </>
    );
  }

  // 节次行数完全由数据推出（不写死成 10）
  const maxSection = Math.max(1, ...sectioned.map((c) => c.endSection ?? c.startSection ?? 1));

  // 每个小节行的开始/结束时间（从课程反推，用于左侧时间列标注）
  const startTimeAt: Record<number, string> = {};
  const endTimeAt: Record<number, string> = {};
  for (const c of sectioned) {
    if (c.startSection && c.startTime) startTimeAt[c.startSection] = c.startTime;
    if (c.endSection && c.endTime) endTimeAt[c.endSection] = c.endTime;
  }

  const blocks = buildBlocks(sectioned);

  return (
    <>
      {addBar}
      <div style={{ marginTop: 4, overflowX: 'auto' }}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: `56px repeat(7, minmax(64px, 1fr))`,
            gridTemplateRows: `${weekStart ? 40 : 30}px repeat(${maxSection}, ${ROW_H}px)`,
            gap: 3,
            minWidth: 560
          }}
        >
          <div className="muted small" style={{ gridColumn: 1, gridRow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11 }}>节次</div>

          {DAY_NAMES.map((d, i) => {
            const isToday = i === todayIdx;
            return (
              <div
                key={`h${i}`}
                className="muted small"
                style={{
                  gridColumn: i + 2, gridRow: 1,
                  display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', lineHeight: 1.15,
                  fontWeight: 600,
                  color: isToday ? 'var(--accent, #1a56db)' : undefined,
                  background: isToday ? 'color-mix(in srgb, var(--accent, #1a56db) 12%, transparent)' : undefined,
                  borderRadius: 4
                }}
              >
                <span>{d}</span>
                {weekStart && typeof selectedWeek === 'number' && (
                  <span style={{ fontSize: 10, fontWeight: 400, opacity: 0.85 }}>
                    {fmtMonthDay(dateForWeekday(weekStart, selectedWeek, i))}
                  </span>
                )}
              </div>
            );
          })}

          {/* 左侧节次/时间列 */}
          {Array.from({ length: maxSection }, (_, i) => {
            const s = i + 1;
            return (
              <div key={`t${s}`} style={{ gridColumn: 1, gridRow: s + 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', background: 'var(--surface-2)', borderRadius: 4, fontSize: 10, color: 'var(--ink-3, #888)', lineHeight: 1.2 }}>
                <span style={{ fontWeight: 600 }}>{s}</span>
                {startTimeAt[s] && <span>{startTimeAt[s]}</span>}
                {endTimeAt[s] && <span style={{ opacity: 0.7 }}>{endTimeAt[s]}</span>}
              </div>
            );
          })}

          {/* 空格底纹 */}
          {Array.from({ length: 7 }, (_, di) =>
            Array.from({ length: maxSection }, (_, si) => (
              <div key={`e${di}-${si}`} style={{ gridColumn: di + 2, gridRow: si + 2, background: 'var(--surface-2)', opacity: 0.35, borderRadius: 4 }} />
            ))
          )}

          {/* 课程块——只显示课名 + 精简地点，点击看详情 */}
          {blocks.map((b) => {
            const primary = b.items[0];
            const ci = colorIndex(primary.courseName);
            const multi = b.items.length > 1;
            return (
              <button
                key={`b${b.day}-${b.start}`}
                onClick={() => setDetail(b.items)}
                title="点击查看完整信息"
                style={{
                  gridColumn: b.day + 1,
                  gridRow: `${b.start + 1} / ${b.end + 2}`,
                  background: PALETTE[ci],
                  color: PALETTE_FG[ci],
                  border: 'none',
                  borderRadius: 6,
                  padding: '4px 5px',
                  overflow: 'hidden',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 1,
                  textAlign: 'left',
                  cursor: 'pointer',
                  boxShadow: 'inset 0 0 0 1px rgba(0,0,0,0.04)',
                  font: 'inherit'
                }}
              >
                <span style={{ fontWeight: 700, fontSize: 12, lineHeight: 1.25, wordBreak: 'break-word', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                  {primary.courseName}{multi ? ` 等${b.items.length}门` : ''}
                </span>
                {primary.location && (
                  <span style={{ fontSize: 10, opacity: 0.85, lineHeight: 1.2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }}>
                    {shortLocation(primary.location)}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {detail && <DetailModal items={detail} onClose={() => setDetail(null)} onDelete={onDelete} />}
      {adding && <AddCourseModal semester={semester} onClose={() => setAdding(false)} onAdd={onAdd!} />}
    </>
  );
}

/** 详情弹层：展示一组课（通常一门，同格冲突时多门）的完整信息 + 删除 */
function DetailModal({ items, onClose, onDelete }: {
  items: CourseEntry[];
  onClose: () => void;
  onDelete?: (id: number) => void;
}) {
  return (
    <ModalShell width={360} onClose={onClose}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <strong style={{ fontSize: 15, whiteSpace: 'nowrap' }}>课程详情</strong>
        <button className="ghost-button" onClick={onClose} aria-label="关闭" style={{ padding: 4 }}>
          <X size={16} />
        </button>
      </div>

      {items.map((c, idx) => {
        const ci = colorIndex(c.courseName);
        return (
          <div key={c.id} style={{ marginTop: idx > 0 ? 14 : 0, paddingTop: idx > 0 ? 14 : 0, borderTop: idx > 0 ? '1px solid var(--line, #eee)' : 'none' }}>
            {/* 课程名居中 */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, marginBottom: 12 }}>
              <span style={{ width: 10, height: 10, borderRadius: 3, background: PALETTE_FG[ci], flexShrink: 0 }} />
              <span style={{ fontWeight: 700, fontSize: 16, wordBreak: 'break-word', textAlign: 'center' }}>{c.courseName}</span>
            </div>
            {/* 信息块整体居中（标签+值仍左对齐成组） */}
            <div style={{ width: 'fit-content', maxWidth: '100%', margin: '0 auto' }}>
              <Row label="时间" value={slotText(c) || '—'} />
              {(c.startSection || c.endSection) && (
                <Row label="节次" value={c.startSection && c.endSection ? `第 ${c.startSection}-${c.endSection} 节` : `第 ${c.startSection ?? c.endSection} 节`} />
              )}
              <Row icon={<User size={13} />} label="教师" value={c.teacher || '—'} />
              <Row icon={<MapPin size={13} />} label="地点" value={c.location || '—'} />
              <Row label="周次" value={[c.weeks, c.weekParity && PARITY_LABEL[c.weekParity] ? PARITY_LABEL[c.weekParity] : ''].filter(Boolean).join(' ') || '—'} />
              {c.semester && <Row label="学期" value={c.semester} />}
            </div>
            {/* 删除按钮放右下角 */}
            {onDelete && (
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 14 }}>
                <button
                  className="secondary-button"
                  style={{ color: 'var(--danger, #e55)' }}
                  onClick={() => { onDelete(c.id); onClose(); }}
                >
                  <Trash2 size={13} /> 删除这节课
                </button>
              </div>
            )}
          </div>
        );
      })}
    </ModalShell>
  );
}

function Row({ label, value, icon }: { label: string; value: string; icon?: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 10, fontSize: 13.5, padding: '4px 0', lineHeight: 1.5 }}>
      <span className="muted" style={{ flexShrink: 0, width: 48, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        {icon}{label}
      </span>
      <span style={{ wordBreak: 'break-word' }}>{value}</span>
    </div>
  );
}

/** 手动新增课程表单弹层 */
function AddCourseModal({ semester, onClose, onAdd }: {
  semester?: string | null;
  onClose: () => void;
  onAdd: (input: CourseCreateInput) => Promise<void> | void;
}) {
  const [form, setForm] = useState<CourseCreateInput>({
    courseName: '', dayOfWeek: 1, teacher: '', location: '',
    startSection: null, endSection: null, startTime: '', endTime: '',
    weeks: '', weekParity: 'ALL', semester: semester ?? null
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  function set<K extends keyof CourseCreateInput>(k: K, v: CourseCreateInput[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  async function submit() {
    if (!form.courseName.trim()) { setErr('请填写课程名称'); return; }
    setErr('');
    setBusy(true);
    try {
      await onAdd({
        ...form,
        courseName: form.courseName.trim(),
        teacher: form.teacher?.trim() || null,
        location: form.location?.trim() || null,
        startTime: form.startTime?.trim() || null,
        endTime: form.endTime?.trim() || null,
        weeks: form.weeks?.trim() || null,
        semester: form.semester?.trim() || null
      });
      onClose();
    } catch (e) {
      setErr(e instanceof Error ? e.message : '添加失败');
    } finally {
      setBusy(false);
    }
  }

  const fieldStyle: React.CSSProperties = { display: 'grid', gap: 4, fontSize: 12 };

  return (
    <ModalShell width={460} onClose={onClose}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <strong style={{ fontSize: 15, whiteSpace: 'nowrap' }}>添加课程</strong>
        <button className="ghost-button inline" onClick={onClose} aria-label="关闭" style={{ padding: 4 }}><X size={16} /></button>
      </div>

      <div className="tt-form">
        <label style={fieldStyle}>
          <span className="muted">课程名称 *</span>
          <input value={form.courseName} onChange={(e) => set('courseName', e.target.value)} placeholder="如 高等数学A2" maxLength={100} />
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 }}>
          <label style={fieldStyle}>
            <span className="muted">星期 *</span>
            <select value={form.dayOfWeek} onChange={(e) => set('dayOfWeek', Number(e.target.value))}>
              {DAY_NAMES.map((d, i) => <option key={i} value={i + 1}>{d}</option>)}
            </select>
          </label>
          <label style={fieldStyle}>
            <span className="muted">起始节次</span>
            <input type="number" min={1} max={20} value={form.startSection ?? ''} onChange={(e) => set('startSection', e.target.value ? Number(e.target.value) : null)} placeholder="如 1" />
          </label>
          <label style={fieldStyle}>
            <span className="muted">结束节次</span>
            <input type="number" min={1} max={20} value={form.endSection ?? ''} onChange={(e) => set('endSection', e.target.value ? Number(e.target.value) : null)} placeholder="如 2" />
          </label>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <label style={fieldStyle}>
            <span className="muted">开始时间</span>
            <input value={form.startTime ?? ''} onChange={(e) => set('startTime', e.target.value)} placeholder="如 08:00" maxLength={8} />
          </label>
          <label style={fieldStyle}>
            <span className="muted">结束时间</span>
            <input value={form.endTime ?? ''} onChange={(e) => set('endTime', e.target.value)} placeholder="如 09:40" maxLength={8} />
          </label>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <label style={fieldStyle}>
            <span className="muted">教师</span>
            <input value={form.teacher ?? ''} onChange={(e) => set('teacher', e.target.value)} maxLength={50} />
          </label>
          <label style={fieldStyle}>
            <span className="muted">地点</span>
            <input value={form.location ?? ''} onChange={(e) => set('location', e.target.value)} maxLength={60} />
          </label>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <label style={fieldStyle}>
            <span className="muted">周次</span>
            <input value={form.weeks ?? ''} onChange={(e) => set('weeks', e.target.value)} placeholder="如 1-16" maxLength={30} />
          </label>
          <label style={fieldStyle}>
            <span className="muted">单双周</span>
            <select value={form.weekParity ?? 'ALL'} onChange={(e) => set('weekParity', e.target.value)}>
              <option value="ALL">每周</option>
              <option value="ODD">单周</option>
              <option value="EVEN">双周</option>
            </select>
          </label>
        </div>
      </div>

      {err && <div className="form-error" style={{ marginTop: 8 }}>{err}</div>}

      <div className="row-actions" style={{ marginTop: 14, gap: 8 }}>
        <button className="primary-button" disabled={busy || !form.courseName.trim()} onClick={submit}>
          {busy ? '保存中…' : '保存'}
        </button>
        <button className="secondary-button" onClick={onClose}>取消</button>
      </div>
    </ModalShell>
  );
}

/** 回退布局：无节次数据时，按列堆叠卡片（点击看详情） */
function StackedFallback({ courses, onOpen }: { courses: CourseEntry[]; onOpen: (items: CourseEntry[]) => void }) {
  const byDay: CourseEntry[][] = Array.from({ length: 7 }, () => []);
  for (const c of courses) {
    const d = c.dayOfWeek;
    if (d >= 1 && d <= 7) byDay[d - 1].push(c);
  }
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 8, marginTop: 4 }}>
      {byDay.map((list, idx) => (
        <div key={idx} style={{ minWidth: 0 }}>
          <div className="muted small" style={{ textAlign: 'center', fontWeight: 600, paddingBottom: 6 }}>{DAY_NAMES[idx]}</div>
          <div style={{ display: 'grid', gap: 6 }}>
            {list.length === 0 && <div className="muted small" style={{ textAlign: 'center', opacity: 0.4, padding: '12px 0' }}>—</div>}
            {list.map((c) => (
              <button key={c.id} className="task-card" onClick={() => onOpen([c])} style={{ padding: 8, textAlign: 'left', cursor: 'pointer', border: 'none', font: 'inherit', width: '100%' }} title="点击查看完整信息">
                {slotText(c) && <div className="muted small" style={{ fontSize: 11 }}>{slotText(c)}</div>}
                <div style={{ fontWeight: 600, fontSize: 13, wordBreak: 'break-word' }}>{c.courseName}</div>
                {c.location && <div className="muted small" style={{ fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{shortLocation(c.location)}</div>}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
