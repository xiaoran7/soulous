/**
 * 【我的页面】ProfilePage（导航标签「我的」）
 * 个人中心总览（只读）：账号概览 + 数据概览（金币/连续打卡/宠物数/等级）+ 出战宠物经验进度
 * + 宠物收藏墙 + 最近金币流水。编辑资料统一前往「设置」。
 */
import React, { useEffect, useState } from 'react';
import { Cat, Coins, Flame, PawPrint, Sparkles } from 'lucide-react';
import { api } from '../api';
import type { Pet, User } from '../types';
import { ClickableAvatar, StatBar, animationForPet, petStatusLabel } from '../components/shared';
import { PetSprite } from '../PetSprite';

type LedgerRow = { id: number; amount: number; balanceAfter: number; source: string; reason: string; createdAt: string };

/**
 * 【我的页面模块级缓存】记住上次拉取的宠物/收藏/钱包/打卡数据，跨页面切回时直接渲染、
 * 后台静默刷新，消除每次进入的白屏等待。登出由 resetProfileCache() 清空，避免串户。
 */
type ProfileSnapshot = {
  pet: Pet | null;
  owned: Pet[];
  balance: number;
  ledger: LedgerRow[];
  streak: number;
  checkedInToday: boolean;
};
let profileCache: ProfileSnapshot | null = null;
export function resetProfileCache() { profileCache = null; }

/** 【流水来源中文标签】 */
const sourceLabel: Record<string, string> = {
  TASK: '完成任务', FOCUS: '专注完成', CHECKIN: '每日打卡', PURCHASE: '购买宠物', ADJUST: '调整', TEST: '测试'
};

function fmtDate(value: string) {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/**
 * 【我的页面主组件】纯展示，无任何编辑入口。
 * @param user - 当前用户信息（只读展示）
 */
export function ProfilePage({ user }: { user: User }) {
  const [pet, setPet] = useState<Pet | null>(() => profileCache?.pet ?? null);
  const [owned, setOwned] = useState<Pet[]>(() => profileCache?.owned ?? []);
  const [balance, setBalance] = useState<number>(() => profileCache?.balance ?? user.coinBalance ?? 0);
  const [ledger, setLedger] = useState<LedgerRow[]>(() => profileCache?.ledger ?? []);
  const [streak, setStreak] = useState<number>(() => profileCache?.streak ?? 0);
  const [checkedInToday, setCheckedInToday] = useState<boolean>(() => profileCache?.checkedInToday ?? false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const [p, ow, w, c] = await Promise.all([
          api.pet().catch(() => null),
          api.petOwned().catch(() => []),
          api.wallet().catch(() => ({ balance: user.coinBalance ?? 0, ledger: [] })),
          api.checkinStatus().catch(() => ({ checkedInToday: false, streak: 0, balance: 0 }))
        ]);
        if (cancelled) return;
        const snapshot: ProfileSnapshot = {
          pet: p as Pet | null,
          owned: Array.isArray(ow) ? (ow as Pet[]) : [],
          balance: w?.balance ?? 0,
          ledger: Array.isArray(w?.ledger) ? (w.ledger as LedgerRow[]) : [],
          streak: c?.streak ?? 0,
          checkedInToday: !!c?.checkedInToday
        };
        profileCache = snapshot;
        setPet(snapshot.pet);
        setOwned(snapshot.owned);
        setBalance(snapshot.balance);
        setLedger(snapshot.ledger);
        setStreak(snapshot.streak);
        setCheckedInToday(snapshot.checkedInToday);
      } catch { /* 静默 */ }
    })();
    return () => { cancelled = true; };
  }, [user.id]);

  const initial = (user.nickname || user.username || 'S').slice(0, 1).toUpperCase();

  return (
    <div className="profile-page">
      {/* ===== 账号概览（只读） ===== */}
      <section className="panel profile-hero-card">
        <div className="profile-hero">
          {user.avatarUrl ? (
            <ClickableAvatar className="avatar-preview" url={user.avatarUrl} alt={`${user.nickname || user.username} 的头像`} />
          ) : (
            <div className="avatar-preview fallback">{initial}</div>
          )}
          <div className="profile-hero-id">
            <h3>{user.nickname || user.username}</h3>
            <p>{user.username} · {user.role}</p>
          </div>
        </div>
        <div className="profile-facts">
          <div><span>用户 ID</span><strong>{user.id}</strong></div>
          <div><span>邮箱</span><strong>{user.email || '未填写'}</strong></div>
          <div><span>头像</span><strong>{user.avatarUrl ? '已配置' : '未配置'}</strong></div>
        </div>
        <p className="muted small" style={{ margin: '8px 0 0' }}>编辑昵称、邮箱、头像与宠物名称请前往「设置」。</p>
      </section>

      {/* ===== 数据概览 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>数据 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>概览</em></h2></div>
        <div className="profile-stat-grid">
          <div className="profile-stat"><Coins size={18} /><strong>{balance}</strong><span>金币</span></div>
          <div className="profile-stat"><Flame size={18} /><strong>{streak}</strong><span>连续打卡{checkedInToday ? '·今日已签' : ''}</span></div>
          <div className="profile-stat"><PawPrint size={18} /><strong>{owned.length}</strong><span>拥有宠物</span></div>
          <div className="profile-stat"><Sparkles size={18} /><strong>Lv.{pet?.level ?? '—'}</strong><span>出战等级</span></div>
        </div>
        {pet && (
          <div style={{ marginTop: 12 }}>
            <StatBar label="出战经验" value={pet.currentExp} max={pet.nextLevelExp} tone="primary"
              valueLabel={`${pet.currentExp} / ${pet.nextLevelExp}`} />
          </div>
        )}
      </section>

      {/* ===== 出战宠物 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>宠物</h2><Cat size={18} /></div>
        <div className="pet-name-preview" style={{ margin: 0 }}>
          <div className="pet-name-frame">
            <PetSprite state={animationForPet(pet)} size={40} sheet={pet?.species?.spritePath} />
          </div>
          <div>
            <strong>{pet?.name || '未领养'}</strong>
            <span>{pet ? `Lv.${pet.level} · ${petStatusLabel[pet.status ?? 'NORMAL'] ?? '安静陪伴'}` : '去宠物页领养第一只'}</span>
          </div>
        </div>
        {/* 收藏墙 */}
        {owned.length > 0 && (
          <div className="profile-collection">
            {owned.map((p) => (
              <div key={p.id} className={`profile-collection-item${p.active ? ' active' : ''}`} title={`${p.name} Lv.${p.level}${p.active ? '（出战中）' : ''}`}>
                <PetSprite state="idle" size={44} sheet={p.species?.spritePath} label={p.name} />
                <span>Lv.{p.level}</span>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* ===== 最近金币流水 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>金币 <em style={{ fontStyle: 'italic', color: 'var(--ink-3)' }}>流水</em></h2></div>
        {ledger.length === 0 ? (
          <div className="muted small">还没有金币变动。完成任务、专注或每日打卡就能赚金币。</div>
        ) : (
          <div className="profile-ledger">
            {ledger.slice(0, 8).map((l) => (
              <div className="profile-ledger-row" key={l.id}>
                <div>
                  <strong>{sourceLabel[l.source] ?? l.source}</strong>
                  <span className="muted small">{l.reason || ''} · {fmtDate(l.createdAt)}</span>
                </div>
                <span className={`badge ${l.amount >= 0 ? 'badge-completed' : ''}`}>{l.amount >= 0 ? `+${l.amount}` : l.amount}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
