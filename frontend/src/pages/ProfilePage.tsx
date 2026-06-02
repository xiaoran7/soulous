/**
 * 【我的页面】ProfilePage（导航标签「我的」）
 * 本页**只读**——账号资料与宠物资料一律展示，不在此处编辑：
 * - 账号概览：头像 + 昵称 + 用户名·角色 + 用户 ID / 邮箱 / 头像状态
 * - 宠物：精灵 + 名称 + 等级 / 状态
 *
 * 设计思路：顶部通栏 Hero 概览 + 下方宠物卡，全部只读。
 * 需要改昵称/邮箱/头像/宠物名称时，统一前往「设置」。
 */
import React, { useEffect, useState } from 'react';
import { Cat } from 'lucide-react';
import { api } from '../api';
import type { Pet, User } from '../types';
import { ClickableAvatar, animationForPet, petStatusLabel } from '../components/shared';
import { PetSprite } from '../PetSprite';

/**
 * 【我的页面主组件】纯展示，无任何编辑入口。
 * @param user - 当前用户信息（只读展示）
 */
export function ProfilePage({ user }: { user: User }) {
  /** 【宠物数据】从 API 加载，仅用于只读展示 */
  const [pet, setPet] = useState<Pet | null>(null);

  /**
   * 【加载宠物数据】组件挂载时获取宠物信息
   * 使用 cancelled 标志防止组件卸载后更新状态；宠物可能尚未创建，catch 静默处理。
   */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const p = await api.pet();
        if (!cancelled) setPet(p);
      } catch { /* pet may not be ready yet */ }
    })();
    return () => { cancelled = true; };
  }, [user.id]);

  /** 【头像首字母】无头像 URL 时显示昵称/用户名首字母占位 */
  const initial = (user.nickname || user.username || 'S').slice(0, 1).toUpperCase();

  return (
    <div className="profile-page">
      {/* ===== 账号概览（只读）：头像 + 昵称 + 详情 ===== */}
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
        {/* 【用户资料详情】ID、邮箱、头像状态 */}
        <div className="profile-facts">
          <div><span>用户 ID</span><strong>{user.id}</strong></div>
          <div><span>邮箱</span><strong>{user.email || '未填写'}</strong></div>
          <div><span>头像</span><strong>{user.avatarUrl ? '已配置' : '未配置'}</strong></div>
        </div>
        <p className="muted small" style={{ margin: '8px 0 0' }}>编辑昵称、邮箱、头像与宠物名称请前往「设置」。</p>
      </section>

      {/* ===== 宠物（只读）：精灵 + 名称 + 等级/状态 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>宠物</h2><Cat size={18} /></div>
        <div className="pet-name-preview" style={{ margin: 0 }}>
          <div className="pet-name-frame">
            <PetSprite state={animationForPet(pet)} size={40} />
          </div>
          <div>
            <strong>{pet?.name || 'Soul'}</strong>
            <span>Lv.{pet?.level ?? 1} · {petStatusLabel[pet?.status ?? 'NORMAL'] ?? '安静陪伴'}</span>
          </div>
        </div>
      </section>
    </div>
  );
}
