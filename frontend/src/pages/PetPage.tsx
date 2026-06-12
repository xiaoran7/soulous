/**
 * 【宠物页面】PetPage
 * 高保真还原 design/stitch/pet：固定单屏的「自习室宠物角」布局。
 * - 中央动画舞台：大尺寸宠物精灵
 * - 左下浮动属性卡：名字 + 等级 + 状态 + 经验条 + 心情/饱食
 * - 右侧动作列：喂食 / 玩耍（切换已解锁动作）/ 宠物市场 / 休息
 *
 * 设计取舍（用户确认「严格照 stitch 精简」）：近 7 天经验趋势图、9 宫格动作解锁、
 * 成长事件日志均已移除，动作预览改由「玩耍/休息」按钮在已解锁动作间切换，确保整屏不滚动。
 */
import React, { useEffect, useState } from 'react';
import { Cookie, Moon, Sparkles, Store } from 'lucide-react';
import { api } from '../api';
import { PetSprite } from '../PetSprite';
import type { PetAnimationState } from '../PetSprite';
import type { Pet } from '../types';
import { StatBar, animationForPet, clampAnimation, PET_ACTION_UNLOCK_LEVEL } from '../components/shared';
import { PetMarket } from '../components/PetMarket';

/** 【成长日志缓存占位】本页不再展示事件日志，保留空实现供登出清理调用（main.tsx resetAllCaches）。 */
export function resetPetLogsCache() { /* 事件日志已下线，无缓存可清 */ }

/**
 * 【宠物状态文案映射】每种宠物状态对应一个标题和描述文案
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

/** 【玩耍可切换的活泼动作】按解锁顺序排列，「玩耍」按钮在已解锁项间循环。 */
const PLAY_ACTIONS: PetAnimationState[] = ['waving', 'jumping', 'running', 'running-right', 'running-left'];

/**
 * 【宠物页面主组件】
 * @param pet - 初始宠物数据（从父组件传入）
 * @param onFed - 喂食/更新后的回调，通知父组件同步宠物状态
 * @param onRefresh - 市场领养/购买/切换出战后刷新全局数据
 */
export function PetPage({ pet: initialPet, onFed, onRefresh }: { pet: Pet | null; onFed?: (pet: Pet) => void; onRefresh?: () => void }) {
  const [pet, setPet] = useState<Pet | null>(initialPet);
  const [marketOpen, setMarketOpen] = useState(false);
  const petLevel = pet?.level ?? 1;
  /** 宠物自然状态可能映射到尚未解锁的动作，钳制回 idle 避免主区播放未解锁动作 */
  const safePetState = clampAnimation(animationForPet(pet), petLevel);
  const [previewState, setPreviewState] = useState<PetAnimationState>(safePetState);
  const [feeding, setFeeding] = useState(false);
  const [feedError, setFeedError] = useState('');
  const statusCopy = petStatusCopy[pet?.status ?? 'NORMAL'] ?? petStatusCopy.NORMAL;

  // 同步父组件数据 / 预览状态
  React.useEffect(() => { setPet(initialPet); }, [initialPet]);
  useEffect(() => { setPreviewState(safePetState); }, [safePetState]);

  const marketModal = marketOpen && (
    <PetMarket onClose={() => setMarketOpen(false)} onChanged={() => { onRefresh?.(); }} />
  );

  // 未领养任何宠物：领养引导
  if (!pet) {
    return (
      <div className="pet-room pet-room-empty">
        {marketModal}
        <section className="panel pet-empty">
          <div className="pet-empty-frame"><PetSprite state="waving" size={120} /></div>
          <h2>领养你的第一只伙伴</h2>
          <p className="muted">完成学习任务、每日打卡能赚取金币。先去宠物市场免费领养一只入门伙伴，陪你一起成长吧。</p>
          <button className="le-primary-button" onClick={() => setMarketOpen(true)}>
            <Store size={15} /> 去宠物市场
          </button>
        </section>
      </div>
    );
  }

  /** 【喂食】增加饱食度，成功后同步本地与父组件 */
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

  /** 【玩耍】在已解锁的活泼动作间循环切换主展示区动画；低等级尚无解锁动作时
   *  退回在 待机/等待 间切换，保证按钮始终有可见反馈 */
  function play() {
    const unlocked = PLAY_ACTIONS.filter((s) => (PET_ACTION_UNLOCK_LEVEL[s] ?? 1) <= petLevel);
    const pool: PetAnimationState[] = unlocked.length ? unlocked : ['idle', 'waiting'];
    const idx = pool.indexOf(previewState);
    setPreviewState(pool[(idx + 1) % pool.length]);
  }

  /** 【休息】回到等待/待机动作 */
  function rest() {
    setPreviewState((PET_ACTION_UNLOCK_LEVEL.waiting ?? 1) <= petLevel ? 'waiting' : 'idle');
  }

  return (
    <div className="pet-room">
      {marketModal}

      {/* 中央动画舞台 + 左下浮动属性卡 */}
      <div className="pet-stage">
        <div className="pet-stage-frame">
          <PetSprite state={previewState} size={200} sheet={pet.species?.spritePath} />
        </div>

        <div className="pet-stats-card panel">
          <div className="pet-stats-head">
            <strong>{pet.name ?? 'Soul'}</strong>
            <span className="lvl-pill">Lv.{petLevel}</span>
            <span className="pet-stats-stage">{pet.growthStage ?? 'EGG'}</span>
          </div>
          <p className="pet-stats-status">{statusCopy.title}</p>
          <div className="pet-stats-bars">
            <StatBar
              label="经验"
              value={pet.currentExp ?? 0}
              max={pet.nextLevelExp ?? 100}
              tone="primary"
              valueLabel={`${pet.currentExp ?? 0} / ${pet.nextLevelExp ?? 100}`}
            />
            <div className="pet-stats-mini">
              <span>心情 <strong>{pet.mood ?? 0}</strong></span>
              <span>饱食 <strong>{pet.satiety ?? 0}</strong></span>
            </div>
          </div>
          {feedError && <div className="form-error" style={{ marginTop: 8 }}>{feedError}</div>}
        </div>
      </div>

      {/* 右侧动作列 */}
      <div className="pet-actions-col">
        <button className="pet-act-btn" onClick={feed} disabled={feeding} title="喂食 (+20 饱食)">
          <Cookie size={22} /><span>{feeding ? '喂食中' : '喂食'}</span>
        </button>
        <button className="pet-act-btn" onClick={play} title="切换动作">
          <Sparkles size={22} /><span>玩耍</span>
        </button>
        <button className="pet-act-btn" onClick={() => setMarketOpen(true)} title="宠物市场">
          <Store size={22} /><span>市场</span>
        </button>
        <button className="pet-act-btn" onClick={rest} title="休息">
          <Moon size={22} /><span>休息</span>
        </button>
      </div>
    </div>
  );
}
