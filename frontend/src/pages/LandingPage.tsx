/**
 * 【落地页】LandingPage
 * 未登录访问时展示的营销首页：品牌 + 功能亮点 + 行动召唤（开始使用 / 登录）。
 * 真实功能页全程鉴权，点击 CTA 才进入登录/注册。
 * 视觉：Luminous Ethereal 玻璃拟态（Stitch 重构，设计稿见 design/stitch/soulous_1）。
 */
import React from 'react';
import { Bot, CalendarRange, Coins, PawPrint, Sparkles, Timer } from 'lucide-react';
import { PetSprite } from '../PetSprite';

const FEATURES = [
  { icon: <Bot size={20} />, title: '任务打卡 · AI 初审', body: '建任务、传凭证，AI 先帮你把关，再分层发放经验。' },
  { icon: <PawPrint size={20} />, title: '宠物养成 · 市场', body: '免费领养第一只伙伴，升级解锁动作，金币去市场收集更多。' },
  { icon: <Coins size={20} />, title: '金币 · 每日签到', body: '完成任务、专注、连续签到都能赚金币，连击越久奖励越高。' },
  { icon: <Timer size={20} />, title: '自习室 · 专注', body: '场景 + 白噪音的沉浸自习，陪你把时间真正用在学习上。' },
  { icon: <CalendarRange size={20} />, title: '课表 · 成绩', body: '一键同步教务课表、考试与成绩，本学期一目了然。' },
  { icon: <Sparkles size={20} />, title: '每日复盘', body: 'AI 根据当天任务、提交与时长生成复盘，帮你持续改进。' }
];

export function LandingPage({ onStart }: { onStart: (mode: 'login' | 'register') => void }) {
  return (
    <div className="landing">
      {/* 整屏清晨窗景背景（复用自习室场景图） */}
      <div className="landing-bg" aria-hidden="true">
        <img src="/studyroom/morning-window.jpg" alt="" />
      </div>

      {/* 悬浮玻璃胶囊导航 */}
      <header className="landing-nav glass-card">
        <div className="brand"><span className="brand-mark" aria-hidden="true" /> Soulous <em>灵魂</em></div>
        <nav className="landing-links" aria-hidden="true">
          <span>场景</span><span>音乐</span><span>计划</span>
        </nav>
        <div className="landing-nav-actions">
          <button className="le-ghost-button" onClick={() => onStart('login')}>登录</button>
          <button className="le-primary-button" onClick={() => onStart('register')}>开始使用</button>
        </div>
      </header>

      <section className="landing-hero">
        <div className="landing-hero-card glass-card le-float">
          <p className="landing-eyebrow">FOCUS · LEARN · GROW</p>
          <h1>开启你的<br />清晨自习室</h1>
          <p className="landing-sub">
            选择场景、音乐与节奏，把一天最清醒的时间留给真正重要的学习。
            任务打卡、AI 初审、金币与宠物养成，在一个工作台里闭环。
          </p>
          <div className="landing-cta">
            <button className="le-primary-button large" onClick={() => onStart('register')}>开始学习 →</button>
            <button className="le-ghost-button large" onClick={() => onStart('login')}>已有账号登录</button>
          </div>
        </div>

        {/* 散点装饰玻璃片（仅宽屏） */}
        <div className="landing-scatter" aria-hidden="true">
          <div className="glass-card landing-chip landing-chip-coffee le-float">☕ 晨间咖啡已备好</div>
          <div className="glass-card landing-chip landing-chip-goal">
            <div><span>今日专注目标</span><strong>120 min</strong></div>
            <div className="landing-goal-bar"><i style={{ width: '45%' }} /></div>
          </div>
        </div>
      </section>

      <section className="landing-features">
        {FEATURES.map((f) => (
          <div className="glass-card landing-feature" key={f.title}>
            <div className="landing-feature-icon">{f.icon}</div>
            <strong>{f.title}</strong>
            <p>{f.body}</p>
          </div>
        ))}
      </section>

      <footer className="landing-footer glass-card">
        <span>Soulous · 为你打造纯净的数字自习空间</span>
        <button className="text-button" onClick={() => onStart('register')}>开始使用 →</button>
      </footer>

      {/* 右下角漂浮宠物 */}
      <button className="landing-pet glass-card amber-glow le-float" onClick={() => onStart('register')} title="和飞雪一起开始">
        <PetSprite state="waving" size={64} />
      </button>
    </div>
  );
}
