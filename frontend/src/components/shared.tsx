/**
 * 【共享 UI 组件库】
 * 提供项目中多处复用的基础 UI 组件，包括：
 * - ClickableAvatar：可点击放大的头像组件
 * - NavButton：顶部悬浮导航按钮（玻璃胶囊，激活态琥珀辉光）
 * - Metric：指标展示卡片
 * - TaskRow：任务列表行
 * - PetCard：宠物信息卡片
 * - Empty：空状态占位符
 * - StatBar：统计进度条
 * - ProgressRing：环形进度条
 * - NavPet：顶部导航宠物芯片
 *
 * 以及状态标签映射表（petStatusLabel、statusLabel）和动画状态映射函数（animationForPet）。
 */
import React, { useEffect, useRef, useState } from 'react';
import { ChevronDown, GraduationCap, PawPrint } from 'lucide-react';
import { PetSprite } from '../PetSprite';
import type { PetAnimationState } from '../PetSprite';
import type { Pet, StudyTask } from '../types';
import { ImageLightbox } from './ImageLightbox';

/**
 * 【可点击放大头像组件】
 * 点击头像时弹出 ImageLightbox 全屏预览大图。
 * 如果没有 url 则渲染 fallback 内容（通常是首字母头像）。
 * 点击事件会 stopPropagation 防止触发父元素的点击处理。
 *
 * @param url - 头像图片 URL，null/undefined 时显示 fallback
 * @param alt - 图片 alt 文本
 * @param fallback - 无 URL 时的替代渲染内容
 * @param className - 可选的 CSS 类名
 * @param imgStyle - 可选的图片内联样式
 */
export function ClickableAvatar({
  url,
  alt,
  fallback,
  className,
  imgStyle
}: {
  url?: string | null;
  alt: string;
  fallback?: React.ReactNode;
  className?: string;
  imgStyle?: React.CSSProperties;
}) {
  /** 【灯箱是否打开】 */
  const [open, setOpen] = useState(false);
  if (!url) {
    return <>{fallback ?? null}</>;
  }
  return (
    <>
      <img
        className={className}
        src={url}
        alt={alt}
        style={{ cursor: 'zoom-in', ...imgStyle }}
        onClick={(e) => { e.stopPropagation(); setOpen(true); }}
        title="点击查看大图"
      />
      {open && (
        <ImageLightbox urls={[url]} index={0} onClose={() => setOpen(false)} onNavigate={() => {}} />
      )}
    </>
  );
}

/**
 * 【宠物状态中文标签映射】
 * 将后端返回的宠物状态枚举值转换为用户友好的中文描述。
 * 用于 NavPet 和 PetCard 组件中展示宠物当前情绪。
 */
export const petStatusLabel: Record<string, string> = {
  NORMAL: '安静陪伴',
  WORKING: '正在工作',
  REVIEWING: '等待复核',
  HAPPY: '心情不错',
  PROUD: '为你骄傲',
  EXCITED: '能量满格',
  SLEEPY: '耐心等待',
  SAD: '需要修正'
};

/**
 * 【任务状态中文标签映射】
 * 将任务状态枚举值转换为中文展示文本。
 * 覆盖任务完整生命周期：未开始 → 进行中 → 已暂停 → 待审核 →
 * AI 审核中 → AI 通过/打回 → 需补充 → 申诉中 → 人工通过/驳回 → 已完成。
 * MODERATION_BLOCKED 为内容安全拦截状态。
 */
export const statusLabel: Record<string, string> = {
  TODO: '未开始',
  DOING: '进行中',
  PAUSED: '已暂停',
  SUBMITTED: '待审核',
  AI_REVIEWING: 'AI 审核中',
  AI_APPROVED: 'AI 通过',
  AI_REJECTED: 'AI 打回',
  NEED_MORE: '需补充',
  APPEALING: '申诉中',
  MANUAL_APPROVED: '人工通过',
  MANUAL_REJECTED: '人工驳回',
  COMPLETED: '已完成',
  MODERATION_BLOCKED: '安全拦截'
};

/**
 * 【宠物状态到动画状态的映射函数】
 * 根据宠物当前的情绪状态，返回对应的精灵图动画状态。
 * 用于 PetCard 和 NavPet 组件中驱动宠物动画播放。
 *
 * @param pet - 宠物对象（可为 null）
 * @returns 对应的 PetAnimationState
 */
export function animationForPet(pet: Pet | null): PetAnimationState {
  switch (pet?.status) {
    case 'HAPPY':
    case 'PROUD':
      return 'waving';
    case 'WORKING':
      return 'running';
    case 'REVIEWING':
      return 'review';
    case 'EXCITED':
      return 'jumping';
    case 'SLEEPY':
      return 'waiting';
    case 'SAD':
      return 'failed';
    case 'NORMAL':
    default:
      return 'idle';
  }
}

/**
 * 【动作解锁等级表】每个宠物动画动作对应的解锁等级（唯一数据源）。
 * 宠物页动作预览、首页宠物卡片、顶部导航宠物芯片共用，确保"未解锁动作不播放"行为一致。
 * idle / waiting 在 Lv1 即可用，最后一个动作（review）恰好在满级 Lv30 解锁。
 */
export const PET_ACTION_UNLOCK_LEVEL: Record<PetAnimationState, number> = {
  idle: 1,
  waiting: 1,
  waving: 3,
  failed: 5,
  running: 8,
  'running-right': 12,
  'running-left': 16,
  jumping: 22,
  review: 30
};

/**
 * 【钳制到已解锁动作】给定动画状态在当前等级尚未解锁时回退到始终可用的 idle，
 * 避免在任意展示位（卡片/导航芯片/详情页）播放尚未解锁的动作。
 */
export function clampAnimation(state: PetAnimationState, level: number): PetAnimationState {
  return (PET_ACTION_UNLOCK_LEVEL[state] ?? 1) <= level ? state : 'idle';
}

/**
 * 【导航按钮组件】
 * 顶部悬浮玻璃导航条中的导航项，激活态以琥珀辉光高亮（非实底色块）。
 *
 * @param active - 是否为当前激活页面
 * @param icon - 按钮图标（lucide-react 组件）
 * @param label - 按钮文本
 * @param onClick - 点击回调
 */
export function NavButton({ active, icon, label, onClick }: {
  active: boolean;
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button className={`nav-button ${active ? 'active' : ''}`} onClick={onClick}>
      {icon}<span>{label}</span>
    </button>
  );
}

/** 【导航下拉簇的子项】 */
export type NavClusterItem = {
  key: string;
  icon: React.ReactNode;
  label: string;
  /** 是否为当前激活页 */
  active?: boolean;
  /** 危险操作（退出登录），悬停泛红 */
  danger?: boolean;
  onClick: () => void;
};

/**
 * 【导航下拉簇组件】
 * 顶部悬浮导航的"功能簇"：一个胶囊按钮收纳多个二级页面入口，
 * 悬停即展开第三层玻璃弹层（DESIGN.md Tonal Stacking），保持导航条稀疏；
 * 点击也可切换（兼容触屏）。移出簇 160ms 后收起 / 点击子项 / 点击外部 / Esc 均收起。
 *
 * @param label - 簇名（如「计划」「我的」）
 * @param icon - 簇图标
 * @param active - 簇内任一页面激活时整簇高亮
 * @param items - 子项列表
 */
export function NavCluster({ label, icon, active, items }: {
  label: string;
  icon: React.ReactNode;
  active: boolean;
  items: NavClusterItem[];
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  /** 【悬停离开的延时收起】短暂离开（按钮→弹层之间的缝隙）不闪关 */
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  function hoverOpen() {
    if (closeTimer.current) clearTimeout(closeTimer.current);
    setOpen(true);
  }
  function hoverClose() {
    if (closeTimer.current) clearTimeout(closeTimer.current);
    closeTimer.current = setTimeout(() => setOpen(false), 160);
  }
  useEffect(() => () => { if (closeTimer.current) clearTimeout(closeTimer.current); }, []);
  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);
  return (
    <div className="nav-cluster" ref={rootRef} onMouseEnter={hoverOpen} onMouseLeave={hoverClose}>
      <button
        className={`nav-button ${active ? 'active' : ''}`}
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-haspopup="menu"
      >
        {icon}<span>{label}</span>
        <ChevronDown size={13} className={`nav-caret${open ? ' open' : ''}`} />
      </button>
      {open && (
        <div className="nav-cluster-menu" role="menu">
          {items.map((item) => (
            <button
              key={item.key}
              role="menuitem"
              className={`nav-cluster-item${item.active ? ' active' : ''}${item.danger ? ' danger' : ''}`}
              onClick={() => { setOpen(false); item.onClick(); }}
            >
              {item.icon}<span>{item.label}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * 【指标展示组件】
 * Dashboard 首页的统计指标卡片，显示图标、标签和数值。
 *
 * @param icon - 指标图标
 * @param label - 指标名称
 * @param value - 指标数值
 */
export function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: number }) {
  return <div className="metric"><span>{icon}</span><p>{label}</p><strong>{value}</strong></div>;
}

/**
 * 【任务列表行组件】
 * 展示单个任务的标题、课程名、类型、难度和当前状态徽章。
 * 用于任务列表页和 Dashboard 的任务概览。
 *
 * @param task - 任务数据对象
 */
export function TaskRow({ task }: { task: StudyTask }) {
  return (
    <div className="task-row">
      <div>
        <strong>{task.title}</strong>
        <p>
          {task.category && <span className="task-cat-tag">{task.category}</span>}
          {task.courseName || '未分类'} · {task.taskType} · {task.difficulty}
        </p>
      </div>
      <span className={`badge ${task.status}`}>{statusLabel[task.status] ?? task.status}</span>
    </div>
  );
}

/**
 * 【宠物信息卡片组件】
 * Dashboard 首页展示的宠物概览卡片，包含：
 * - 宠物头像（支持自定义头像或精灵图动画）
 * - 名字、等级、成长阶段、情绪状态
 * - 经验值进度条
 * - 心情值显示
 *
 * 动画状态优先根据任务状态判断：有进行中任务 → running，有待审核任务 → review，
 * 否则根据宠物自身情绪状态映射。
 *
 * @param pet - 宠物数据对象（可为 null）
 * @param activeTaskCount - 进行中的任务数量
 * @param reviewTaskCount - 待审核的任务数量
 */
export function PetCard({ pet, activeTaskCount = 0, reviewTaskCount = 0 }: {
  pet: Pet | null;
  activeTaskCount?: number;
  reviewTaskCount?: number;
}) {
  const percent = pet ? Math.round((pet.currentExp / pet.nextLevelExp) * 100) : 0;
  const rawState = activeTaskCount > 0 ? 'running' : reviewTaskCount > 0 ? 'review' : animationForPet(pet);
  /** 【钳制到已解锁动作】未解锁的动作（如低等级的 running/review）回退 idle，不在卡片上播放 */
  const petState = clampAnimation(rawState, pet?.level ?? 1);
  return (
    <section className="panel pet-card">
      <div className="panel-title"><h2>宠物 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>成长</em></h2><PawPrint size={16} /></div>
      <div className="pet-visual">
        {pet?.avatarUrl
          ? <ClickableAvatar url={pet.avatarUrl} alt={`${pet.name ?? '宠物'} 头像`} imgStyle={{ width: 132, height: 132, objectFit: 'cover', borderRadius: '50%' }} />
          : <PetSprite state={petState} size={132} />}
      </div>
      <h3>{pet?.name ?? 'Soul'}</h3>
      <p className="muted">Lv.{pet?.level ?? 1} · {pet?.growthStage ?? 'EGG'} · {pet?.status ?? 'NORMAL'}</p>
      <div className="progress"><span style={{ width: `${percent}%` }} /></div>
      <div className="status-line"><span>经验</span><strong>{pet?.currentExp ?? 0}<small style={{ fontFamily: 'var(--mono)', color: 'var(--ink-3)', fontSize: 11, marginLeft: 4 }}>/{pet?.nextLevelExp ?? 100}</small></strong></div>
      <div className="status-line"><span>心情</span><strong>{pet?.mood ?? 80}</strong></div>
    </section>
  );
}

/**
 * 【空状态占位组件】
 * 列表为空时显示的引导信息，带毕业帽图标和提示文本。
 *
 * @param text - 提示文本
 */
export function Empty({ text }: { text: string }) {
  return <div className="empty"><GraduationCap size={24} /><span>{text}</span></div>;
}

/**
 * 【统计进度条组件】
 * 水平进度条，支持三种色调（primary/warm/cool）和自定义数值标签。
 * 用于统计页面展示各项指标的完成情况。
 *
 * @param label - 指标名称
 * @param value - 当前值
 * @param max - 最大值
 * @param tone - 色调，默认 'primary'
 * @param valueLabel - 自定义数值标签，默认显示 "value/max"
 */
export function StatBar({ label, value, max, tone = 'primary', valueLabel }: {
  label: string;
  value: number;
  max: number;
  tone?: 'primary' | 'warm' | 'cool';
  valueLabel?: string;
}) {
  const safeMax = Math.max(max, 1);
  const percent = Math.max(0, Math.min(100, Math.round((value / safeMax) * 100)));
  return (
    <div className={`stat-bar tone-${tone}`}>
      <div className="stat-bar-head">
        <span>{label}</span>
        <strong>{valueLabel ?? `${value}/${max}`}</strong>
      </div>
      <div className="stat-bar-track"><span style={{ width: `${percent}%` }} /></div>
    </div>
  );
}

/**
 * 【环形进度条组件】
 * SVG 实现的环形进度指示器，用于 PetPage 等页面展示经验值等指标。
 * 使用 strokeDasharray + strokeDashoffset 实现圆弧效果。
 *
 * @param value - 当前值
 * @param max - 最大值
 * @param size - 组件尺寸（宽高），默认 128px
 * @param stroke - 线条宽度，默认 12px
 * @param label - 中心主标签
 * @param sublabel - 中心副标签
 */
export function ProgressRing({ value, max, size = 128, stroke = 12, label, sublabel }: {
  value: number;
  max: number;
  size?: number;
  stroke?: number;
  label?: string;
  sublabel?: string;
}) {
  const safeMax = Math.max(max, 1);
  const clamped = Math.max(0, Math.min(value, safeMax));
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (clamped / safeMax) * circumference;
  return (
    <div className="progress-ring" style={{ width: size, height: size }}>
      <svg width={size} height={size}>
        <circle className="progress-ring-track" cx={size / 2} cy={size / 2} r={radius} fill="none" strokeWidth={stroke} />
        <circle
          className="progress-ring-fill"
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          strokeDasharray={circumference}
          strokeDashoffset={offset}
        />
      </svg>
      <div className="progress-ring-label">
        <div>
          <strong>{label ?? `${value}/${max}`}</strong>
          {sublabel && <span>{sublabel}</span>}
        </div>
      </div>
    </div>
  );
}

/**
 * 【顶部导航宠物芯片组件】
 * 悬浮导航条右侧的迷你出战宠物：精灵头像 + 名字 + 等级 + 微型经验条。
 * 悬停泛琥珀辉光，点击跳转宠物详情页；心情文案放 title 提示。
 *
 * @param pet - 宠物数据对象（可为 null）
 * @param onOpen - 点击跳转到宠物页的回调
 */
export function NavPet({ pet, onOpen }: { pet: Pet | null; onOpen: () => void }) {
  const percent = pet ? Math.min(100, Math.round((pet.currentExp / Math.max(pet.nextLevelExp, 1)) * 100)) : 0;
  const mood = petStatusLabel[pet?.status ?? 'NORMAL'] ?? '安静陪伴';
  const animState = clampAnimation(animationForPet(pet), pet?.level ?? 1);
  return (
    <button className="top-nav-pet" onClick={onOpen} title={`${pet?.name ?? 'Soul'} · ${mood}`}>
      <div className="top-nav-pet-frame">
        <PetSprite state={animState} size={26} />
      </div>
      <div className="top-nav-pet-meta">
        <strong>
          {pet?.name ?? 'Soul'}
          <span className="lvl-pill" style={{ marginLeft: 6 }}>Lv.{pet?.level ?? 1}</span>
        </strong>
        <div className="top-nav-pet-bar"><span style={{ width: `${percent}%` }} /></div>
      </div>
    </button>
  );
}
