/**
 * 【落地页】LandingPage
 * 未登录访问时展示的营销首页：高保真还原 design/stitch/soulous_1。
 * 结构 = 悬浮玻璃胶囊导航（品牌 + 场景/音乐/计划/会员 + 语言 + 登录）
 *      + 单屏 Hero 玻璃卡（一个 CTA）+ 右侧散点装饰玻璃片
 *      + 简化玻璃页脚（品牌句 + 版权，弃用链接列）
 *      + 右下角悬浮宠物。无功能网格、无内部滚动区。
 */
import React from 'react';
import { ArrowRight, Coffee, Globe } from 'lucide-react';
import { PetSprite } from '../PetSprite';

export function LandingPage({ onStart }: { onStart: (mode: 'login' | 'register') => void }) {
  return (
    <div className="landing">
      {/* 整屏清晨窗景背景（复用自习室场景图） */}
      <div className="landing-bg" aria-hidden="true">
        <img src="/studyroom/morning-window.jpg" alt="" />
      </div>

      {/* 悬浮玻璃胶囊导航：品牌 | 四个锚点 | 语言 + 登录（与设计稿一致，保持稀疏） */}
      <header className="landing-nav glass-card">
        <div className="landing-brand">Soulous</div>
        <nav className="landing-links" aria-hidden="true">
          <span>场景</span><span>音乐</span><span>计划</span><span>会员</span>
        </nav>
        <div className="landing-nav-actions">
          <button className="landing-globe" aria-label="语言" title="语言">
            <Globe size={18} />
          </button>
          <button className="le-primary-button" onClick={() => onStart('login')}>登录</button>
        </div>
      </header>

      <section className="landing-hero">
        <div className="landing-hero-card glass-card">
          <p className="landing-eyebrow">FOCUS LEARN GROW</p>
          <h1>开启你的<br />清晨自习室</h1>
          <p className="landing-sub">
            选择场景、音乐与节奏，把一天最清醒的时间留给真正重要的学习。沉浸式体验，告别焦虑。
          </p>
          <div className="landing-cta">
            <button className="le-primary-button large" onClick={() => onStart('register')}>
              开始学习 <ArrowRight size={18} />
            </button>
          </div>
        </div>

        {/* 散点装饰玻璃片（仅宽屏，模拟书桌上随手摆放的物件） */}
        <div className="landing-scatter" aria-hidden="true">
          <div className="glass-card landing-chip landing-chip-coffee le-float">
            <Coffee size={36} strokeWidth={1.6} />
            <span>晨间咖啡已备好</span>
          </div>
          <div className="glass-card landing-chip landing-chip-goal">
            <div><span>今日专注目标</span><strong>120 min</strong></div>
            <div className="landing-goal-bar"><i style={{ width: '45%' }} /></div>
          </div>
        </div>
      </section>

      {/* 简化页脚：只留品牌句与版权（设计稿的链接列按需求弃用） */}
      <footer className="landing-footer glass-card">
        <div className="landing-footer-brand">Soulous</div>
        <p className="landing-footer-tagline">
          为你打造纯净的数字自习空间。用设计与科技的力量，重塑学习体验，找回心流时刻。
        </p>
        <div className="landing-footer-copy">© 2026 Soulous. All rights reserved.</div>
      </footer>

      {/* 右下角漂浮宠物 */}
      <button className="landing-pet glass-card amber-glow le-float" onClick={() => onStart('register')} title="和飞雪一起开始">
        <PetSprite state="waving" size={64} />
      </button>
    </div>
  );
}
