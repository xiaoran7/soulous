/**
 * 【课表页面】TimetablePage
 * 1. 课表展示（置顶）：周一~周日网格，可手动增删单节课；多学期时顶部下拉切换。
 * 2. 同步课表（底部）：通过输入学号密码从学校教务系统爬取。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { CalendarRange, ChevronLeft, ChevronRight, ClipboardList, GraduationCap, Settings2, Trash2 } from 'lucide-react';
import { api } from '../api';
import type { CourseCreateInput, CourseEntry, ExamEntry, GradeEntry } from '../types';
import { TimetableGrid } from '../components/TimetableGrid';
import { ExamPanel, GradePanel } from './ExamGrade';
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
let examsCache: ExamEntry[] | null = null;
let gradesCache: GradeEntry[] | null = null;
export function resetTimetableCache() { coursesCache = null; examsCache = null; gradesCache = null; }

export function TimetablePage({ onRefresh, importState, setImportState }: {
  onRefresh: () => void;
  importState?: TimetableImportState;
  setImportState?: React.Dispatch<React.SetStateAction<TimetableImportState>>;
}) {
  // 有缓存则直接渲染，零顿挫；仅缓存为空时才需要首次加载。
  // 课表 / 考试 / 成绩三份数据各自模块级缓存，切 tab 不重新拉取、不闪「加载中」。
  const [courses, setCourses] = useState<CourseEntry[]>(() => coursesCache ?? []);
  const [exams, setExams] = useState<ExamEntry[]>(() => examsCache ?? []);
  const [grades, setGrades] = useState<GradeEntry[]>(() => gradesCache ?? []);
  const [loading, setLoading] = useState(coursesCache === null);
  const [examsLoading, setExamsLoading] = useState(examsCache === null);
  const [gradesLoading, setGradesLoading] = useState(gradesCache === null);
  // 在任何 effect 改写缓存之前，于渲染期捕获"是否需要首次拉取"，避免竞态
  const needInitialLoad = useRef(coursesCache === null);
  const needExams = useRef(examsCache === null);
  const needGrades = useRef(gradesCache === null);
  // 学期为三视图共享：选定后课表、考试、成绩同时切到该学期。
  // 课表固定显示「本学期」，不再有「全部学期」混合视图；旧学期通过下拉单独查看。
  const [selectedSemester, setSelectedSemester] = useState<string>('');

  // 课表页内的子视图：课表 / 考试安排 / 成绩。考试与成绩随课表同步一并抓取，分学期展示。
  const [view, setView] = useState<'schedule' | 'exam' | 'grade'>('schedule');

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

  async function loadExams() {
    setExamsLoading(true);
    try {
      const list = await api.exams();
      setExams(Array.isArray(list) ? list : []);
    } catch {
      /* ignore */
    } finally {
      setExamsLoading(false);
    }
  }

  async function loadGrades() {
    setGradesLoading(true);
    try {
      const list = await api.grades();
      setGrades(Array.isArray(list) ? list : []);
    } catch {
      /* ignore */
    } finally {
      setGradesLoading(false);
    }
  }

  // 数据变化时同步回各自模块缓存（加载完成、增删、导入后都会经过这里）
  useEffect(() => { coursesCache = courses; }, [courses]);
  useEffect(() => { examsCache = exams; }, [exams]);
  useEffect(() => { gradesCache = grades; }, [grades]);

  useEffect(() => {
    // 三份数据各自仅首次进入拉取；有缓存直接复用，切 tab 零顿挫
    if (needInitialLoad.current) void loadCourses();
    if (needExams.current) void loadExams();
    if (needGrades.current) void loadGrades();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 三视图共享的学期集合：课表 + 考试 + 成绩里出现过的学期并集（去重、倒序）
  const semesters = useMemo(() => {
    const set = new Set<string>();
    for (const c of courses) if (c.semester) set.add(c.semester);
    for (const e of exams) if (e.semester) set.add(e.semester);
    for (const g of grades) if (g.semester) set.add(g.semester);
    return Array.from(set).sort().reverse();
  }, [courses, exams, grades]);

  // 课表固定「本学期」：学期数据就绪后，若未选或所选学期已不存在，自动落到最新学期。
  // 这取代了旧的「全部学期」默认值，杜绝上学期成绩混进本学期课表。
  useEffect(() => {
    if (semesters.length === 0) return;
    setSelectedSemester((cur) => (cur && semesters.includes(cur) ? cur : semesters[0]));
  }, [semesters]);

  // 有效学期：始终是一个具体学期（默认最新），下拉切换即可单独查看旧学期。
  const effectiveSemester = selectedSemester || (semesters[0] ?? '');

  // 当前展示的课程/考试/成绩：始终按有效学期过滤（不再有「全部学期」混排）
  const shownCourses = useMemo(
    () => (effectiveSemester ? courses.filter((c) => c.semester === effectiveSemester) : courses),
    [courses, effectiveSemester]
  );
  const shownExams = useMemo(
    () => (effectiveSemester ? exams.filter((e) => e.semester === effectiveSemester) : exams),
    [exams, effectiveSemester]
  );
  const shownGrades = useMemo(
    () => (effectiveSemester ? grades.filter((g) => g.semester === effectiveSemester) : grades),
    [grades, effectiveSemester]
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

  /** 落库后统一刷新；导入/新增/删除/清空共用。同步会一并带回考试/成绩，故一起刷新 */
  async function refreshAll(focusSemester?: string, includeExamGrade = false) {
    await loadCourses();
    if (includeExamGrade) await Promise.all([loadExams(), loadGrades()]);
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
      await refreshAll(res.semester, true); // true：同步带回了考试/成绩，一并刷新
      const extra = [
        res.examCount ? `${res.examCount} 场考试` : '',
        res.gradeCount ? `${res.gradeCount} 门成绩` : ''
      ].filter(Boolean).join('、');
      setImportMsg(`同步成功：拉取到 ${res.count} 节课${extra ? `，并顺带 ${extra}` : ''}（学期：${res.semester}）`);
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
      {/* ===== 课表 / 考试 / 成绩（置顶）===== */}
      <div className="panel-title">
        <h2>
          <CalendarRange size={18} style={{ verticalAlign: '-3px' }} />{' '}
          {view === 'schedule' ? `我的课表 ${hasCourses ? `（${shownCourses.length} 节）` : ''}`
            : view === 'exam' ? '考试安排' : '成绩'}
        </h2>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          {/* 学期选择三视图共享：切学期后课表/考试/成绩同时切换 */}
          {semesters.length > 1 && (
            <select
              value={effectiveSemester}
              onChange={(e) => setSelectedSemester(e.target.value)}
              style={{ fontSize: 13 }}
              title="切换学期（课表 / 考试 / 成绩同步切换）"
            >
              {semesters.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          )}
          {view === 'schedule' && hasCourses && (
            <button className="ghost-button small" onClick={() => setManaging(true)} title="管理已导入的课表">
              <Settings2 size={14} /> 管理课表
            </button>
          )}
        </div>
      </div>

      {/* 子视图切换：课表 / 考试安排 / 成绩 */}
      <div className="chip-group" style={{ marginBottom: 14 }}>
        <button className={`chip${view === 'schedule' ? ' selected' : ''}`} onClick={() => setView('schedule')}>
          <CalendarRange size={13} style={{ verticalAlign: '-2px' }} /> 课表
        </button>
        <button className={`chip${view === 'exam' ? ' selected' : ''}`} onClick={() => setView('exam')}>
          <ClipboardList size={13} style={{ verticalAlign: '-2px' }} /> 考试安排
        </button>
        <button className={`chip${view === 'grade' ? ' selected' : ''}`} onClick={() => setView('grade')}>
          <GraduationCap size={13} style={{ verticalAlign: '-2px' }} /> 成绩
        </button>
      </div>

      {view === 'exam' && (
        <ExamPanel exams={shownExams} loading={examsLoading} selectedSemester={effectiveSemester} />
      )}
      {view === 'grade' && (
        <GradePanel grades={shownGrades} loading={gradesLoading} selectedSemester={effectiveSemester} />
      )}

      {view === 'schedule' && (<>
      {loading && <div className="muted small">加载中…</div>}
      {!loading && !hasCourses && (
        <div className="muted small" style={{ padding: '8px 0' }}>
          还没有课表。可在下方输入教务系统账号密码同步，或用"添加课程"手动补一节。
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
          semester={effectiveSemester || null}
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
      </>)}

      {/* ===== 同步课表（底部）===== */}
      <div className="panel-title" style={{ marginTop: 26 }}>
        <h3 style={{ margin: 0 }}><CalendarRange size={15} style={{ verticalAlign: '-2px' }} /> 同步教务系统课表</h3>
      </div>

      {/* autoComplete 关掉浏览器自动填充：否则会把登录 Soulous 时存的账号密码塞进教务字段，误导用户拿错凭证去同步 */}
      <form onSubmit={handleSync} autoComplete="off" style={{ maxWidth: 400, display: 'grid', gap: 12 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
            <span className="muted">学号/账号</span>
            <input
              type="text"
              required
              name="jw-username"
              autoComplete="off"
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
              name="jw-password"
              autoComplete="new-password"
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
