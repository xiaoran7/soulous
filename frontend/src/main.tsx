/**
 * 【Soulous 应用入口模块】
 * 包含 App 根组件和应用初始化逻辑。
 *
 * App 组件是整个前端应用的顶层组件，负责：
 * - 全局状态管理（用户、任务、宠物、统计数据）
 * - 页面路由（通过 page 状态切换，非 React Router）
 * - 侧边栏导航渲染
 * - 顶部栏（页面标题、通知铃铛、用户头像）
 * - 认证状态判断（未登录显示 AuthScreen）
 * - 管理员角色自动重定向到审核页
 * - 页面标题/副标题/眉毛文本的中英文映射
 *
 * 页面列表：dashboard / tasks / timetable / chat / review / pet / stats / focus / profile / settings / admin
 */
import React, { useEffect, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  Activity,
  Bot,
  CalendarCheck,
  CalendarRange,
  ClipboardList,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  PawPrint,
  Plus,
  Settings,
  ShieldCheck,
  Timer,
  UserCog
} from 'lucide-react';
import { api, UnauthorizedError } from './api';
import { NavButton, SidebarPet } from './components/shared';
import type { DailyReview, Pet, StudyTask, Summary, User } from './types';
import { AuthScreen } from './pages/AuthScreen';
import { LandingPage } from './pages/LandingPage';
import { Dashboard, resetCheckinCache } from './pages/Dashboard';
import { TasksPage } from './pages/TasksPage';
import { ChatPage, resetChatCache } from './pages/ChatPage';
import { DailyReviewPage } from './pages/DailyReviewPage';
import { PetPage, resetPetLogsCache } from './pages/PetPage';
import { ProfilePage, resetProfileCache } from './pages/ProfilePage';
import { SettingsPage } from './pages/SettingsPage';
import { AdminPage } from './pages/AdminPage';
import { FocusPage, resetFocusCache } from './pages/FocusPage';
import { TimetablePage, resetTimetableCache, type TimetableImportState } from './pages/TimetablePage';
import { type AppPreferences, loadPreferences, savePreferences } from './preferences';
import './styles.css';

/** 【页面路由类型】 */
type Page = 'dashboard' | 'tasks' | 'timetable' | 'chat' | 'review' | 'pet' | 'focus' | 'profile' | 'settings' | 'admin';

/** 【侧边栏收起状态存储键】纯前端 localStorage，记住用户上次的收起/展开选择 */
const SIDEBAR_COLLAPSE_KEY = 'soulous.sidebar.collapsed.v1';

/**
 * 【App 根组件】
 * 应用的顶层组件，管理全局状态和页面路由。
 */
function App() {
  /** 【当前登录用户信息，null 表示未登录】 */
  const [user, setUser] = useState<User | null>(null);
  /** 【用户的任务列表】 */
  const [tasks, setTasks] = useState<StudyTask[]>([]);
  /** 【用户的宠物数据】 */
  const [pet, setPet] = useState<Pet | null>(null);
  /** 【学习统计数据摘要】 */
  const [summary, setSummary] = useState<Summary | null>(null);
  /** 【应用偏好】纯前端 localStorage，控制默认登录页与侧边栏宠物显隐 */
  const [prefs, setPrefs] = useState<AppPreferences>(loadPreferences);
  /** 【当前页面路由状态】初始页取自偏好（默认登录页） */
  const [page, setPage] = useState<Page>(() => loadPreferences().defaultPage);
  /** 【沉浸态】自习室进行中时全屏：隐藏侧边栏与顶栏 */
  const [immersive, setImmersive] = useState(false);
  /** 【未登录时是否已点「开始使用」进入登录页】false 时展示落地页 */
  const [showAuth, setShowAuth] = useState(false);
  /** 【进入登录页时的默认标签】login=登录 / register=注册 */
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');
  /** 【侧边栏抽屉】沉浸态下从左侧滑出导航 */
  const [navDrawerOpen, setNavDrawerOpen] = useState(false);
  /** 【侧边栏收起态】普通模式下手动收起侧边栏，主工作区自适应铺满 */
  const [sidebarCollapsed, setSidebarCollapsed] = useState<boolean>(() => {
    try { return localStorage.getItem(SIDEBAR_COLLAPSE_KEY) === '1'; } catch { return false; }
  });
  useEffect(() => {
    try { localStorage.setItem(SIDEBAR_COLLAPSE_KEY, sidebarCollapsed ? '1' : '0'); } catch { /* ignore */ }
  }, [sidebarCollapsed]);

  /**
   * 【管理员自动重定向效果】
   * 管理员角色只能访问审核管理和个人资料页面，
   * 如果当前页面不是这两个之一，自动重定向到审核管理页。
   */
  useEffect(() => {
    if (user?.role === 'ADMIN' && page !== 'admin' && page !== 'profile' && page !== 'settings') {
      setPage('admin');
    }
  }, [user?.role, page]);

  /** 【全局加载状态】 */
  const [loading, setLoading] = useState(false);
  /** 【全局消息提示】 */
  const [message, setMessage] = useState('');
  /** 【课表导入状态】提升到 App 层，AI 解析中切走再回来不丢进度/结果 */
  const [timetableImport, setTimetableImport] = useState<TimetableImportState>({ importing: false, msg: '', err: '' });
  /** 【每日复盘数据缓存】 */
  const [dailyReview, setDailyReview] = useState<DailyReview | null>(null);

  /**
   * 【应用初始化/数据刷新函数】
   * 并行请求用户信息、任务列表、宠物数据和统计数据。
   * 成功后更新全局状态，失败时清空用户状态（触发登录页面）。
   * 被多处调用：页面加载、登录成功、任务变更后刷新等。
   */
  async function bootstrap() {
    setLoading(true);
    try {
      const [me, taskList, petData, summaryData] = await Promise.all([
        api.me(),
        api.tasks(),
        api.pet(),
        api.summary()
      ]);
      setUser(me as User);
      setTasks(Array.isArray(taskList) ? taskList : []);
      setPet(petData as Pet);
      setSummary(summaryData as Summary);
      setMessage('');
    } catch (error) {
      setUser(null);
      if (!(error instanceof UnauthorizedError)) {
        setMessage(error instanceof Error ? error.message : '加载失败');
      }
    } finally {
      setLoading(false);
    }
  }

  /** 【应用挂载时执行初始化】 */
  useEffect(() => { void bootstrap(); }, []);

  /**
   * 【退出登录处理】
   * 调用 API 清除服务端会话，清空所有本地状态。
   * 即使 API 调用失败也清空本地状态（cookie 可能已失效）。
   */
  /** 【清空本地会话状态】退出登录 / 退出所有设备共用，回到登录页 */
  function clearSession() {
    setUser(null);
    setTasks([]);
    setPet(null);
    setSummary(null);
    resetAllCaches();
  }

  /** 【清空所有模块级缓存】登出/切号共用，防止下一个账号看到上一个账号的数据 */
  function resetAllCaches() {
    resetTimetableCache();
    resetFocusCache();
    resetProfileCache();
    resetChatCache();
    resetPetLogsCache();
    resetCheckinCache();
  }

  async function handleLogout() {
    try { await api.logout(); } catch { /* cookie already cleared on error */ }
    clearSession();
  }

  /** 【更新应用偏好】落 localStorage 并刷新本地状态（侧边栏宠物等即时生效） */
  function updatePreferences(next: AppPreferences) {
    setPrefs(next);
    savePreferences(next);
  }

  // 离开自习室页立即退出沉浸态；退出沉浸态时收起抽屉
  useEffect(() => { if (page !== 'focus') setImmersive(false); }, [page]);
  useEffect(() => { if (!immersive) setNavDrawerOpen(false); }, [immersive]);

  if (!user) {
    if (!showAuth) {
      return <LandingPage onStart={(mode) => { setAuthMode(mode); setShowAuth(true); }} />;
    }
    return <AuthScreen initialMode={authMode} onAuthed={() => { resetAllCaches(); void bootstrap(); }} message={message} />;
  }

  const isAdmin = user.role === 'ADMIN';
  /** 【仅在 Dashboard 页面显示"新建任务"快捷按钮】 */
  const showQuickCreate = page === 'dashboard';

  return (
    <div className={`app-shell${immersive ? ' immersive' : ''}${immersive && navDrawerOpen ? ' nav-open' : ''}${sidebarCollapsed && !immersive ? ' sidebar-collapsed' : ''}`}>
      {/* 沉浸态：左侧抽屉把手 + 遮罩，用于唤回被隐藏的侧边栏 */}
      {immersive && (
        <>
          <button className="nav-drawer-handle" onClick={() => setNavDrawerOpen(o => !o)} aria-label="菜单">
            <Menu size={18} />
          </button>
          {navDrawerOpen && <div className="nav-drawer-backdrop" onClick={() => setNavDrawerOpen(false)} />}
        </>
      )}
      {/* 普通模式收起后：左上角悬浮把手，用于重新展开侧边栏 */}
      {!immersive && sidebarCollapsed && (
        <button className="sidebar-expand-handle" onClick={() => setSidebarCollapsed(false)} aria-label="展开侧边栏" title="展开侧边栏">
          <PanelLeftOpen size={18} />
        </button>
      )}
      <aside className="sidebar">
        <div className="brand">
          <button className="sidebar-collapse-btn" onClick={() => setSidebarCollapsed(true)} aria-label="收起侧边栏" title="收起侧边栏">
            <PanelLeftClose size={18} />
          </button>
          <span>Soulous <em>灵魂</em></span>
        </div>

        {!isAdmin && (
          <>
            <div className="nav-group">
              <div className="nav-group-label">Daily · 日常</div>
              <NavButton active={page === 'dashboard'} icon={<Activity size={16} />} label="工作台" onClick={() => setPage('dashboard')} />
              <NavButton active={page === 'tasks'} icon={<ClipboardList size={16} />} label="任务" onClick={() => setPage('tasks')} />
              <NavButton active={page === 'timetable'} icon={<CalendarRange size={16} />} label="课表" onClick={() => setPage('timetable')} />
              <NavButton active={page === 'review'} icon={<CalendarCheck size={16} />} label="复盘" onClick={() => setPage('review')} />
              <NavButton active={page === 'focus'} icon={<Timer size={16} />} label="自习室" onClick={() => setPage('focus')} />
            </div>

            <div className="nav-group">
              <div className="nav-group-label">Tools · 工具</div>
              <NavButton active={page === 'chat'} icon={<Bot size={16} />} label="AI 拆解" onClick={() => setPage('chat')} />
            </div>
          </>
        )}

        <div className="nav-group">
          <div className="nav-group-label">Account · 账号</div>
          {isAdmin && (
            <NavButton active={page === 'admin'} icon={<ShieldCheck size={16} />} label="审核管理" onClick={() => setPage('admin')} />
          )}
          <NavButton active={page === 'profile'} icon={<UserCog size={16} />} label="我的" onClick={() => setPage('profile')} />
          {!isAdmin && (
            <NavButton active={page === 'pet'} icon={<PawPrint size={16} />} label="宠物" onClick={() => setPage('pet')} />
          )}
          <NavButton active={page === 'settings'} icon={<Settings size={16} />} label="设置" onClick={() => setPage('settings')} />
        </div>

        {!isAdmin && prefs.showSidebarPet && <SidebarPet pet={pet} onOpen={() => setPage('pet')} />}

        <button className="ghost-button logout" onClick={() => void handleLogout()}>
          <LogOut size={14} /> 退出
        </button>
      </aside>

      <main className={page === 'chat' ? 'is-chat' : undefined}>
        {/* 顶栏精简：通知铃铛与用户信息已移除（个人资料走侧栏「我的」）。
            仅在工作台保留「新建任务」快捷入口，其余页面不渲染顶栏，让内容铺满。 */}
        {showQuickCreate && (
          <header className="topbar">
            <div />
            <div className="topbar-actions">
              <button className="primary-button" onClick={() => setPage('tasks')}>
                <Plus size={14} /> 新建任务
              </button>
            </div>
          </header>
        )}

        {loading && <div className="notice">正在同步数据...</div>}
        {message && <div className="notice">{message}</div>}

        {page === 'dashboard' && <Dashboard tasks={tasks} pet={pet} summary={summary} onRefresh={bootstrap} onPetSync={setPet} onOpenTasks={() => setPage('tasks')} onOpenReview={() => setPage('review')} onOpenPet={() => setPage('pet')} />}
        {page === 'tasks' && <TasksPage tasks={tasks} onRefresh={bootstrap} />}
        {page === 'timetable' && <TimetablePage onRefresh={bootstrap} importState={timetableImport} setImportState={setTimetableImport} />}
        {page === 'chat' && <ChatPage />}
        {page === 'review' && <DailyReviewPage summary={summary} review={dailyReview} onReviewChange={setDailyReview} />}
        {page === 'pet' && <PetPage pet={pet} onRefresh={bootstrap} onFed={setPet} />}
        {page === 'profile' && <ProfilePage user={user} />}
        {page === 'settings' && <SettingsPage user={user} onUpdated={setUser} onPetUpdated={setPet} prefs={prefs} onPrefsChange={updatePreferences} onSessionEnded={clearSession} />}
        {page === 'focus' && <FocusPage userId={user.id} onImmersiveChange={setImmersive} />}
        {page === 'admin' && <AdminPage user={user} onRefresh={bootstrap} />}
      </main>
    </div>
  );
}

/**
 * 【应用挂载】
 * 获取根 DOM 容器，创建 React Root 并渲染 App 组件。
 * 将 Root 实例缓存到 window 对象上，支持 HMR（热模块替换）时复用。
 */
const rootContainer = document.getElementById('root')!;
const rootWindow = window as typeof window & { __soulousRoot?: Root };
const root = rootWindow.__soulousRoot ?? createRoot(rootContainer);
rootWindow.__soulousRoot = root;
root.render(<App />);
