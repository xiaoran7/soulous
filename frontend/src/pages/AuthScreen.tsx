/**
 * 【认证页面】AuthScreen
 * 本页面是用户的入口页面，提供登录和注册功能：
 * - 登录：用户名 + 密码 + 验证码
 * - 注册：用户名 + 密码 + 确认密码 + 昵称 + 验证码
 * - 验证码：图形验证码，点击图片可刷新
 *
 * 安全设计：
 * - 密码强度校验：8-72 位、至少两类字符、不能含空格或用户名
 * - 图形验证码防暴力破解
 * - 验证码失败后自动刷新
 *
 * 交互设计：
 * - 登录/注册模式切换时自动刷新验证码
 * - 验证码图片支持点击和键盘操作（Enter/Space）
 * - 表单提交时禁用按钮防止重复提交
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';

/**
 * 【密码强度校验】在注册时验证密码是否符合安全要求
 * @param password - 用户输入的密码
 * @param username - 用户名（用于检查密码是否包含用户名）
 * @returns 错误信息字符串，校验通过返回 null
 *
 * 校验规则：
 * 1. 不能为空
 * 2. 长度 8-72 位
 * 3. 不能包含空格
 * 4. 至少包含字母、数字、符号中的两类
 * 5. 不能包含用户名（防止密码泄露风险）
 */
function validatePassword(password: string, username: string): string | null {
  if (!password) return '密码不能为空';
  if (password.length < 8) return '密码至少 8 位';
  if (password.length > 72) return '密码不能超过 72 位';
  if (/\s/.test(password)) return '密码不能包含空格';
  let classes = 0;
  if (/[a-z]/.test(password)) classes++;
  if (/[A-Z]/.test(password)) classes++;
  if (/[0-9]/.test(password)) classes++;
  if (/[^A-Za-z0-9]/.test(password)) classes++;
  if (classes < 2) return '密码至少需要包含字母、数字、符号中的两类';
  if (username && username.length >= 4 && password.toLowerCase().includes(username.toLowerCase())) {
    return '密码不能包含用户名';
  }
  return null;
}

/** 【邮箱格式校验】注册时邮箱可选，但一旦填写就必须是合法格式（后续邮件提醒依赖它） */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
function validateEmail(email: string): string | null {
  const trimmed = email.trim();
  if (!trimmed) return null; // 可选，留空放行
  if (trimmed.length > 254) return '邮箱过长';
  if (!EMAIL_RE.test(trimmed)) return '邮箱格式不正确';
  return null;
}

/**
 * 【密码强度评估】实时给注册用户可视化反馈，替代原先一行静态文字。
 * 评分基于长度与字符种类，返回 0-4 档及对应文案/颜色。
 * @returns score 0=无/极弱 ... 4=很强；filled 用于决定渲染几格
 */
function passwordStrength(password: string): { score: number; label: string; color: string } {
  if (!password) return { score: 0, label: '', color: '#d1d5db' };
  let classes = 0;
  if (/[a-z]/.test(password)) classes++;
  if (/[A-Z]/.test(password)) classes++;
  if (/[0-9]/.test(password)) classes++;
  if (/[^A-Za-z0-9]/.test(password)) classes++;
  let score = classes;
  if (password.length >= 12) score++;
  if (password.length < 8) score = Math.min(score, 1);
  score = Math.max(1, Math.min(4, score));
  const meta = [
    { label: '太弱', color: '#ef4444' },
    { label: '较弱', color: '#f59e0b' },
    { label: '中等', color: '#eab308' },
    { label: '较强', color: '#22c55e' }
  ][score - 1];
  return { score, ...meta };
}

/**
 * 【认证页面组件】
 * @param onAuthed - 认证成功回调，通知父组件切换到主界面
 * @param message - 外部传入的消息（如 session 过期提示）
 *
 * 核心状态：
 * - mode: 当前模式（login=登录/register=注册）
 * - captchaId/captchaImage/captchaCode: 验证码相关状态
 * - inflight: 防止并发刷新验证码的标志
 */
export function AuthScreen({ onAuthed, message, initialMode = 'login' }: { onAuthed: () => void; message: string; initialMode?: 'login' | 'register' }) {
  const [mode, setMode] = useState<'login' | 'register'>(initialMode);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  /** 【确认密码】仅注册模式显示 */
  const [confirmPassword, setConfirmPassword] = useState('');
  /** 【昵称】仅注册模式显示，可选 */
  const [nickname, setNickname] = useState('');
  /** 【邮箱】仅注册模式显示，必填；注册验证码发往此邮箱，也用于后续提醒邮件 */
  const [email, setEmail] = useState('');
  /** 【邮箱验证码】仅注册模式显示，必填；点「发送验证码」后填入收到的 6 位码 */
  const [emailCode, setEmailCode] = useState('');
  /** 【发码中标志】防止重复点击「发送验证码」 */
  const [sendingCode, setSendingCode] = useState(false);
  /** 【重发冷却秒数】> 0 时「发送验证码」按钮禁用并倒计时 */
  const [codeCooldown, setCodeCooldown] = useState(0);
  /** 【发码成功提示】展示「验证码已发送」之类的轻提示 */
  const [codeHint, setCodeHint] = useState('');
  /** 【验证码 ID】服务端生成的验证码标识 */
  const [captchaId, setCaptchaId] = useState('');
  /** 【验证码图片】Base64 编码的验证码图片数据 */
  const [captchaImage, setCaptchaImage] = useState('');
  /** 【验证码输入】用户输入的验证码文本 */
  const [captchaCode, setCaptchaCode] = useState('');
  const [error, setError] = useState(message);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  /** 【并发控制标志】防止同时发起多个验证码请求 */
  const inflight = useRef(false);

  /**
   * 【刷新验证码】从 API 获取新的验证码图片
   * 使用 inflight 标志防止并发请求
   * 失败时清空验证码状态并显示错误
   */
  const refreshCaptcha = useCallback(async () => {
    if (inflight.current) return;
    inflight.current = true;
    setRefreshing(true);
    setCaptchaCode('');
    try {
      const { id, image } = await api.captcha();
      setCaptchaId(id);
      setCaptchaImage(image);
    } catch (err) {
      setCaptchaId('');
      setCaptchaImage('');
      setError(err instanceof Error ? `验证码加载失败：${err.message}` : '验证码加载失败');
    } finally {
      inflight.current = false;
      setRefreshing(false);
    }
  }, []);

  // 【初始加载验证码】组件挂载时获取第一个验证码
  useEffect(() => { void refreshCaptcha(); }, [refreshCaptcha]);

  // 【发码重发倒计时】codeCooldown > 0 时每秒递减，归零后按钮恢复可点
  useEffect(() => {
    if (codeCooldown <= 0) return;
    const timer = setTimeout(() => setCodeCooldown((s) => s - 1), 1000);
    return () => clearTimeout(timer);
  }, [codeCooldown]);

  /**
   * 【发送邮箱验证码】注册模式专用：校验邮箱格式后请求后端发码，成功则进入 60s 冷却。
   * 防呆：邮箱为空/格式错时内联报错，不发请求。
   */
  async function sendCode() {
    if (sendingCode || codeCooldown > 0) return;
    const trimmed = email.trim();
    if (!trimmed) { setError('请先填写邮箱'); return; }
    const emailReason = validateEmail(trimmed);
    if (emailReason) { setError(emailReason); return; }
    setError('');
    setCodeHint('');
    setSendingCode(true);
    try {
      await api.sendEmailCode(trimmed);
      setCodeHint('验证码已发送，请查收邮箱（10 分钟内有效）');
      setCodeCooldown(60);
    } catch (err) {
      setError(err instanceof Error ? err.message : '验证码发送失败');
    } finally {
      setSendingCode(false);
    }
  }

  /**
   * 【提交表单】登录或注册
   * - 登录：校验图形验证码已加载且已输入 → api.login
   * - 注册：校验密码强度/确认/邮箱格式/邮箱验证码 → api.register
   * 登录失败后刷新图形验证码（注册不涉及图形验证码）。
   */
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (mode === 'login') {
      if (!captchaId || !captchaImage) { setError('验证码还未加载，请稍候'); return; }
      if (!captchaCode.trim()) { setError('请输入验证码'); return; }
    } else {
      const reason = validatePassword(password, username);
      if (reason) { setError(reason); return; }
      if (password !== confirmPassword) { setError('两次输入的密码不一致'); return; }
      const trimmedEmail = email.trim();
      if (!trimmedEmail) { setError('请填写邮箱'); return; }
      const emailReason = validateEmail(trimmedEmail);
      if (emailReason) { setError(emailReason); return; }
      if (!emailCode.trim()) { setError('请填写邮箱验证码'); return; }
    }
    setError('');
    setLoading(true);
    try {
      if (mode === 'login') await api.login(username, password, captchaId, captchaCode);
      else await api.register(username, password, confirmPassword, nickname, email.trim(), emailCode.trim());
      onAuthed();
    } catch (err) {
      setError(err instanceof Error ? err.message : '认证失败');
      if (mode === 'login') void refreshCaptcha();
    } finally {
      setLoading(false);
    }
  }

  /** 【密码强度】注册模式下根据当前密码实时计算，驱动强度条渲染 */
  const strength = passwordStrength(password);

  return (
    <div className="auth-page">
      <section className="auth-panel">
        {/* 【品牌标识】 */}
        <div className="brand large">
          <span className="brand-mark" aria-hidden="true" />
          <span>Soulous <em>灵魂</em></span>
        </div>
        <p className="page-eyebrow" style={{ marginTop: 20, marginBottom: 6 }}>Welcome · 入口</p>
        <h1>把学习 <em>凭证</em><br />变成成长反馈</h1>
        <p>任务、AI 初审、分层经验和宠物成长都在一个轻量工作台里闭环。</p>

        {/* 【认证表单】 */}
        <form onSubmit={submit} className="auth-form">
          <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" autoComplete="username" />
          <input value={password} onChange={(e) => setPassword(e.target.value)} placeholder="密码" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
          {/* 【密码强度条】仅注册模式且已输入时显示，4 格按评分点亮 */}
          {mode === 'register' && password && (
            <div className="pwd-strength" aria-live="polite">
              <div className="pwd-strength-bars">
                {[0, 1, 2, 3].map((i) => (
                  <span key={i} style={{ background: i < strength.score ? strength.color : '#e5e7eb' }} />
                ))}
              </div>
              <span className="muted small" style={{ color: strength.color }}>{strength.label}</span>
            </div>
          )}
          {/* 【确认密码】仅注册模式显示 */}
          {mode === 'register' && (
            <input
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="再次输入密码"
              type="password"
              autoComplete="new-password"
            />
          )}
          {/* 【昵称】仅注册模式显示，可选 */}
          {mode === 'register' && (
            <input value={nickname} onChange={(e) => setNickname(e.target.value)} placeholder="昵称" autoComplete="nickname" />
          )}
          {/* 【邮箱】仅注册模式显示，必填；注册验证码发往此邮箱 */}
          {mode === 'register' && (
            <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="邮箱（接收验证码，必填）" type="email" autoComplete="email" inputMode="email" />
          )}
          {/* 【邮箱验证码区域】仅注册模式：填码输入框 + 发送验证码按钮（带 60s 冷却） */}
          {mode === 'register' && (
            <div className="captcha-row">
              <input
                value={emailCode}
                onChange={(e) => setEmailCode(e.target.value)}
                placeholder="邮箱验证码"
                autoComplete="one-time-code"
                inputMode="numeric"
                maxLength={6}
                aria-label="邮箱验证码"
              />
              <button
                type="button"
                className="secondary-button"
                disabled={sendingCode || codeCooldown > 0}
                onClick={() => void sendCode()}
              >
                {sendingCode ? '发送中…' : codeCooldown > 0 ? `重新发送(${codeCooldown}s)` : '发送验证码'}
              </button>
            </div>
          )}
          {/* 【发码成功提示】 */}
          {mode === 'register' && codeHint && <p className="muted small" style={{ color: 'var(--sage, #4b7)' }}>{codeHint}</p>}
          {/* 【密码要求提示】仅注册模式显示 */}
          {mode === 'register' && (
            <p className="muted small">密码 8-72 位，需包含字母、数字、符号中的至少两类，不能含空格或用户名。</p>
          )}
          {/* 【图形验证码区域】仅登录模式：输入框 + 验证码图片 */}
          {mode === 'login' && (
            <div className="captcha-row">
              <input
                value={captchaCode}
                onChange={(e) => setCaptchaCode(e.target.value)}
                placeholder="验证码"
                autoComplete="off"
                maxLength={8}
                aria-label="验证码"
              />
              {captchaImage ? (
                /**
                 * 【验证码图片】点击可刷新，支持键盘操作
                 * 使用 img 标签展示 Base64 图片数据
                 * 添加 role="button" 和 tabIndex 以支持键盘交互
                 */
                <img
                  src={captchaImage}
                  alt="点击刷新验证码"
                  title="点击换一张"
                  role="button"
                  tabIndex={0}
                  aria-busy={refreshing}
                  aria-disabled={refreshing}
                  onClick={() => { if (!refreshing) void refreshCaptcha(); }}
                  onKeyDown={(e) => {
                    if ((e.key === 'Enter' || e.key === ' ') && !refreshing) {
                      e.preventDefault();
                      void refreshCaptcha();
                    }
                  }}
                  draggable={false}
                  style={{ cursor: refreshing ? 'wait' : 'pointer', height: 40, borderRadius: 6, opacity: refreshing ? 0.6 : 1 }}
                />
              ) : (
                /* 【获取验证码按钮】图片加载失败时的降级方案 */
                <button type="button" className="secondary-button" disabled={refreshing} onClick={() => void refreshCaptcha()}>
                  {refreshing ? '加载中…' : '获取验证码'}
                </button>
              )}
            </div>
          )}
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button full-width" disabled={loading}>{loading ? '请稍候...' : mode === 'login' ? '登录' : '注册'}</button>
        </form>
        {/* 【模式切换按钮】登录 <-> 注册 */}
        <button className="text-button" onClick={() => {
          setMode(mode === 'login' ? 'register' : 'login');
          setError('');
          setCodeHint('');
          void refreshCaptcha();
        }}>
          {mode === 'login' ? '创建新账号' : '使用已有账号登录'}
        </button>
      </section>
    </div>
  );
}
