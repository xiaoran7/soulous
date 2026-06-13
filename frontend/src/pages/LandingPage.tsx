/**
 * 【落地页】LandingPage
 * 未登录访问时展示的营销首页：高保真还原 design/stitch/soulous_1。
 * 结构 = 悬浮玻璃胶囊导航（品牌 + 产品真实板块锚点 + 语言 + 登录）
 *      + 单屏双栏 Hero（左侧文案玻璃卡 + 右侧功能预览玻璃列，填满视口不留空）
 *      + 简化玻璃页脚（品牌句 + 版权，弃用链接列）
 *      + 右下角「领养伙伴」提示胶囊（未登录无宠物，故不展示真实精灵）。
 */
import React from 'react';
import { ArrowRight, Egg, Globe, ListChecks, PawPrint, Sparkles, Target, Timer } from 'lucide-react';

/** 导航锚点 = 产品真实板块；点击进入登录（功能需登录后使用，注册可在登录页切换） */
const NAV_LINKS = ['自习室', '计划', '宠物', 'AI 伙伴'] as const;

/** 右侧功能预览列：图标 + 标题 + 副文案，对应产品真实板块 */
const FEATURES = [
  { icon: Timer, title: '沉浸自习室', sub: '场景 · 白噪音 · 番茄钟' },
  { icon: ListChecks, title: 'AI 拆解计划', sub: '把大目标拆成今日清单' },
  { icon: PawPrint, title: '宠物成长', sub: '专注越久，伙伴越活跃' }
] as const;

export function LandingPage({ onStart }: { onStart: (mode: 'login' | 'register') => void }) {
  return (
    <div className="landing">
      {/* 整屏清晨窗景背景（复用自习室场景图） */}
      <div className="landing-bg" aria-hidden="true">
        <img src="/studyroom/morning-window.jpg" alt="" />
      </div>

      {/* 悬浮玻璃胶囊导航：品牌 | 产品真实板块 | 语言 + 登录（保持稀疏） */}
      <header className="landing-nav glass-card">
        <div className="landing-brand">Soulous</div>
        <nav className="landing-links">
          {NAV_LINKS.map((label) => (
            <button key={label} type="button" onClick={() => onStart('login')}>{label}</button>
          ))}
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

        {/* 右侧功能预览玻璃列（仅宽屏）：三张功能卡 + 今日目标进度卡，填满右半屏 */}
        <div className="landing-features" aria-hidden="true">
          {FEATURES.map(({ icon: Icon, title, sub }) => (
            <div key={title} className="glass-card landing-feature">
              <span className="landing-chip-badge"><Icon size={18} strokeWidth={2.2} /></span>
              <div className="landing-feature-body">
                <strong>{title}</strong>
                <span>{sub}</span>
              </div>
            </div>
          ))}
          <div className="glass-card landing-feature landing-feature-goal">
            <div className="landing-chip-goal-top">
              <span className="landing-chip-badge sm"><Target size={13} strokeWidth={2.4} /></span>
              <span>今日专注目标</span>
            </div>
            <div className="landing-chip-goal-meter">
              <div className="landing-goal-bar"><i style={{ width: '45%' }} /></div>
              <span className="landing-chip-goal-num"><strong>54</strong>/120</span>
            </div>
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

      {/* 右下角「领养伙伴」提示：未登录尚无宠物，用引导胶囊替代真实精灵 */}
      <button className="landing-adopt glass-card amber-glow le-float" onClick={() => onStart('register')} title="注册领养你的学习伙伴">
        <span className="landing-adopt-egg"><Egg size={20} strokeWidth={1.8} /><Sparkles className="landing-adopt-spark" size={11} /></span>
        <span className="landing-adopt-text">领养你的<br />学习伙伴</span>
      </button>
    </div>
  );
}
