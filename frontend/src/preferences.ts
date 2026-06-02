/**
 * 【应用偏好模块】
 * 纯前端的用户偏好，存在 localStorage，不走后端（改动最小、即时生效）。
 * 目前包含两项真正生效的偏好：
 * - defaultPage：登录后默认进入的页面（App 初始化时读取）
 * - showSidebarPet：是否在侧边栏底部显示宠物迷你卡片
 *
 * 带版本号的存储键，便于将来结构升级时平滑迁移。
 */

/** 【可作为默认登录页的页面集合】仅日常类页面，避免落到管理/账号类页面 */
export type DefaultPage = 'dashboard' | 'tasks' | 'timetable' | 'review' | 'focus' | 'pet';

/** 【应用偏好结构】 */
export type AppPreferences = {
  /** 登录后默认进入的页面 */
  defaultPage: DefaultPage;
  /** 侧边栏底部是否展示宠物迷你卡片 */
  showSidebarPet: boolean;
};

/** 【localStorage 存储键】带版本号 */
const STORAGE_KEY = 'soulous.prefs.v1';

/** 【默认偏好】首次使用或读取失败时的兜底 */
export const DEFAULT_PREFERENCES: AppPreferences = {
  defaultPage: 'dashboard',
  showSidebarPet: true
};

/** 【默认页可选项的中文标签】供设置页下拉使用 */
export const DEFAULT_PAGE_LABELS: Record<DefaultPage, string> = {
  dashboard: '工作台',
  tasks: '任务',
  timetable: '课表',
  review: '复盘',
  focus: '自习室',
  pet: '宠物'
};

/**
 * 【读取偏好】从 localStorage 解析，缺字段用默认值兜底；解析失败返回默认。
 */
export function loadPreferences(): AppPreferences {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULT_PREFERENCES };
    const parsed = JSON.parse(raw) as Partial<AppPreferences>;
    return {
      defaultPage: parsed.defaultPage && parsed.defaultPage in DEFAULT_PAGE_LABELS
        ? parsed.defaultPage
        : DEFAULT_PREFERENCES.defaultPage,
      showSidebarPet: typeof parsed.showSidebarPet === 'boolean'
        ? parsed.showSidebarPet
        : DEFAULT_PREFERENCES.showSidebarPet
    };
  } catch {
    return { ...DEFAULT_PREFERENCES };
  }
}

/**
 * 【保存偏好】立即写回 localStorage；写入失败静默忽略（隐私模式等）。
 */
export function savePreferences(prefs: AppPreferences): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch { /* localStorage 不可用时静默忽略 */ }
}
