/**
 * 【自习室本地偏好】useStudyRoomPrefs
 *
 * 按产品决策，场景选择、音量、今日目标、番茄钟时长等偏好**只存浏览器本地**
 * （localStorage），后端 FocusSession 不动。好处是改动最小、即时生效；
 * 代价是不跨设备、目标不进数据库统计（后续如需再迁后端）。
 *
 * "今日目标"带日期戳：跨天自动清空，避免昨天的目标残留到今天。
 */
import { useCallback, useEffect, useState } from 'react';

import { DEFAULT_MUSIC_ID, DEFAULT_SCENE_ID, DURATION_PRESETS } from './scenes';

/** 【localStorage 存储键】带版本号，便于将来结构升级时平滑迁移 */
const STORAGE_KEY = 'soulous.studyroom.v1';

/**
 * 【偏好变更广播事件】每次写回 localStorage 后派发。
 * 全局场景背景层（SceneBackdrop）等"非本页订阅者"靠它感知场景切换，
 * 实现「在自习室换场景 = 整个 App 换背景」。
 */
export const STUDYROOM_PREFS_EVENT = 'soulous:studyroom-prefs';

/** 【计时方式】up=正计时（不设时长压力） / down=倒计时（plannedMinutes 为目标时长） */
export type TimerMode = 'up' | 'down';

/** 【自习室偏好结构】 */
export interface StudyRoomPrefs {
  /** 选中的场景 ID */
  sceneId: string;
  /** 计时方式：正计时 / 倒计时 */
  timerMode: TimerMode;
  /** 计划番茄钟时长（分钟），倒计时模式下生效 */
  plannedMinutes: number;
  /** 音乐音量 0–1 */
  musicVolume: number;
  /** 环境音音量 0–1 */
  ambientVolume: number;
  /** 是否开启音乐 */
  musicEnabled: boolean;
  /** 当前背景音乐曲目 ID */
  musicTrackId: string;
  /** 当前背景音（环境音）来源 ID：'scene' 表示跟随场景，其余为自定义上传的环境音 ID */
  ambientTrackId: string;
  /** 是否开启环境音 */
  ambientEnabled: boolean;
  /** 今日目标文案 */
  goal: string;
  /** 今日目标对应的日期（YYYY-MM-DD），用于跨天重置 */
  goalDate: string;
}

/** 【今天的日期字符串】本地时区 YYYY-MM-DD */
function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 【默认偏好】首次使用或读取失败时的兜底 */
function defaults(): StudyRoomPrefs {
  return {
    sceneId: DEFAULT_SCENE_ID,
    timerMode: 'up',
    plannedMinutes: DURATION_PRESETS[1], // 25 分钟
    musicVolume: 0.5,
    ambientVolume: 0.6,
    musicEnabled: true,
    musicTrackId: DEFAULT_MUSIC_ID,
    ambientTrackId: 'scene',
    ambientEnabled: true,
    goal: '',
    goalDate: today(),
  };
}

/**
 * 【读取偏好】从 localStorage 解析，并做跨天目标重置与字段兜底。
 * 任何异常都回退到默认值，保证页面永不因脏数据崩溃。
 */
export function readStudyRoomPrefs(): StudyRoomPrefs {
  return read();
}

function read(): StudyRoomPrefs {
  if (typeof window === 'undefined') return defaults();
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaults();
    const parsed = JSON.parse(raw) as Partial<StudyRoomPrefs>;
    const merged = { ...defaults(), ...parsed };
    // 【跨天重置今日目标】
    if (merged.goalDate !== today()) {
      merged.goal = '';
      merged.goalDate = today();
    }
    return merged;
  } catch {
    return defaults();
  }
}

/**
 * 【自习室偏好 Hook】返回当前偏好与一个 patch 更新器。
 * 每次更新都会立即写回 localStorage。
 */
export function useStudyRoomPrefs() {
  const [prefs, setPrefs] = useState<StudyRoomPrefs>(read);

  // 【持久化】prefs 变化时写回本地存储，并广播给全局订阅者（场景背景层等）
  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
    } catch {
      /* 隐私模式或配额超限时静默忽略，不影响使用 */
    }
    window.dispatchEvent(new Event(STUDYROOM_PREFS_EVENT));
  }, [prefs]);

  /** 【局部更新】合并式更新偏好，例如 update({ musicVolume: 0.3 }) */
  const update = useCallback((patch: Partial<StudyRoomPrefs>) => {
    setPrefs(prev => ({ ...prev, ...patch }));
  }, []);

  return { prefs, update };
}
