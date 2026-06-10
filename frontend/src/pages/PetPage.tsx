/**
 * 【宠物页面】PetPage
 * 本页面展示宠物的详细信息和互动功能，三个模块自上而下：
 * - 成长档案（合并原「宠物成长」+「成长反馈」）：身份信息（头像/动画、名字、
 *   等级阶段状态、头像管理）+ 状态文案 + 经验/心情/饱食进度条 + 喂食
 * - 成长动态（合并原「经验趋势」+「动作预览」）：近 7 天经验趋势图 + 动作预览
 * - 成长事件日志：展示宠物的成长历史记录
 *
 * 设计思路：宠物是学习成果的可视化载体，通过等级、心情、饱食度等维度
 * 反映用户的学习状态。喂食和头像管理增加互动性和个性化。原本「宠物成长」
 * 卡片与「成长反馈」面板在身份信息上高度重合，已合并为单一成长档案模块。
 */
import React, { Suspense, lazy, useEffect, useState } from 'react';
import { Cookie, Lock, RefreshCw, Store, TrendingUp } from 'lucide-react';
import { api } from '../api';
import { PetSprite } from '../PetSprite';
import type { PetAnimationState } from '../PetSprite';
import type { ExpLog, Pet } from '../types';
import { Empty, StatBar, animationForPet, clampAnimation, PET_ACTION_UNLOCK_LEVEL } from '../components/shared';
import { PetMarket } from '../components/PetMarket';

/** 【懒加载经验趋势图】仅在滚动到图表区域时加载 */
const ExpTrendChart = lazy(() => import('../components/PetCharts'));

/**
 * 【宠物成长日志模块级缓存】记住上次拉取的成长事件，跨页面切回宠物页时先渲染旧数据、
 * 后台静默刷新，消除每次重进的列表/图表卡顿。登出由 resetPetLogsCache() 清空。
 */
let petLogsCache: ExpLog[] | null = null;
export function resetPetLogsCache() { petLogsCache = null; }

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
 * 【动作解锁配置】宠物全部 9 个动画动作，各自有解锁等级。
 * 升级逐步解锁，满级（Lv30）解锁全部——最后一个动作恰好在满级解锁。
 * 未达解锁等级的动作在预览区显示为锁定态，不可选中。
 */
const PET_ACTIONS: { state: PetAnimationState; label: string; description: string; unlockLevel: number }[] = [
  { state: 'idle', label: '待机', description: '日常陪伴', unlockLevel: PET_ACTION_UNLOCK_LEVEL.idle },
  { state: 'waiting', label: '等待', description: '耐心等你', unlockLevel: PET_ACTION_UNLOCK_LEVEL.waiting },
  { state: 'waving', label: '招呼', description: '挥手问好', unlockLevel: PET_ACTION_UNLOCK_LEVEL.waving },
  { state: 'failed', label: '低落', description: '需要补充', unlockLevel: PET_ACTION_UNLOCK_LEVEL.failed },
  { state: 'running', label: '奔跑', description: '元气满满', unlockLevel: PET_ACTION_UNLOCK_LEVEL.running },
  { state: 'running-right', label: '右奔', description: '向右冲刺', unlockLevel: PET_ACTION_UNLOCK_LEVEL['running-right'] },
  { state: 'running-left', label: '左奔', description: '向左冲刺', unlockLevel: PET_ACTION_UNLOCK_LEVEL['running-left'] },
  { state: 'jumping', label: '跳跃', description: '兴奋雀跃', unlockLevel: PET_ACTION_UNLOCK_LEVEL.jumping },
  { state: 'review', label: '复核', description: '专注审阅', unlockLevel: PET_ACTION_UNLOCK_LEVEL.review }
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
export function PetPage({ pet: initialPet, onFed, onRefresh }: { pet: Pet | null; onFed?: (pet: Pet) => void; onRefresh?: () => void }) {
  const [pet, setPet] = useState<Pet | null>(initialPet);
  /** 【宠物市场弹层开关】 */
  const [marketOpen, setMarketOpen] = useState(false);
  /** 【宠物动画状态】根据宠物数据自动计算的动画状态 */
  const petState = animationForPet(pet);
  /** 【当前等级】驱动动作解锁判断 */
  const petLevel = pet?.level ?? 1;
  /**
   * 【安全的展示动画状态】宠物的自然状态可能映射到尚未解锁的动作
   * （例如 Lv2 心情 HAPPY 会映射到 Lv3 才解锁的 waving）。此时回退到始终可用的 idle，
   * 避免主展示区播放未解锁的动作。
   */
  const safePetState = clampAnimation(petState, petLevel);
  /** 【预览动画状态】用户手动选择的预览状态，默认跟随宠物状态（已钳制到已解锁动作） */
  const [previewState, setPreviewState] = useState<PetAnimationState>(safePetState);
  /** 【成长事件日志】宠物的经验获取历史，初值从模块缓存 seed */
  const [logs, setLogs] = useState<ExpLog[]>(() => petLogsCache ?? []);
  const [loadingLogs, setLoadingLogs] = useState(petLogsCache === null);
  const [feeding, setFeeding] = useState(false);
  const [feedError, setFeedError] = useState('');
  const [logsError, setLogsError] = useState('');
  /** 【状态文案】根据当前宠物状态获取对应的标题和描述 */
  const statusCopy = petStatusCopy[pet?.status ?? 'NORMAL'] ?? petStatusCopy.NORMAL;
  /** 【已解锁动作数】用于动作区小标题展示 X/9 */
  const unlockedCount = PET_ACTIONS.filter((a) => a.unlockLevel <= petLevel).length;

  /** 【市场弹层】领养/购买/切换出战后刷新全局数据 */
  const marketModal = marketOpen && (
    <PetMarket onClose={() => setMarketOpen(false)} onChanged={() => { onRefresh?.(); }} />
  );

  // 未领养任何宠物：展示领养引导，去市场领养首只
  if (!pet) {
    return (
      <div className="page-stack">
        {marketModal}
        <section className="panel pet-empty">
          <div className="pet-empty-frame"><PetSprite state="waving" size={120} /></div>
          <h2>领养你的第一只伙伴</h2>
          <p className="muted">完成学习任务、每日打卡能赚取金币。先去宠物市场免费领养一只入门伙伴，陪你一起成长吧。</p>
          <button className="primary-button" onClick={() => setMarketOpen(true)}>
            <Store size={15} /> 去宠物市场
          </button>
        </section>
      </div>
    );
  }

  /**
   * 【同步父组件数据】当父组件的 pet 数据变化时更新本地状态
   * 用于响应其他页面（如任务提交）导致的宠物状态变化
   *
   * sync when parent refreshes
   */
  React.useEffect(() => { setPet(initialPet); }, [initialPet]);

  /** 【同步预览状态】当宠物状态变化时，更新预览动画（钳制到已解锁动作，未解锁回退 idle） */
  useEffect(() => { setPreviewState(safePetState); }, [safePetState]);

  /**
   * 【加载成长事件日志】获取宠物的经验获取历史
   * 用于展示成长事件列表和经验趋势图
   */
  async function loadLogs() {
    setLoadingLogs(true);
    setLogsError('');
    try {
      const data = await api.petLogs();
      const next = Array.isArray(data) ? data : [];
      petLogsCache = next;
      setLogs(next);
    } catch (err) {
      setLogsError(err instanceof Error ? err.message : '加载成长事件失败');
    } finally {
      setLoadingLogs(false);
    }
  }

  useEffect(() => { void loadLogs(); }, []);

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
    <div className="page-stack">
      {marketModal}
      {/* ===== 【成长档案】合并「宠物成长」与「成长反馈」：身份信息 + 状态属性 + 喂食 ===== */}
      <section className="panel pet-profile full">
        <div className="panel-title">
          <h2>宠物 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>成长档案</em></h2>
          <button className="ghost-button small" onClick={() => setMarketOpen(true)} title="宠物市场">
            <Store size={14} /> 宠物市场
          </button>
        </div>
        <div className="pet-profile-body">
          {/* 【身份列】品种动画 + 名字 + 等级阶段状态。形象由所购品种决定，不支持自定义头像。 */}
          <div className="pet-profile-identity">
            <div className="pet-avatar-frame">
              <PetSprite state={previewState} size={140} sheet={pet?.species?.spritePath} />
            </div>
            <h3>{pet?.name ?? 'Soul'}</h3>
            {/* 【宠物属性网格】等级、阶段、状态 */}
            <div className="pet-state-grid">
              <div><span>等级</span><strong>Lv.{pet?.level ?? 1}</strong></div>
              <div><span>阶段</span><strong>{pet?.growthStage ?? 'EGG'}</strong></div>
              <div><span>状态</span><strong>{pet?.status ?? 'NORMAL'}</strong></div>
            </div>
          </div>

          {/* 【反馈列】状态文案 + 属性进度条 + 喂食 */}
          <div className="pet-profile-detail">
            {/* 【状态文案】展示宠物当前的情感状态 */}
            <div className="pet-state-copy">
              <span>{statusCopy.title}</span>
              <p>{statusCopy.body}</p>
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
            <button className="secondary-button" onClick={feed} disabled={feeding} style={{ marginTop: 4, alignSelf: 'flex-start' }}>
              <Cookie size={16} />{feeding ? '喂食中...' : '喂食 (+20 饱食)'}
            </button>
            {feedError && <div className="form-error" style={{ marginTop: 8 }}>{feedError}</div>}
          </div>
        </div>

        {/* ===== 【成长动态】合并「经验趋势」+「动作预览」，并入成长档案 ===== */}
        <div className="pet-dynamics">
          {/* 【经验趋势图】近 7 天的经验获取趋势 */}
          <div className="pet-dynamics-block">
            <div className="pet-subhead"><TrendingUp size={13} /> 近 7 天经验趋势</div>
            <Suspense fallback={<div className="muted">加载图表中...</div>}>
              <ExpTrendChart logs={logs} days={7} />
            </Suspense>
          </div>

          {/* 【动作解锁】点击切换主展示区动画；未达等级的动作锁定，升级解锁，满级全开 */}
          <div className="pet-dynamics-block">
            <div className="pet-subhead">动作解锁（{unlockedCount}/{PET_ACTIONS.length}）</div>
            <div className="pet-action-grid">
              {PET_ACTIONS.map((action) => {
                const locked = action.unlockLevel > petLevel;
                return (
                  <button
                    key={action.state}
                    className={`pet-action ${previewState === action.state ? 'selected' : ''}${locked ? ' locked' : ''}`}
                    onClick={() => { if (!locked) setPreviewState(action.state); }}
                    disabled={locked}
                    title={locked ? `Lv.${action.unlockLevel} 解锁` : action.description}
                  >
                    <PetSprite state={locked ? 'idle' : action.state} size={52} sheet={pet?.species?.spritePath} label={`${pet?.name ?? 'Soul'} ${action.label}`} />
                    <strong>{action.label}</strong>
                    <span>{locked ? `Lv.${action.unlockLevel} 解锁` : action.description}</span>
                    {locked && <span className="pet-action-lock" aria-hidden="true"><Lock size={12} /></span>}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </section>

      {/* ===== 【成长事件日志】仿任务列表（task-card）样式展示成长历史 ===== */}
      <section className="panel full">
        <div className="panel-title">
          <h2>成长 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>事件</em></h2>
          <button className="icon-button" onClick={loadLogs} title="刷新经验记录"><RefreshCw size={14} /></button>
        </div>
        {logsError && <div className="form-error">{logsError}</div>}
        <div className="task-list">
          {logs.slice(0, 6).map((log) => (
            <div className="task-card" key={log.id}>
              <div className="task-row">
                <div>
                  <strong>{petEventTitle(log)}</strong>
                  <p>{formatDateTime(log.createdAt)} · {log.reason || '成长事件'}</p>
                </div>
                {/* 【经验值变化】正数显示 +N EXP 徽章，否则显示事件标签 */}
                <span className={`badge ${log.expAmount > 0 ? 'badge-completed' : ''}`}>
                  {log.expAmount > 0 ? `+${log.expAmount} EXP` : petEventLabels[log.eventType ?? ''] ?? '反馈'}
                </span>
              </div>
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
