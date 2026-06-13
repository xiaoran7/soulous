/**
 * 【工作台主页】Dashboard
 * 高保真还原 design/stitch/soulous_4：固定单屏的三卡布局，弃用此前 7 段纵向堆叠。
 * - 问候卡（左大）：时段问候 + 今日经验/连续打卡 + 签到 + 复盘入口
 * - 今日概览卡（右）：完成度环 + 学习时长/任务/专注 + 学习目标进度
 * - 近 7 天趋势卡（整宽底部）：学习时长趋势图
 *
 * 设计取舍（用户确认「严格照 stitch 精简」）：最近任务、审核状态、6 宫格指标、
 * 独立签到卡均已并入上述三卡或迁回任务页，确保整屏不滚动。
 */
import React, { Suspense, lazy, useEffect, useState } from 'react';
import { CalendarCheck, Coins, Flame, Sparkles } from 'lucide-react';
import { api } from '../api';
import type { Pet, StudyTask, Summary } from '../types';
import { PetSprite } from '../PetSprite';
import { ProgressRing, animationForPet, petStatusLabel, petNick } from '../components/shared';

/** 【懒加载趋势图组件】 */
const TrendChart = lazy(() => import('../components/TrendChart'));

/**
 * 【签到模块级缓存】记住今日打卡状态与已领奖励，跨页面切回工作台时直接渲染、
 * 后台静默刷新，消除每次重进的局部 loading。登出由 resetCheckinCache() 清空。
 */
type CheckinSnapshot = { checkedInToday: boolean; streak: number; balance: number };
let checkinCache: { status: CheckinSnapshot; reward: { expReward: number; coinReward: number } | null } | null = null;
export function resetCheckinCache() { checkinCache = null; }

/** 【时段问候】按本地时间返回 早上好/下午好/晚上好 */
function greetingForNow(): string {
  const h = new Date().getHours();
  if (h < 11) return '早上好！';
  if (h < 14) return '中午好！';
  if (h < 18) return '下午好！';
  return '晚上好！';
}

/**
 * 【签到 hook】查询今日打卡状态，未签到则可领奖；领取后用 POST 响应里的宠物快照
 * 局部刷新（onPetSync），不整页重拉接口。供问候卡内联使用。
 */
function useCheckin(onPetSync: (pet: Pet) => void) {
  const [status, setStatus] = useState<CheckinSnapshot | null>(() => checkinCache?.status ?? null);
  const [reward, setReward] = useState<{ expReward: number; coinReward: number } | null>(() => checkinCache?.reward ?? null);
  const [claiming, setClaiming] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const s = await api.checkinStatus();
        if (cancelled) return;
        setStatus(s);
        checkinCache = { status: s, reward: checkinCache?.reward ?? null };
      } catch { /* 未登录或网络异常时静默 */ }
    })();
    return () => { cancelled = true; };
  }, []);

  async function claim() {
    if (claiming || status?.checkedInToday) return;
    setClaiming(true);
    setError('');
    try {
      const r = await api.checkin();
      const nextReward = { expReward: r.expReward, coinReward: r.coinReward };
      const nextStatus = { checkedInToday: true, streak: r.streak, balance: r.balance };
      setReward(nextReward);
      setStatus(nextStatus);
      checkinCache = { status: nextStatus, reward: nextReward };
      if (r.pet) onPetSync(r.pet);
    } catch (err) {
      setError(err instanceof Error ? err.message : '签到失败');
    } finally {
      setClaiming(false);
    }
  }

  return { status, reward, claiming, error, claim };
}

/**
 * 【工作台主组件】
 * 数据来源：所有数据从父组件（App）传入，Dashboard 本身只发起签到相关请求。
 */
export function Dashboard({ pet, summary, onPetSync, onOpenReview, onOpenPet }: {
  tasks: StudyTask[];
  pet: Pet | null;
  summary: Summary | null;
  onRefresh: () => void;
  onPetSync: (pet: Pet) => void;
  onOpenTasks: () => void;
  onOpenReview: () => void;
  onOpenPet: () => void;
}) {
  const { status, reward, claiming, error, claim } = useCheckin(onPetSync);

  /** 【今日完成度】提交次数 / 今日任务数 */
  const todayTasks = summary?.todayTasks ?? 0;
  const todayDone = summary?.todaySubmissions ?? 0;
  const ringMax = Math.max(todayTasks, todayDone, 1);
  const donePercent = Math.round((todayDone / ringMax) * 100);

  /** 【学习目标】今日学时对日目标（默认 120 分）的进度 */
  const DAILY_GOAL = 120;
  const todayMinutes = summary?.todayMinutes ?? 0;
  const goalPercent = Math.min(100, Math.round((todayMinutes / DAILY_GOAL) * 100));

  /** 【连续天数】优先用签到 streak，回退 summary.consecutiveDays */
  const streak = status?.streak ?? summary?.consecutiveDays ?? 0;
  const checkedIn = status?.checkedInToday ?? false;

  /** 【宠物心情标签】 */
  const petMood = petStatusLabel[pet?.status ?? 'NORMAL'] ?? '安静陪伴';

  return (
    <div className="wb-grid">
      {/* ===== 问候卡（soulous_4 左大卡）===== */}
      <section className="panel wb-greeting">
        <div className="wb-greeting-main">
          <p className="wb-eyebrow">FOCUS · LEARN · GROW</p>
          <h2>{greetingForNow()}</h2>
          <p className="wb-greeting-sub">今天也要专注学习哦，把凭证交给 AI，宠物会陪你一起成长。</p>

          <div className="wb-reward-row">
            <span className="wb-reward-chip"><Sparkles size={14} /> +{summary?.todayExp ?? 0} XP</span>
            <span className="wb-reward-chip"><Flame size={14} /> 连续打卡 {streak} 天</span>
            <span className="wb-reward-chip"><Coins size={14} /> {status?.balance ?? 0}</span>
          </div>

          <div className="wb-greeting-actions">
            <button className="le-primary-button" disabled={checkedIn || claiming} onClick={() => void claim()}>
              {checkedIn ? '已签到 ✓' : claiming ? '签到中…' : '签到领取'}
            </button>
            <button className="le-ghost-button" onClick={onOpenReview}>
              <CalendarCheck size={15} /> 今日复盘
            </button>
          </div>
          {checkedIn && reward && (
            <p className="wb-reward-note">已领 +{reward.expReward} 经验 · +{reward.coinReward} 金币，明天继续保持连击。</p>
          )}
          {error && <p className="wb-reward-note error">{error}</p>}
        </div>

        {/* 出战宠物气泡：点击进宠物页 */}
        <button className="wb-pet" onClick={onOpenPet} title={`${petNick(pet)} · ${petMood}`}>
          <PetSprite state={animationForPet(pet)} size={84} sheet={pet?.species?.spritePath} />
          <div className="wb-pet-meta">
            <strong>{petNick(pet)}</strong>
            <span>Lv.{pet?.level ?? 1} · {petMood}</span>
          </div>
        </button>
      </section>

      {/* ===== 今日概览卡（soulous_4 右卡）===== */}
      <section className="panel wb-overview">
        <div className="panel-title"><h2>今日 <em>概览</em></h2></div>
        <div className="wb-overview-body">
          <ProgressRing value={todayDone} max={ringMax} size={104} stroke={11} label={`${donePercent}%`} sublabel="完成度" />
          <div className="wb-overview-stats">
            <div><span>学习时长</span><strong>{todayMinutes}<small>分</small></strong></div>
            <div><span>今日任务</span><strong>{todayTasks}</strong></div>
            <div><span>专注时长</span><strong>{summary?.todayFocusMinutes ?? 0}<small>分</small></strong></div>
          </div>
        </div>
        <div className="wb-goal">
          <div className="wb-goal-head"><span>学习目标</span><strong>{todayMinutes} / {DAILY_GOAL} 分</strong></div>
          <div className="wb-goal-bar"><i style={{ width: `${goalPercent}%` }} /></div>
        </div>
      </section>

      {/* ===== 近 7 天趋势卡（soulous_4 底部整宽）===== */}
      <section className="panel wb-trend">
        <div className="panel-title"><h2>近 7 天 <em>学习趋势</em></h2></div>
        <div className="wb-trend-chart">
          <Suspense fallback={<div className="muted">加载图表中...</div>}>
            <TrendChart data={summary?.trend ?? []} />
          </Suspense>
        </div>
      </section>
    </div>
  );
}
