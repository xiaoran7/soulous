/**
 * 【课表页面】TimetablePage
 * 1. 课表展示（置顶）：周一~周日网格，可手动增删单节课；多学期时顶部下拉切换。
 * 2. 同步课表（底部）：通过输入学号密码从学校教务系统爬取。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { CalendarRange, ChevronDown, ChevronLeft, ChevronRight, Settings2, Trash2 } from 'lucide-react';
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

/**
 * 【课表导入状态】importing=AI 解析中；msg/err=成功/失败提示。
 * 提升到 App 层持有，使 AI 解析过程中切换面板再回来不丢进度与结果
 * （后端请求本就不会因组件卸载而中断，丢的只是这份 UI 状态）。
 */
export interface TimetableImportState {
  importing: boolean;
  msg: string;
  err: string;
}
const EMPTY_IMPORT: TimetableImportState = { importing: false, msg: '', err: '' };

/**
 * 【课表模块级缓存】跨面板切换时组件会被卸载/重建，若每次都重新拉取就会出现
 * "空白→填充"的顿挫。这里用模块级变量保留上次的课表，重建时直接复用、不再请求；
 * 仅首次进入（缓存为空）或发生导入/增删等变更时才向后端取数。
 * 登出/重新登录时由 resetTimetableCache() 清空，避免串户看到上一个账号的课表。
 */
let coursesCache: CourseEntry[] | null = null;
export function resetTimetableCache() { coursesCache = null; }

export function TimetablePage({ onRefresh, importState, setImportState }: {
  onRefresh: () => void;
  importState?: TimetableImportState;
  setImportState?: React.Dispatch<React.SetStateAction<TimetableImportState>>;
}) {
  // 有缓存则直接渲染，零顿挫；仅缓存为空时才需要首次加载
  const [courses, setCourses] = useState<CourseEntry[]>(() => coursesCache ?? []);
  const [loading, setLoading] = useState(coursesCache === null);
  // 在任何 effect 改写缓存之前，于渲染期捕获"是否需要首次拉取"，避免竞态
  const needInitialLoad = useRef(coursesCache === null);
  const [selectedSemester, setSelectedSemester] = useState<string>('');

  // 周次导航
  const [weekStartISO, setWeekStartISO] = useState<string>('');   // 第 1 周周一（yyyy-mm-dd），按学期持久化
  const [selectedWeek, setSelectedWeek] = useState<number>(1);
  const [editingStart, setEditingStart] = useState(false);
  const [managing, setManaging] = useState(false);

  // 同步状态与输入项
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [internalImport, setInternalImport] = useState<TimetableImportState>(EMPTY_IMPORT);
  const impState = importState ?? internalImport;
  const updateImport = setImportState ?? setInternalImport;
  const importing = impState.importing;
  const importMsg = impState.msg;
  const importErr = impState.err;
  const setImporting = (v: boolean) => updateImport((prev) => ({ ...prev, importing: v }));
  const setImportMsg = (v: string) => updateImport((prev) => ({ ...prev, msg: v }));
  const setImportErr = (v: string) => updateImport((prev) => ({ ...prev, err: v }));

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

  // 课表变化时同步回模块缓存（加载完成、增删、导入后都会经过这里）
  useEffect(() => { coursesCache = courses; }, [courses]);

  useEffect(() => {
    if (needInitialLoad.current) void loadCourses(); // 仅首次进入拉取；有缓存直接复用
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  /** 通过账号密码调用爬虫抓取同步 */
  async function handleSync(e: React.FormEvent) {
    e.preventDefault();
    if (!username.trim() || !password.trim()) {
      setImportErr('请输入学号和教务系统密码');
      return;
    }
    setImportErr('');
    setImportMsg('');
    setImporting(true);
    try {
      const res = await api.syncTimetable(username.trim(), password);
      if (res.weekStart) {
        saveWeekStart(res.semester, res.weekStart);
      }
      await refreshAll(res.semester);
      setImportMsg(`同步成功：拉取到 ${res.count} 节课（学期：${res.semester}）`);
      setPassword(''); // 成功后清除密码
    } catch (err) {
      setImportErr(err instanceof Error ? err.message : '同步失败');
    } finally {
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

      {/* ===== 同步课表（底部）===== */}
      <div className="panel-title" style={{ marginTop: 26 }}>
        <h3 style={{ margin: 0 }}><CalendarRange size={15} style={{ verticalAlign: '-2px' }} /> 同步教务系统课表</h3>
      </div>
      <div className="muted small" style={{ marginBottom: 12, lineHeight: 1.5 }}>
        通过教务系统账号密码直接拉取最新课表，不再需要手动导出文件。首次同步时系统会自动安装所需运行环境，耗时可能较长（1~2分钟），请耐心等待。
      </div>

      <form onSubmit={handleSync} style={{ maxWidth: 400, display: 'grid', gap: 12 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
            <span className="muted">学号/账号</span>
            <input
              type="text"
              required
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="请输入学号"
              disabled={importing}
              style={{ fontSize: 13, padding: '6px 10px', borderRadius: 4, border: '1px solid var(--line, #eee)' }}
            />
          </label>
          <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
            <span className="muted">教务系统密码</span>
            <input
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
              disabled={importing}
              style={{ fontSize: 13, padding: '6px 10px', borderRadius: 4, border: '1px solid var(--line, #eee)' }}
            />
          </label>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <button
            type="submit"
            className="primary-button"
            disabled={importing}
            style={{ width: 'fit-content' }}
          >
            {importing ? '正在同步中...' : '开始同步'}
          </button>
        </div>
      </form>

      {importing && (
        <div style={{ marginTop: 12 }}>
          <div className="muted small" style={{ marginBottom: 6 }}>✨ 正在登录学校统一身份认证并同步课表...</div>
          <div className="progress-indeterminate" />
        </div>
      )}

      {importErr && <div className="form-error" style={{ marginTop: 8 }}>{importErr}</div>}
      {importMsg && <div className="notice" style={{ marginTop: 8 }}>{importMsg}</div>}
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
