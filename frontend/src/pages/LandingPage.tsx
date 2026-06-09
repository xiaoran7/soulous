/**
 * 【落地页】LandingPage
 * 未登录访问时展示的营销首页：品牌 + 功能亮点 + 行动召唤（开始使用 / 登录）。
 * 真实功能页全程鉴权，点击 CTA 才进入登录/注册。
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
      <header className="landing-nav">
        <div className="brand"><span className="brand-mark" aria-hidden="true" /> Soulous <em>灵魂</em></div>
        <div className="landing-nav-actions">
          <button className="text-button" onClick={() => onStart('login')}>登录</button>
          <button className="primary-button" onClick={() => onStart('register')}>开始使用</button>
        </div>
      </header>

      <section className="landing-hero">
        <div className="landing-hero-copy">
          <p className="page-eyebrow">Study · Grow · Together</p>
          <h1>把每一次学习<br /><em>凭证</em>，变成<em>成长</em>反馈</h1>
          <p className="landing-sub">
            任务打卡、AI 初审、分层经验和宠物养成，都在一个轻量工作台里闭环。
            完成学习赚金币，陪伴宠物一起升级。
          </p>
          <div className="landing-cta">
            <button className="primary-button large" onClick={() => onStart('register')}>免费开始</button>
            <button className="secondary-button large" onClick={() => onStart('login')}>已有账号登录</button>
          </div>
        </div>
        <div className="landing-hero-pet">
          <PetSprite state="waving" size={180} />
        </div>
      </section>

      <section className="landing-features">
        {FEATURES.map((f) => (
          <div className="landing-feature" key={f.title}>
            <div className="landing-feature-icon">{f.icon}</div>
            <strong>{f.title}</strong>
            <p>{f.body}</p>
          </div>
        ))}
      </section>

      <footer className="landing-footer">
        <span>Soulous · 学习打卡 × 宠物成长</span>
        <button className="text-button" onClick={() => onStart('register')}>开始使用 →</button>
      </footer>
    </div>
  );
}
