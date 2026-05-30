/**
 * 【课表页面】TimetablePage
 * 1. 课表展示（置顶）：周一~周日网格，可手动增删单节课；多学期时顶部下拉切换。
 * 2. 导入课表（底部）：主路径上传教务系统导出的 .xls；折叠区保留"粘贴 HTML"为高级备选。
 *
 * 取数据说明：教务系统可直接导出 .xls（学期课表）。前端用 SheetJS 解析 .xls → 转成 HTML 表格
 * → 复用后端 /api/timetable/import 的 AI 解析链路落库；学期自动从表头单元格识别，无需手填。
 * 粘贴 HTML 仍作为兜底（老用户 / 无法导出 xls 的系统）。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { CalendarRange, ChevronDown, ChevronLeft, ChevronRight, FileSpreadsheet, Settings2, Trash2, Upload } from 'lucide-react';
import { api } from '../api';
import type { CourseCreateInput, CourseEntry } from '../types';
import { TimetableGrid } from '../components/TimetableGrid';
import { ModalShell } from '../components/Modal';
import { currentWeekOf, dateForWeekday, fmtMonthDay, maxWeekOf, mondayOf, parseISODate, toISODate } from '../timetableWeeks';

/** 开学日期（第 1 周周一）按学期存 localStorage 的 key */
function weekStartKey(semester: string): string {
  return `soulous.tt.weekStart.${semester || '_all'}`;
}
function loadWeekStart(semester: string): string {
  try { return localStorage.getItem(weekStartKey(semester)) || ''; } catch { return ''; }
}
function saveWeekStart(semester: string, iso: string) {
  try {
    if (iso) localStorage.setItem(weekStartKey(semester), iso);
    else localStorage.removeItem(weekStartKey(semester));
  } catch { /* ignore */ }
}

/** 从一段文本里识别学期标识，如 "学年学期：2025-2026-2" → "2025-2026-2" */
function detectSemester(text: string): string | undefined {
  const m = text.match(/(\d{4})\s*[-–]\s*(\d{4})\s*[-–]\s*([1-3])/);
  return m ? `${m[1]}-${m[2]}-${m[3]}` : undefined;
}

export function TimetablePage({ onRefresh }: { onRefresh: () => void }) {
  const [courses, setCourses] = useState<CourseEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedSemester, setSelectedSemester] = useState<string>('');

  // 周次导航
  const [weekStartISO, setWeekStartISO] = useState<string>('');   // 第 1 周周一（yyyy-mm-dd），按学期持久化
  const [selectedWeek, setSelectedWeek] = useState<number>(1);
  const [editingStart, setEditingStart] = useState(false);
  const [managing, setManaging] = useState(false);

  // 导入区
  const [html, setHtml] = useState('');
  const [importing, setImporting] = useState(false);
  const [importMsg, setImportMsg] = useState('');
  const [importErr, setImportErr] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [showGuide, setShowGuide] = useState(false);

  async function loadCourses() {
    setLoading(true);
    try {
      const list = await api.timetable();
      setCourses(Array.isArray(list) ? list : []);
    } catch {
      /* ignore */
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadCourses();
  }, []);

  // 已导入课表里出现过的学期（去重、排序），用于顶部切换
  const semesters = useMemo(() => {
    const set = new Set<string>();
    for (const c of courses) if (c.semester) set.add(c.semester);
    return Array.from(set).sort().reverse();
  }, [courses]);

  // 当前展示的课程：选了学期则过滤，否则全部
  const shownCourses = useMemo(
    () => (selectedSemester ? courses.filter((c) => c.semester === selectedSemester) : courses),
    [courses, selectedSemester]
  );

  const maxWeek = useMemo(() => maxWeekOf(shownCourses), [shownCourses]);
  const weekStart = useMemo(() => parseISODate(weekStartISO), [weekStartISO]);
  const currentWeek = useMemo(
    () => (weekStart ? Math.min(maxWeek, currentWeekOf(weekStart)) : null),
    [weekStart, maxWeek]
  );

  // 切换学期：读取该学期的开学日期，并把选中周重置到"当前周"（无开学日期则第 1 周）
  useEffect(() => {
    const iso = loadWeekStart(selectedSemester);
    setWeekStartISO(iso);
    const ws = parseISODate(iso);
    setSelectedWeek(ws ? Math.min(maxWeekOf(shownCourses), currentWeekOf(ws)) : 1);
    setEditingStart(false);
    // 仅在学期切换时重置；maxWeek 变化不重置用户当前浏览的周
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSemester]);

  /** 设置/清除开学日期（按当前学期持久化） */
  function applyWeekStart(iso: string) {
    const monday = parseISODate(iso);
    const normalized = monday ? toISODate(mondayOf(monday)) : '';
    setWeekStartISO(normalized);
    saveWeekStart(selectedSemester, normalized);
    setEditingStart(false);
    if (normalized) {
      const ws = parseISODate(normalized)!;
      setSelectedWeek(Math.min(maxWeek, currentWeekOf(ws)));
    }
  }

  const clampWeek = (w: number) => Math.max(1, Math.min(maxWeek, w));

  // 选中周的"周一~周日"日期范围标签
  const weekRangeLabel = useMemo(() => {
    if (!weekStart) return '';
    const mon = dateForWeekday(weekStart, selectedWeek, 0);
    const sun = dateForWeekday(weekStart, selectedWeek, 6);
    return `${fmtMonthDay(mon)} – ${fmtMonthDay(sun)}`;
  }, [weekStart, selectedWeek]);

  /** 落库后统一刷新；导入/新增/删除/清空共用 */
  async function refreshAll(focusSemester?: string) {
    await loadCourses();
    if (focusSemester) setSelectedSemester(focusSemester);
    onRefresh();
  }

  /** 用解析好的 HTML + 学期调后端 AI 解析导入 */
  async function importFromHtml(htmlSource: string, semester?: string) {
    setImportErr('');
    setImportMsg('');
    setImporting(true);
    try {
      const res = await api.importTimetable(htmlSource, semester, true);
      await refreshAll(res.semester ?? semester);
      setImportMsg(`导入成功：识别到 ${res.count} 节课${res.semester ? `（${res.semester}）` : ''}。`);
      setHtml('');
    } catch (err) {
      setImportErr(err instanceof Error ? err.message : '导入失败');
    } finally {
      setImporting(false);
    }
  }

  /** 选中 .xls/.xlsx → SheetJS 解析 → 转 HTML 表格 + 识别学期 → 走 AI 导入 */
  async function onPickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许重复选同一文件
    if (!file) return;
    setImportErr('');
    setImportMsg('');
    setImporting(true);
    try {
      // 动态加载 SheetJS：xlsx 体积较大，只有真正导入 Excel 时才拉取，避免进主包
      const XLSX = await import('xlsx');
      const buf = await file.arrayBuffer();
      const wb = XLSX.read(buf, { type: 'array' });
      const ws = wb.Sheets[wb.SheetNames[0]];
      if (!ws) throw new Error('表格为空或无法读取');
      const tableHtml = XLSX.utils.sheet_to_html(ws);
      const csvText = XLSX.utils.sheet_to_csv(ws);
      const semester = detectSemester(csvText) ?? detectSemester(file.name);
      await importFromHtml(tableHtml, semester);
    } catch (err) {
      setImportErr(err instanceof Error ? `解析 Excel 失败：${err.message}` : '解析 Excel 失败');
      setImporting(false);
    }
  }

  async function addCourse(input: CourseCreateInput) {
    const created = await api.createCourse({
      ...input,
      semester: input.semester ?? (selectedSemester || null)
    });
    await refreshAll(created.semester ?? (selectedSemester || undefined));
  }

  async function removeCourse(id: number) {
    try {
      await api.deleteCourse(id);
      setCourses((prev) => prev.filter((c) => c.id !== id));
    } catch {
      /* ignore */
    }
  }

  /** 清空指定学期的课表（sem 必为非空具体学期） */
  async function clearSemester(sem: string) {
    if (!window.confirm(`确定清空学期 ${sem} 的课表吗？此操作不可撤销。`)) return;
    try {
      await api.clearTimetable(sem);
      saveWeekStart(sem, ''); // 一并清掉该学期的开学日期
      if (selectedSemester === sem) setSelectedSemester('');
      await loadCourses();
    } catch {
      /* ignore */
    }
  }

  /** 清空"未标注学期"的课程（逐条删除，因后端按学期清空无法定位 null 学期） */
  async function clearUnlabeled() {
    const ids = courses.filter((c) => !c.semester).map((c) => c.id);
    if (ids.length === 0) return;
    if (!window.confirm(`确定删除 ${ids.length} 节未标注学期的课程吗？此操作不可撤销。`)) return;
    try {
      for (const id of ids) {
        // eslint-disable-next-line no-await-in-loop
        await api.deleteCourse(id);
      }
      await loadCourses();
    } catch {
      /* ignore */
    }
  }

  /** 清空全部学期的课表 */
  async function clearEverything() {
    if (!window.confirm('确定清空全部课表（所有学期）吗？此操作不可撤销。')) return;
    try {
      await api.clearTimetable();
      setSelectedSemester('');
      await loadCourses();
    } catch {
      /* ignore */
    }
  }

  const hasCourses = courses.length > 0;

  // 管理面板用：每个已导入学期的节数 + 开学日期；以及未标注学期的课程数
  const manageEntries = useMemo(
    () => semesters.map((s) => ({ semester: s, count: courses.filter((c) => c.semester === s).length, weekStart: loadWeekStart(s) })),
    [semesters, courses]
  );
  const unlabeledCount = useMemo(() => courses.filter((c) => !c.semester).length, [courses]);

  return (
    <div className="panel">
      {/* ===== 课表展示（置顶）===== */}
      <div className="panel-title">
        <h2><CalendarRange size={18} style={{ verticalAlign: '-3px' }} /> 我的课表 {hasCourses ? `（${shownCourses.length} 节）` : ''}</h2>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          {semesters.length > 1 && (
            <select
              value={selectedSemester}
              onChange={(e) => setSelectedSemester(e.target.value)}
              style={{ fontSize: 13 }}
              title="切换学期"
            >
              <option value="">全部学期</option>
              {semesters.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          )}
          {hasCourses && (
            <button className="ghost-button small" onClick={() => setManaging(true)} title="管理已导入的课表">
              <Settings2 size={14} /> 管理课表
            </button>
          )}
        </div>
      </div>

      {loading && <div className="muted small">加载中…</div>}
      {!loading && !hasCourses && (
        <div className="muted small" style={{ padding: '8px 0' }}>
          还没有课表。可在下方上传教务系统导出的 .xls 课表，或用"添加课程"手动补一节。
        </div>
      )}

      {/* 周次导航 */}
      {hasCourses && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', margin: '4px 0 10px' }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <button className="ghost-button inline" aria-label="上一周" disabled={selectedWeek <= 1} onClick={() => setSelectedWeek((w) => clampWeek(w - 1))} style={{ padding: 4 }}>
              <ChevronLeft size={16} />
            </button>
            <select value={selectedWeek} onChange={(e) => setSelectedWeek(clampWeek(Number(e.target.value)))} style={{ fontSize: 13 }} title="切换周次">
              {Array.from({ length: maxWeek }, (_, i) => i + 1).map((w) => (
                <option key={w} value={w}>第 {w} 周{currentWeek === w ? '（本周）' : ''}</option>
              ))}
            </select>
            <button className="ghost-button inline" aria-label="下一周" disabled={selectedWeek >= maxWeek} onClick={() => setSelectedWeek((w) => clampWeek(w + 1))} style={{ padding: 4 }}>
              <ChevronRight size={16} />
            </button>
          </div>

          {weekRangeLabel && <span className="muted small">{weekRangeLabel}</span>}

          {currentWeek != null && selectedWeek !== currentWeek && (
            <button className="ghost-button small inline" onClick={() => setSelectedWeek(currentWeek)}>回到本周</button>
          )}

          {/* 开学日期设置（用于显示日期/定位本周） */}
          <button className="ghost-button small inline" onClick={() => setEditingStart(true)} title="设置开学日期后即可显示每天的日期、定位本周">
            <CalendarRange size={13} /> {weekStartISO ? '调整开学日期' : '设置开学日期（显示日期）'}
          </button>
        </div>
      )}

      {editingStart && (
        <StartDateModal
          value={weekStartISO}
          onClose={() => setEditingStart(false)}
          onApply={applyWeekStart}
        />
      )}

      {(hasCourses || !loading) && (
        <TimetableGrid
          courses={shownCourses}
          onDelete={removeCourse}
          onAdd={addCourse}
          semester={selectedSemester || (semesters.length === 1 ? semesters[0] : null)}
          selectedWeek={selectedWeek}
          weekStart={weekStart}
          currentWeek={currentWeek}
        />
      )}

      {managing && (
        <ManageTimetableModal
          entries={manageEntries}
          unlabeledCount={unlabeledCount}
          selectedSemester={selectedSemester}
          onView={(sem) => { setSelectedSemester(sem); setManaging(false); }}
          onSetStart={(sem) => { setSelectedSemester(sem); setManaging(false); setEditingStart(true); }}
          onClearSemester={clearSemester}
          onClearUnlabeled={clearUnlabeled}
          onClearAll={clearEverything}
          onClose={() => setManaging(false)}
        />
      )}

      {/* ===== 导入课表（底部）===== */}
      <div className="panel-title" style={{ marginTop: 26 }}>
        <h3 style={{ margin: 0 }}><Upload size={15} style={{ verticalAlign: '-2px' }} /> 导入课表</h3>
      </div>
      <div className="muted small" style={{ marginBottom: 10 }}>
        在教务系统导出学期课表（.xls），上传到这里，AI 会自动识别课程、教师、地点、节次与学期。
      </div>

      <label className="primary-button" style={{ display: 'inline-flex', cursor: importing ? 'default' : 'pointer', opacity: importing ? 0.6 : 1 }}>
        <FileSpreadsheet size={14} /> {importing ? '解析中…' : '选择 .xls 课表文件'}
        <input type="file" accept=".xls,.xlsx" onChange={onPickFile} disabled={importing} style={{ display: 'none' }} />
      </label>

      {importing && (
        <div style={{ marginTop: 12 }}>
          <div className="muted small" style={{ marginBottom: 6 }}>✨ AI 正在解析课表，请稍候…</div>
          <div className="progress-indeterminate" />
        </div>
      )}

      {importErr && <div className="form-error" style={{ marginTop: 8 }}>{importErr}</div>}
      {importMsg && <div className="notice" style={{ marginTop: 8 }}>{importMsg}</div>}

      {/* 高级：粘贴 HTML（兜底） */}
      <button
        className="ghost-button"
        onClick={() => setShowAdvanced((s) => !s)}
        style={{ marginTop: 12 }}
      >
        {showAdvanced ? <ChevronDown size={14} /> : <ChevronRight size={14} />} 其它导入方式：粘贴课表 HTML（高级）
      </button>

      {showAdvanced && (
        <div style={{ marginTop: 8 }}>
          <button className="ghost-button" onClick={() => setShowGuide((s) => !s)} style={{ marginBottom: 8 }}>
            {showGuide ? <ChevronDown size={14} /> : <ChevronRight size={14} />} 怎么拿到课表 HTML？（点开看教程）
          </button>

          {showGuide && (
            <div className="muted small" style={{ background: 'var(--surface-2)', borderRadius: 6, padding: 12, marginBottom: 10, lineHeight: 1.7 }}>
              <strong>方式 A（推荐）：保存网页</strong>
              <ol style={{ margin: '4px 0 8px', paddingLeft: 18 }}>
                <li>电脑浏览器登录学校<b>教务系统</b>，打开「课程表 / 我的课表」页面；</li>
                <li>按 <b>Ctrl+S</b> 保存网页（保存为「网页，仅 HTML」）；</li>
                <li>用<b>记事本</b>打开保存的 .html 文件，<b>Ctrl+A 全选、Ctrl+C 复制</b>；</li>
                <li>粘贴到下面的输入框，点「导入」。</li>
              </ol>
              <strong>方式 B：开发者工具复制</strong>
              <ol style={{ margin: '4px 0 8px', paddingLeft: 18 }}>
                <li>在课表页按 <b>F12</b> 打开开发者工具 → Elements；</li>
                <li>找到课表所在的 <code>&lt;table&gt;</code>（或 body），右键 → Copy → <b>Copy outerHTML</b>；</li>
                <li>粘贴进来，点「导入」。</li>
              </ol>
            </div>
          )}

          <textarea
            value={html}
            onChange={(e) => setHtml(e.target.value)}
            placeholder="把教务系统课表页的 HTML 粘贴到这里…"
            rows={6}
            style={{ width: '100%', fontFamily: 'monospace', fontSize: 12, resize: 'vertical' }}
          />
          <div className="row-actions" style={{ marginTop: 8 }}>
            <button className="primary-button" disabled={importing || !html.trim()} onClick={() => importFromHtml(html, detectSemester(html))}>
              <Upload size={14} /> {importing ? 'AI 解析中…' : '导入 HTML'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** 开学日期设置弹层：选第 1 周周一，用于显示每天日期、定位本周 */
function StartDateModal({ value, onClose, onApply }: {
  value: string;
  onClose: () => void;
  onApply: (iso: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  return (
    <ModalShell width={360} onClose={onClose}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <strong style={{ fontSize: 15 }}>设置开学日期</strong>
        <button className="ghost-button" onClick={onClose} aria-label="关闭" style={{ padding: 4 }}>✕</button>
      </div>
      <div className="muted small" style={{ marginBottom: 12, lineHeight: 1.6 }}>
        选择本学期 <b>第 1 周的周一</b>。设置后课表表头会显示每天的日期，并能自动定位到本周。
        （选哪天都行，会自动归整到那一周的周一。）
      </div>
      <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
        <span className="muted">开学第一周（周一）</span>
        <input type="date" value={draft} onChange={(e) => setDraft(e.target.value)} style={{ fontSize: 14 }} />
      </label>
      <div className="row-actions" style={{ marginTop: 16, gap: 8 }}>
        <button className="primary-button" disabled={!draft} onClick={() => onApply(draft)}>保存</button>
        {value && <button className="secondary-button" onClick={() => onApply('')} style={{ color: 'var(--danger, #e55)' }}>清除</button>}
        <button className="secondary-button" onClick={onClose}>取消</button>
      </div>
    </ModalShell>
  );
}

/** 管理已导入课表：按学期列出节数/开学日期，可切换查看、设开学日期、清空单学期或全部 */
function ManageTimetableModal({ entries, unlabeledCount, selectedSemester, onView, onSetStart, onClearSemester, onClearUnlabeled, onClearAll, onClose }: {
  entries: Array<{ semester: string; count: number; weekStart: string }>;
  unlabeledCount: number;
  selectedSemester: string;
  onView: (sem: string) => void;
  onSetStart: (sem: string) => void;
  onClearSemester: (sem: string) => void;
  onClearUnlabeled: () => void;
  onClearAll: () => void;
  onClose: () => void;
}) {
  const total = entries.reduce((s, e) => s + e.count, 0) + unlabeledCount;
  return (
    <ModalShell width={480} onClose={onClose}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <strong style={{ fontSize: 15 }}>管理课表</strong>
        <button className="ghost-button" onClick={onClose} aria-label="关闭" style={{ padding: 4 }}>✕</button>
      </div>
      <div className="muted small" style={{ marginBottom: 12 }}>
        已导入 {entries.length} 个学期，共 {total} 节课。
      </div>

      <div style={{ display: 'grid', gap: 8 }}>
        {entries.map((e) => {
          const active = e.semester === selectedSemester;
          return (
            <div key={e.semester} style={{ border: '1px solid var(--line, #eee)', borderRadius: 8, padding: '10px 12px', background: active ? 'var(--surface-2)' : undefined }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
                <strong style={{ fontSize: 14 }}>{e.semester}{active && <span className="chip" style={{ marginLeft: 8 }}>当前</span>}</strong>
                <span className="muted small">{e.count} 节</span>
              </div>
              <div className="muted small" style={{ marginTop: 2 }}>
                开学日期：{e.weekStart || '未设置'}
              </div>
              <div className="row-actions" style={{ marginTop: 8, gap: 8, flexWrap: 'wrap' }}>
                <button className="ghost-button small" onClick={() => onView(e.semester)}>查看</button>
                <button className="ghost-button small" onClick={() => onSetStart(e.semester)}>
                  <CalendarRange size={13} /> {e.weekStart ? '调整开学日期' : '设置开学日期'}
                </button>
                <button className="ghost-button small" onClick={() => onClearSemester(e.semester)} style={{ color: 'var(--danger, #e55)' }}>
                  <Trash2 size={13} /> 清空
                </button>
              </div>
            </div>
          );
        })}

        {entries.length === 0 && unlabeledCount === 0 && (
          <div className="muted small">还没有已导入的课表。</div>
        )}
        {unlabeledCount > 0 && (
          <div style={{ border: '1px dashed var(--line, #eee)', borderRadius: 8, padding: '10px 12px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8 }}>
              <strong style={{ fontSize: 14 }}>未标注学期</strong>
              <span className="muted small">{unlabeledCount} 节</span>
            </div>
            <div className="muted small" style={{ marginTop: 2 }}>手动添加且未填学期的课程</div>
            <div className="row-actions" style={{ marginTop: 8 }}>
              <button className="ghost-button small" onClick={onClearUnlabeled} style={{ color: 'var(--danger, #e55)' }}>
                <Trash2 size={13} /> 清空未标注课程
              </button>
            </div>
          </div>
        )}
      </div>

      {total > 0 && (
        <div className="row-actions" style={{ marginTop: 16, justifyContent: 'flex-end' }}>
          <button className="secondary-button" onClick={onClearAll} style={{ color: 'var(--danger, #e55)' }}>
            <Trash2 size={14} /> 清空全部课表
          </button>
        </div>
      )}
    </ModalShell>
  );
}
