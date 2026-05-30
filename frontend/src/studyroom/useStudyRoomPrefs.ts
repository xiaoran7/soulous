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

/** 【自习室偏好结构】 */
export interface StudyRoomPrefs {
  /** 选中的场景 ID */
  sceneId: string;
  /** 计划番茄钟时长（分钟） */
  plannedMinutes: number;
  /** 音乐音量 0–1 */
  musicVolume: number;
  /** 环境音音量 0–1 */
  ambientVolume: number;
  /** 是否开启音乐 */
  musicEnabled: boolean;
  /** 当前背景音乐曲目 ID */
  musicTrackId: string;
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
    plannedMinutes: DURATION_PRESETS[1], // 25 分钟
    musicVolume: 0.5,
    ambientVolume: 0.6,
    musicEnabled: true,
    musicTrackId: DEFAULT_MUSIC_ID,
    ambientEnabled: true,
    goal: '',
    goalDate: today(),
  };
}

/**
 * 【读取偏好】从 localStorage 解析，并做跨天目标重置与字段兜底。
 * 任何异常都回退到默认值，保证页面永不因脏数据崩溃。
 */
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

  // 【持久化】prefs 变化时写回本地存储
  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
    } catch {
      /* 隐私模式或配额超限时静默忽略，不影响使用 */
    }
  }, [prefs]);

  /** 【局部更新】合并式更新偏好，例如 update({ musicVolume: 0.3 }) */
  const update = useCallback((patch: Partial<StudyRoomPrefs>) => {
    setPrefs(prev => ({ ...prev, ...patch }));
  }, []);

  return { prefs, update };
}
