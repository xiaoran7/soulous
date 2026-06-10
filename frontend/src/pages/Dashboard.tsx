/**
 * 【工作台主页】Dashboard
 * 本页面是 Soulous 的核心首页，提供学习状态的全局概览：
 * - Hero 区域：今日打卡进度环 + 连续天数/经验/学时统计 + 宠物状态
 * - 指标行：今日任务数、提交次数、经验、学时、专注分钟、专注次数
 * - 最近任务：展示最近 5 个任务的状态
 * - 审核状态：已完成/被拒绝/需要补充的任务数量统计
 * - 学习趋势：近 7 天的学习时长趋势图
 * - 复盘入口：跳转到 AI 每日复盘页面
 *
 * 设计思路：Dashboard 是用户的"仪表盘"，一屏展示所有关键信息，
 * 引导用户进入任务、复盘、宠物等子页面。宠物气泡提供情感化陪伴。
 */
import React, { Suspense, lazy, useEffect, useState } from 'react';
import { CalendarCheck, CheckCircle2, ChevronRight, ClipboardList, Coins, Flame, RefreshCw, Sparkles, Target, Timer } from 'lucide-react';
import { api } from '../api';
import type { Pet, StudyTask, Summary } from '../types';
import { PetSprite } from '../PetSprite';
import {
  Empty,
  Metric,
  ProgressRing,
  TaskRow,
  animationForPet,
  petStatusLabel,
  statusLabel
} from '../components/shared';

/** 【懒加载趋势图组件】仅在滚动到趋势区域时加载 */
const TrendChart = lazy(() => import('../components/TrendChart'));

/**
 * 【签到模块级缓存】记住今日打卡状态与已领奖励，跨页面切回工作台时直接渲染、
 * 后台静默刷新，消除每次重进的局部 loading。登出由 resetCheckinCache() 清空。
 */
type CheckinSnapshot = { checkedInToday: boolean; streak: number; balance: number };
let checkinCache: { status: CheckinSnapshot; reward: { expReward: number; coinReward: number } | null } | null = null;
export function resetCheckinCache() { checkinCache = null; }

/**
 * 【每日签到卡片】自包含：挂载查询今日打卡状态，未签到则可领奖。
 * 领取后用 POST 响应里的宠物快照局部刷新（onPetSync），不再整页重拉接口。
 * @param onPetSync - 领奖成功后同步出战宠物快照到全局 state（宠物经验/等级即时更新）
 */
function CheckinCard({ onPetSync }: { onPetSync: (pet: Pet) => void }) {
  const [status, setStatus] = useState<CheckinSnapshot | null>(() => checkinCache?.status ?? null);
  const [claiming, setClaiming] = useState(false);
  const [reward, setReward] = useState<{ expReward: number; coinReward: number } | null>(() => checkinCache?.reward ?? null);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const s = await api.checkinStatus();
        if (cancelled) return;
        setStatus(s);
        checkinCache = { status: s, reward: checkinCache?.reward ?? null };
      } catch { /* 未登录或网络异常时静默，不打扰工作台 */ }
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
      // 用响应里的宠物快照局部刷新全局 state，避免重拉 me/tasks/pet/summary
      if (r.pet) onPetSync(r.pet);
    } catch (err) {
      setError(err instanceof Error ? err.message : '签到失败');
    } finally {
      setClaiming(false);
    }
  }

  if (!status) return null;
  const done = status.checkedInToday;

  return (
    <section className="panel checkin-card">
      <div className="checkin-main">
        <div className="checkin-streak" title="连续打卡天数">
          <Flame size={18} /> <strong>{status.streak}</strong><span>天</span>
        </div>
        <div className="checkin-copy">
          <h3>{done ? '今日已签到' : '每日签到'}</h3>
          <p>
            {done
              ? (reward ? `已领 +${reward.expReward} 经验 · +${reward.coinReward} 金币，明天继续保持连击。` : '今天已经领过啦，明天再来。')
              : '每天签到领经验和金币，连续越久奖励越高。'}
          </p>
        </div>
      </div>
      <div className="checkin-actions">
        <span className="checkin-balance"><Coins size={14} /> {status.balance}</span>
        <button className="primary-button" disabled={done || claiming} onClick={() => void claim()}>
          {done ? '已签到 ✓' : claiming ? '签到中…' : '签到领取'}
        </button>
      </div>
      {error && <div className="form-error" style={{ flexBasis: '100%' }}>{error}</div>}
    </section>
  );
}

/**
 * 【宠物心情文案映射】每种宠物状态对应的描述文案
 * 用于 Hero 区域的宠物气泡展示
 */
const petMoodCopy: Record<string, string> = {
  NORMAL: 'Feixue 安静地陪着你，等下一个任务开始。',
  WORKING: '正陪你冲刺当前任务，保持节奏。',
  REVIEWING: '提交已经送审，她在认真等结果。',
  HAPPY: '最近完成得不错，她心情很好。',
  PROUD: '这次提交很扎实，她为你骄傲。',
  EXCITED: '能量突破，新阶段已经解锁。',
  SLEEPY: '她有点想念你，补一个小任务热起来吧。',
  SAD: '有凭证需要处理，先把反馈跟掉会更稳。'
};

/**
 * 【工作台主组件】
 * @param tasks - 任务列表
 * @param pet - 宠物数据
 * @param summary - 今日摘要数据
 * @param onRefresh - 刷新数据回调
 * @param onOpenTasks - 跳转到任务页面的回调
 * @param onOpenReview - 跳转到复盘页面的回调
 * @param onOpenPet - 跳转到宠物页面的回调
 *
 * 数据来源：所有数据都从父组件（App）传入，Dashboard 本身不发起 API 请求。
 * 这种设计确保了数据的一致性和页面切换的流畅性。
 */
export function Dashboard({ tasks, pet, summary, onRefresh, onPetSync, onOpenTasks, onOpenReview, onOpenPet }: {
  tasks: StudyTask[];
  pet: Pet | null;
  summary: Summary | null;
  onRefresh: () => void;
  onPetSync: (pet: Pet) => void;
  onOpenTasks: () => void;
  onOpenReview: () => void;
  onOpenPet: () => void;
}) {
  /** 【最近任务】取前 5 个任务展示 */
  const latest = tasks.slice(0, 5);
  /** 【今日总任务数】至少为 1，避免除零 */
  const todayTotal = Math.max(summary?.todayTasks ?? 0, 1);
  /** 【今日完成数】今日提交次数 */
  const todayDone = summary?.todaySubmissions ?? 0;
  /** 【环形图最大值】取任务数和完成数的较大值 */
  const ringMax = Math.max(summary?.todayTasks ?? 0, todayDone, 1);
  /** 【宠物状态】默认为 NORMAL */
  const petStatus = pet?.status ?? 'NORMAL';
  /** 【宠物心情标签】从共享映射中获取 */
  const petMood = petStatusLabel[petStatus] ?? '安静陪伴';
  /** 【宠物心情文案】从本地映射中获取详细描述 */
  const petLine = petMoodCopy[petStatus] ?? petMoodCopy.NORMAL;

  return (
    <div className="content-grid">
      {/* ===== 【Hero 区域】今日打卡进度 + 宠物状态 ===== */}
      <section className="hero">
        <div className="hero-progress">
          {/* 【打卡进度环】展示今日完成/总任务比例 */}
          <ProgressRing
            value={todayDone}
            max={ringMax}
            size={148}
            stroke={14}
            label={`${todayDone}/${summary?.todayTasks ?? 0}`}
            sublabel="今日打卡"
          />
          <div>
            {/* 【动态文案】根据完成情况显示不同的鼓励文案 */}
            <div className="hero-progress-copy">
              <h2>
                {todayDone === 0
                  ? <>开始今天的<em> 第一次 </em>打卡</>
                  : todayDone >= todayTotal
                  ? <>今天已经<em> 满格 </em>了</>
                  : <>保持节奏，<em>继续推进</em></>}
              </h2>
              <p>{todayDone === 0 ? '创建或开始一个任务，提交凭证就能获得经验。' : '已完成的提交会同步给 AI 复核，宠物也会跟着成长。'}</p>
            </div>
            {/* 【今日统计】连续天数、今日经验、今日学时 */}
            <div className="hero-progress-stats">
              <div><span>连续打卡</span><strong>{summary?.consecutiveDays ?? 0}<small style={{ fontFamily: 'var(--sans)', fontSize: 12, color: 'var(--ink-3)', marginLeft: 4 }}>天</small></strong></div>
              <div><span>今日经验</span><strong>+{summary?.todayExp ?? 0}</strong></div>
              <div><span>今日学时</span><strong>{summary?.todayMinutes ?? 0}<small style={{ fontFamily: 'var(--sans)', fontSize: 12, color: 'var(--ink-3)', marginLeft: 4 }}>分</small></strong></div>
            </div>
          </div>
        </div>

        {/* 【宠物气泡按钮】点击跳转到宠物页面 */}
        <button className="hero-pet" onClick={onOpenPet} style={{ cursor: 'pointer' }}>
          <div className="hero-pet-figure">
            <PetSprite state={animationForPet(pet)} size={92} />
          </div>
          <div className="hero-pet-copy">
            <h3>{pet?.name ?? 'Soul'} <span className="muted">Lv.{pet?.level ?? 1}</span></h3>
            <div className="mood"><Sparkles size={11} /> {petMood}</div>
            <p>{petLine}</p>
          </div>
        </button>
      </section>

      {/* ===== 【每日签到】领经验+金币，连续越久越多 ===== */}
      <CheckinCard onPetSync={onPetSync} />

      {/* ===== 【指标行】6 个关键指标卡片 ===== */}
      <section className="metric-row">
        <Metric icon={<ClipboardList />} label="今日任务" value={summary?.todayTasks ?? 0} />
        <Metric icon={<CheckCircle2 />} label="提交次数" value={summary?.todaySubmissions ?? 0} />
        <Metric icon={<Sparkles />} label="今日经验" value={summary?.todayExp ?? 0} />
        <Metric icon={<Timer />} label="学习分钟" value={summary?.todayMinutes ?? 0} />
        <Metric icon={<Target />} label="专注分钟" value={summary?.todayFocusMinutes ?? 0} />
        <Metric icon={<CheckCircle2 />} label="专注次数" value={summary?.todayFocusSessions ?? 0} />
      </section>

      {/* ===== 【最近任务】展示最近 5 个任务，点击跳转到任务页面 ===== */}
      <section className="panel wide">
        <div className="panel-title">
          <h2>最近 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>任务</em></h2>
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="icon-button" onClick={onRefresh} title="刷新"><RefreshCw size={14} /></button>
            <button className="text-button" onClick={onOpenTasks}>查看全部 <ChevronRight size={14} /></button>
          </div>
        </div>
        <div className="task-list compact">
          {latest.map((task) => (
            <div className="task-card" key={task.id} onClick={onOpenTasks}>
              <TaskRow task={task} />
            </div>
          ))}
          {latest.length === 0 && <Empty text="还没有任务，去任务页创建第一个学习目标。" />}
        </div>
      </section>

      {/* ===== 【审核状态】统计不同审核状态的任务数量 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>审核 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>状态</em></h2></div>
        <div className="status-stack">
          {(['COMPLETED', 'AI_REJECTED', 'NEED_MORE'] as const).map((status) => (
            <div key={status} className="status-line">
              <span>{statusLabel[status]}</span>
              <strong>{tasks.filter((t) => t.status === status).length}</strong>
            </div>
          ))}
        </div>
      </section>

      {/* ===== 【学习趋势】近 7 天的学习时长趋势图 ===== */}
      <section className="panel wide">
        <div className="panel-title"><h2>近 7 天 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>学习趋势</em></h2></div>
        <Suspense fallback={<div className="muted">加载图表中...</div>}>
          <TrendChart data={summary?.trend ?? []} />
        </Suspense>
      </section>

      {/* ===== 【复盘入口】点击跳转到 AI 每日复盘页面 ===== */}
      <button
        className="panel review-entry"
        onClick={onOpenReview}
        style={{ textAlign: 'left', cursor: 'pointer', display: 'grid', gap: 12 }}
      >
        <div className="panel-title" style={{ marginBottom: 0 }}>
          <h2>今日 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>复盘</em></h2>
          <CalendarCheck size={18} style={{ color: 'var(--le-amber)' }} />
        </div>
        <p style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--ink-3)', margin: 0 }}>
          AI Reflection
        </p>
        <p style={{ lineHeight: 1.6, fontSize: 13.5, color: 'var(--ink-2)', margin: '4px 0 0' }}>
          点击查看 AI 根据今日任务、提交、学习时长生成的复盘报告。
        </p>
        <span style={{ alignSelf: 'flex-start', fontSize: 13, display: 'inline-flex', alignItems: 'center', gap: 4, marginTop: 4, color: 'var(--amber-deep, #b45309)', fontWeight: 600 }}>
          进入复盘 <ChevronRight size={14} />
        </span>
      </button>
    </div>
  );
}
