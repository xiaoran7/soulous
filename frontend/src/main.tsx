/**
 * 【Soulous 应用入口模块】
 * 包含 App 根组件和应用初始化逻辑。
 *
 * App 组件是整个前端应用的顶层组件，负责：
 * - 全局状态管理（用户、任务、宠物、统计数据）
 * - 页面路由（通过 page 状态切换，非 React Router）
 * - 顶部悬浮玻璃导航渲染（Luminous Ethereal "Floating Navigation"，无侧边栏）
 * - 认证状态判断（未登录显示 AuthScreen）
 * - 管理员角色自动重定向到审核页
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
  PawPrint,
  Settings,
  ShieldCheck,
  Timer,
  UserCog
} from 'lucide-react';
import { api, UnauthorizedError } from './api';
import { NavButton, NavCluster, NavPet } from './components/shared';
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
import { SceneBackdrop } from './studyroom/SceneBackdrop';
import './styles.css';

/** 【页面路由类型】 */
type Page = 'dashboard' | 'tasks' | 'timetable' | 'chat' | 'review' | 'pet' | 'focus' | 'profile' | 'settings' | 'admin';

/**
 * 【允许整页滚动的长内容页白名单】
 * 桌面式固定视口：绝大多数页面锁定、滚轮不动整页。
 * 个人资料/设置按需求改为固定页（应用偏好已删，内容收敛进单屏），
 * 仅审核管理（列表长度不可控）放行 main 的整页滚动。
 */
const SCROLL_PAGES: ReadonlySet<Page> = new Set(['admin']);

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
  /** 【当前页面路由状态】产品形态「房间即主页」：登录后固定进自习室 */
  const [page, setPage] = useState<Page>('focus');
  /** 【沉浸态】自习室进行中时全屏：隐藏侧边栏与顶栏 */
  const [immersive, setImmersive] = useState(false);
  /** 【沉浸态导航唤出】鼠标移到屏幕顶部区域时导航条滑下来，移开收回 */
  const [navReveal, setNavReveal] = useState(false);
  /** 【未登录时是否已点「开始使用」进入登录页】false 时展示落地页 */
  const [showAuth, setShowAuth] = useState(false);
  /** 【进入登录页时的默认标签】login=登录 / register=注册 */
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login');

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

  // 离开自习室页立即退出沉浸态
  useEffect(() => { if (page !== 'focus') setImmersive(false); }, [page]);

  // 沉浸态：鼠标进入顶部 90px 唤出导航，离开 170px 以下收回（滞回区间防抖动）
  useEffect(() => {
    if (!immersive) { setNavReveal(false); return; }
    function onMove(e: MouseEvent) {
      setNavReveal(prev => e.clientY <= 90 ? true : (e.clientY > 170 ? false : prev));
    }
    window.addEventListener('mousemove', onMove);
    return () => window.removeEventListener('mousemove', onMove);
  }, [immersive]);

  if (!user) {
    if (!showAuth) {
      return <LandingPage onStart={(mode) => { setAuthMode(mode); setShowAuth(true); }} />;
    }
    // 登录页同样铺场景背景：满足「一进入就置身自习室（背景图 + 玻璃）」的全局体验
    return (
      <>
        <SceneBackdrop userId={null} />
        <AuthScreen initialMode={authMode} onAuthed={() => { resetAllCaches(); void bootstrap(); }} message={message} />
      </>
    );
  }

  const isAdmin = user.role === 'ADMIN';

  /** 【页面切换】 */
  function go(next: Page) {
    setPage(next);
  }

  return (
    <div className={`app-shell${immersive ? ' immersive' : ''}${immersive && navReveal ? ' nav-reveal' : ''}`}>
      {/* 全局场景背景：登录后所有页面都"住在"当前自习室场景里，切场景即换全屋背景 */}
      <SceneBackdrop userId={user.id} />

      {/* 顶部悬浮玻璃胶囊导航（DESIGN.md "Floating Navigation"）：
          品牌标 | 自习室 · 计划 · 宠物 · 我的（四入口）| 宠物芯片。沉浸态下平移出屏、由把手唤回。 */}
      <header className="top-nav">
        <div className="brand"><span>Soulous <em>灵魂</em></span></div>

        {/* 自习室为主，其余功能收进「计划」「我的」两个下拉簇，保持导航稀疏（soulous_1 密度） */}
        <nav className="top-nav-links">
          {!isAdmin && (
            <>
              <NavButton active={page === 'focus'} icon={<Timer size={16} />} label="自习室" onClick={() => go('focus')} />
              <NavCluster
                label="计划"
                icon={<CalendarRange size={16} />}
                active={page === 'dashboard' || page === 'tasks' || page === 'timetable' || page === 'review' || page === 'chat'}
                items={[
                  { key: 'dashboard', icon: <Activity size={15} />, label: '工作台', active: page === 'dashboard', onClick: () => go('dashboard') },
                  { key: 'tasks', icon: <ClipboardList size={15} />, label: '任务', active: page === 'tasks', onClick: () => go('tasks') },
                  { key: 'timetable', icon: <CalendarRange size={15} />, label: '课表', active: page === 'timetable', onClick: () => go('timetable') },
                  { key: 'review', icon: <CalendarCheck size={15} />, label: '复盘', active: page === 'review', onClick: () => go('review') },
                  { key: 'chat', icon: <Bot size={15} />, label: 'AI 拆解', active: page === 'chat', onClick: () => go('chat') }
                ]}
              />
              <NavButton active={page === 'pet'} icon={<PawPrint size={16} />} label="宠物" onClick={() => go('pet')} />
            </>
          )}
          {isAdmin && (
            <NavButton active={page === 'admin'} icon={<ShieldCheck size={16} />} label="审核管理" onClick={() => go('admin')} />
          )}
          <NavCluster
            label="我的"
            icon={<UserCog size={16} />}
            active={page === 'profile' || page === 'settings'}
            items={[
              { key: 'profile', icon: <UserCog size={15} />, label: '个人资料', active: page === 'profile', onClick: () => go('profile') },
              { key: 'settings', icon: <Settings size={15} />, label: '设置', active: page === 'settings', onClick: () => go('settings') },
              { key: 'logout', icon: <LogOut size={15} />, label: '退出登录', danger: true, onClick: () => void handleLogout() }
            ]}
          />
        </nav>

        <div className="top-nav-side">
          {!isAdmin && <NavPet pet={pet} onOpen={() => go('pet')} />}
        </div>
      </header>

      <main className={[
        page === 'chat' ? 'is-chat' : '',
        page === 'focus' ? 'is-room' : '',
        SCROLL_PAGES.has(page) ? 'page-scroll' : ''
      ].filter(Boolean).join(' ') || undefined}>
        {/* 顶栏已移除：工作台重构为 soulous_4 三卡单屏（新建任务入口走「计划→任务」），
            其余页面内容铺满，整页固定不滚动。同步/消息提示改为浮层，不占据布局高度。 */}
        {(loading || message) && (
          <div className="page-notice-float">
            {loading && <div className="notice">正在同步数据...</div>}
            {message && <div className="notice">{message}</div>}
          </div>
        )}

        {page === 'dashboard' && <Dashboard tasks={tasks} pet={pet} summary={summary} onRefresh={bootstrap} onPetSync={setPet} onOpenTasks={() => setPage('tasks')} onOpenReview={() => setPage('review')} onOpenPet={() => setPage('pet')} />}
        {page === 'tasks' && <TasksPage tasks={tasks} onRefresh={bootstrap} />}
        {page === 'timetable' && <TimetablePage onRefresh={bootstrap} importState={timetableImport} setImportState={setTimetableImport} />}
        {page === 'chat' && <ChatPage />}
        {page === 'review' && <DailyReviewPage summary={summary} review={dailyReview} onReviewChange={setDailyReview} />}
        {page === 'pet' && <PetPage pet={pet} onRefresh={bootstrap} onFed={setPet} />}
        {page === 'profile' && <ProfilePage user={user} />}
        {page === 'settings' && <SettingsPage user={user} onUpdated={setUser} onPetUpdated={setPet} onSessionEnded={clearSession} />}
        {page === 'focus' && (
          <FocusPage
            userId={user.id}
            pet={pet}
            summary={summary}
            onImmersiveChange={setImmersive}
            onNavigate={(p) => go(p)}
          />
        )}
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
