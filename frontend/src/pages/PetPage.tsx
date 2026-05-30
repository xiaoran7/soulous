/**
 * 【宠物页面】PetPage
 * 本页面展示宠物的详细信息和互动功能：
 * - 宠物卡片：等级、阶段、状态、经验值、心情、饱食度
 * - 成长反馈：宠物状态文案、动画预览
 * - 喂食功能：增加饱食度
 * - 头像管理：上传/清除宠物自定义头像
 * - 经验趋势图：近 7 天的经验获取趋势
 * - 动作预览：预览宠物的不同动画状态
 * - 成长事件日志：展示宠物的成长历史记录
 *
 * 设计思路：宠物是学习成果的可视化载体，通过等级、心情、饱食度等维度
 * 反映用户的学习状态。喂食和头像管理增加互动性和个性化。
 */
import React, { Suspense, lazy, useEffect, useState } from 'react';
import { Cookie, PawPrint, RefreshCw, TrendingUp, Upload, X } from 'lucide-react';
import { api } from '../api';
import { PetSprite } from '../PetSprite';
import type { PetAnimationState } from '../PetSprite';
import type { ExpLog, Pet } from '../types';
import { ClickableAvatar, Empty, PetCard, StatBar, animationForPet } from '../components/shared';

/** 【懒加载经验趋势图】仅在滚动到图表区域时加载 */
const ExpTrendChart = lazy(() => import('../components/PetCharts'));

/**
 * 【宠物状态文案映射】每种宠物状态对应一个标题和描述文案
 * 用于在页面上展示宠物当前的情感状态和对用户的建议
 */
const petStatusCopy: Record<string, { title: string; body: string }> = {
  NORMAL: { title: '安静陪伴中', body: 'Feixue 正在待机，等你把下一件学习任务推进一点。' },
  WORKING: { title: '正在工作', body: 'Feixue 已进入工作状态，陪你把当前任务推进到底。' },
  REVIEWING: { title: '等待复核', body: '提交已经送去审核，Feixue 正在认真等结果。' },
  HAPPY: { title: '心情不错', body: '最近的完成记录让她很开心，适合继续保持节奏。' },
  PROUD: { title: '为你骄傲', body: '这次提交很扎实，她已经把这份成长记下来了。' },
  EXCITED: { title: '升级能量满格', body: '经验突破带来了新的成长阶段，适合趁热打铁。' },
  SLEEPY: { title: '耐心等待', body: '她在安静等你回来，补一个小任务就能重新热起来。' },
  SAD: { title: '需要修正', body: '有凭证需要补充或被打回，先把反馈处理掉会更稳。' }
};

/**
 * 【成长事件类型标签映射】将后端事件类型转换为中文标签
 * 用于成长事件日志的展示
 */
const petEventLabels: Record<string, string> = {
  TASK_STARTED: '开始任务',
  SUBMITTED_FOR_REVIEW: '提交复核',
  EXP_GAINED: '获得经验',
  NEEDS_MORE: '需要补充',
  REJECTED: '未通过'
};

/**
 * 【动作预览配置】定义宠物的 5 种动画状态预览
 * 用户点击可切换主展示区的动画
 */
const petActionPreviews: { state: PetAnimationState; label: string; description: string }[] = [
  { state: 'idle', label: '待机', description: '日常陪伴' },
  { state: 'running', label: '工作', description: '任务进行中' },
  { state: 'review', label: '复核', description: '等待审核' },
  { state: 'waving', label: '开心', description: '获得认可' },
  { state: 'failed', label: '低落', description: '需要补充' }
];

/**
 * 【成长事件标题】生成事件日志的标题文本
 * @param log - 经验日志记录
 * @returns 格式化的标题，如 "获得经验 · 复习二叉树"
 */
function petEventTitle(log: ExpLog) {
  const label = petEventLabels[log.eventType ?? ''] ?? '成长事件';
  return log.task?.title ? `${label} · ${log.task.title}` : label;
}

/**
 * 【格式化日期时间】将 ISO 日期字符串转换为中文友好的短格式
 * @param value - ISO 日期字符串
 * @returns 格式化的日期时间，如 "05/27 14:30"
 */
function formatDateTime(value: string) {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/**
 * 【宠物页面主组件】
 * @param pet - 初始宠物数据（从父组件传入）
 * @param onFed - 喂食/更新后的回调，通知父组件同步宠物状态
 *
 * 核心状态：
 * - pet: 宠物数据（本地维护，支持喂食和头像更新后立即反映）
 * - previewState: 当前预览的动画状态
 * - logs: 成长事件日志
 * - feeding/avatarUploading: 操作忙碌状态
 */
export function PetPage({ pet: initialPet, onFed }: { pet: Pet | null; onFed?: (pet: Pet) => void }) {
  const [pet, setPet] = useState<Pet | null>(initialPet);
  /** 【宠物动画状态】根据宠物数据自动计算的动画状态 */
  const petState = animationForPet(pet);
  /** 【预览动画状态】用户手动选择的预览状态，默认跟随宠物状态 */
  const [previewState, setPreviewState] = useState<PetAnimationState>(petState);
  /** 【成长事件日志】宠物的经验获取历史 */
  const [logs, setLogs] = useState<ExpLog[]>([]);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [feeding, setFeeding] = useState(false);
  const [feedError, setFeedError] = useState('');
  const [logsError, setLogsError] = useState('');
  const [avatarUploading, setAvatarUploading] = useState(false);
  const [avatarError, setAvatarError] = useState('');
  /** 【状态文案】根据当前宠物状态获取对应的标题和描述 */
  const statusCopy = petStatusCopy[pet?.status ?? 'NORMAL'] ?? petStatusCopy.NORMAL;

  /**
   * 【同步父组件数据】当父组件的 pet 数据变化时更新本地状态
   * 用于响应其他页面（如任务提交）导致的宠物状态变化
   *
   * sync when parent refreshes
   */
  React.useEffect(() => { setPet(initialPet); }, [initialPet]);

  /** 【同步预览状态】当宠物状态变化时，更新预览动画 */
  useEffect(() => { setPreviewState(petState); }, [petState]);

  /**
   * 【加载成长事件日志】获取宠物的经验获取历史
   * 用于展示成长事件列表和经验趋势图
   */
  async function loadLogs() {
    setLoadingLogs(true);
    setLogsError('');
    try {
      const data = await api.petLogs();
      setLogs(Array.isArray(data) ? data : []);
    } catch (err) {
      setLogsError(err instanceof Error ? err.message : '加载成长事件失败');
    } finally {
      setLoadingLogs(false);
    }
  }

  useEffect(() => { void loadLogs(); }, []);

  /**
   * 【上传宠物头像】两步流程：
   * 1. 上传图片文件到服务器，获取 URL
   * 2. 调用 setPetAvatar 设置宠物头像
   * 成功后更新本地宠物状态并通知父组件
   */
  async function uploadAvatar(file?: File) {
    if (!file) return;
    setAvatarError('');
    setAvatarUploading(true);
    try {
      const result = await api.uploadScreenshot(file);
      const updated = await api.setPetAvatar(result.url) as Pet;
      setPet(updated);
      onFed?.(updated);
    } catch (err) {
      setAvatarError(err instanceof Error ? err.message : '上传宠物头像失败');
    } finally {
      setAvatarUploading(false);
    }
  }

  /**
   * 【清除宠物头像】将头像重置为默认（使用 PetSprite 动画）
   * 传入 null 表示清除自定义头像
   */
  async function clearAvatar() {
    setAvatarError('');
    setAvatarUploading(true);
    try {
      const updated = await api.setPetAvatar(null) as Pet;
      setPet(updated);
      onFed?.(updated);
    } catch (err) {
      setAvatarError(err instanceof Error ? err.message : '清除头像失败');
    } finally {
      setAvatarUploading(false);
    }
  }

  /**
   * 【喂食宠物】增加宠物的饱食度
   * 成功后更新本地宠物状态并通知父组件
   */
  async function feed() {
    setFeedError('');
    setFeeding(true);
    try {
      const updated = await api.feedPet() as Pet;
      setPet(updated);
      onFed?.(updated);
    } catch (err) {
      setFeedError(err instanceof Error ? err.message : '喂食失败');
    } finally {
      setFeeding(false);
    }
  }

  return (
    <div className="two-column">
      {/* 【宠物卡片】左侧的综合宠物信息卡片 */}
      <PetCard pet={pet} />

      {/* ===== 【成长反馈面板】宠物状态、动画、属性、喂食 ===== */}
      <section className="panel pet-stage-panel">
        <div className="panel-title"><h2>成长 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>反馈</em></h2></div>
        <div className="pet-stage">
          {/* 【宠物形象展示区】头像或动画 + 头像管理按钮 */}
          <div className="pet-figure">
            <div className="pet-avatar-frame">
              {pet?.avatarUrl ? (
                <ClickableAvatar url={pet.avatarUrl} alt={`${pet.name ?? '宠物'} 头像`} />
              ) : (
                <PetSprite state={previewState} size={176} />
              )}
            </div>
            {/* 【头像操作按钮】上传/更换/恢复默认 */}
            <div className="pet-avatar-actions">
              <label className={`upload-control${avatarUploading ? ' disabled' : ''}`}>
                <Upload size={14} />
                <span>{avatarUploading ? '上传中...' : pet?.avatarUrl ? '更换头像' : '上传宠物头像'}</span>
                <input type="file" accept="image/*" disabled={avatarUploading} onChange={(e) => void uploadAvatar(e.target.files?.[0])} />
              </label>
              {pet?.avatarUrl && (
                <button className="secondary-button compact-button" onClick={() => void clearAvatar()} disabled={avatarUploading}>
                  <X size={14} /> 恢复默认
                </button>
              )}
            </div>
            {avatarError && <div className="form-error" style={{ marginTop: 6 }}>{avatarError}</div>}
          </div>

          {/* 【状态文案】展示宠物当前的情感状态 */}
          <div className="pet-state-copy">
            <span>{statusCopy.title}</span>
            <p>{statusCopy.body}</p>
          </div>

          {/* 【宠物属性网格】等级、阶段、状态 */}
          <div className="pet-state-grid">
            <div><span>等级</span><strong>Lv.{pet?.level ?? 1}</strong></div>
            <div><span>阶段</span><strong>{pet?.growthStage ?? 'EGG'}</strong></div>
            <div><span>状态</span><strong>{pet?.status ?? 'NORMAL'}</strong></div>
          </div>

          {/* 【属性进度条】经验、心情、饱食度 */}
          <div className="pet-stat-bars">
            <StatBar
              label="经验"
              value={pet?.currentExp ?? 0}
              max={pet?.nextLevelExp ?? 100}
              tone="primary"
              valueLabel={`${pet?.currentExp ?? 0} / ${pet?.nextLevelExp ?? 100}`}
            />
            <StatBar
              label="心情"
              value={pet?.mood ?? 0}
              max={100}
              tone="warm"
              valueLabel={`${pet?.mood ?? 0}`}
            />
            <StatBar
              label="饱食"
              value={pet?.satiety ?? 0}
              max={100}
              tone="cool"
              valueLabel={`${pet?.satiety ?? 0}`}
            />
          </div>

          {/* 【喂食按钮】点击增加 20 饱食度 */}
          <button className="secondary-button" onClick={feed} disabled={feeding} style={{ marginTop: 12 }}>
            <Cookie size={16} />{feeding ? '喂食中...' : '喂食 (+20 饱食)'}
          </button>
          {feedError && <div className="form-error" style={{ marginTop: 8 }}>{feedError}</div>}
        </div>
      </section>

      {/* ===== 【经验趋势图】近 7 天的经验获取趋势 ===== */}
      <section className="panel full">
        <div className="panel-title"><h2>近 7 天 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>经验趋势</em></h2><TrendingUp size={16} /></div>
        <Suspense fallback={<div className="muted">加载图表中...</div>}>
          <ExpTrendChart logs={logs} days={7} />
        </Suspense>
      </section>

      {/* ===== 【动作预览】点击切换主展示区的动画状态 ===== */}
      <section className="panel full">
        <div className="panel-title"><h2>动作 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>预览</em></h2></div>
        <div className="pet-action-grid">
          {petActionPreviews.map((action) => (
            <button
              key={action.state}
              className={`pet-action ${previewState === action.state ? 'selected' : ''}`}
              onClick={() => setPreviewState(action.state)}
            >
              <PetSprite state={action.state} size={82} label={`Feixue ${action.label}`} />
              <strong>{action.label}</strong>
              <span>{action.description}</span>
            </button>
          ))}
        </div>
      </section>

      {/* ===== 【成长事件日志】展示宠物的经验获取历史 ===== */}
      <section className="panel full">
        <div className="panel-title">
          <h2>成长 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>事件</em></h2>
          <button className="icon-button" onClick={loadLogs} title="刷新经验记录"><RefreshCw size={14} /></button>
        </div>
        {logsError && <div className="form-error">{logsError}</div>}
        <div className="pet-log-list">
          {logs.slice(0, 8).map((log) => (
            <div className="pet-log-item" key={log.id}>
              <div>
                <strong>{petEventTitle(log)}</strong>
                <p className="muted">{formatDateTime(log.createdAt)} · {log.reason || '成长事件'}</p>
              </div>
              {/* 【经验值变化】正数显示 +N，否则显示事件标签 */}
              <span className={log.expAmount > 0 ? '' : 'neutral'}>
                {log.expAmount > 0 ? `+${log.expAmount}` : petEventLabels[log.eventType ?? ''] ?? '反馈'}
              </span>
            </div>
          ))}
          {logs.length === 0 && (
            <Empty text={loadingLogs ? '正在读取成长事件...' : '还没有成长事件，开始一次学习任务后会出现在这里。'} />
          )}
        </div>
      </section>
    </div>
  );
}
