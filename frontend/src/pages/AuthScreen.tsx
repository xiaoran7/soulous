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
export function AuthScreen({ onAuthed, message }: { onAuthed: () => void; message: string }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  /** 【确认密码】仅注册模式显示 */
  const [confirmPassword, setConfirmPassword] = useState('');
  /** 【昵称】仅注册模式显示，可选 */
  const [nickname, setNickname] = useState('');
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

  /**
   * 【提交表单】登录或注册
   * 校验顺序：
   * 1. 验证码已加载
   * 2. 验证码已输入
   * 3. 注册模式下校验密码强度和确认密码
   * 4. 调用对应 API
   * 5. 失败时自动刷新验证码
   */
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!captchaId || !captchaImage) {
      setError('验证码还未加载，请稍候');
      return;
    }
    if (!captchaCode.trim()) {
      setError('请输入验证码');
      return;
    }
    if (mode === 'register') {
      const reason = validatePassword(password, username);
      if (reason) { setError(reason); return; }
      if (password !== confirmPassword) { setError('两次输入的密码不一致'); return; }
    }
    setError('');
    setLoading(true);
    try {
      if (mode === 'login') await api.login(username, password, captchaId, captchaCode);
      else await api.register(username, password, confirmPassword, nickname, captchaId, captchaCode);
      onAuthed();
    } catch (err) {
      setError(err instanceof Error ? err.message : '认证失败');
      void refreshCaptcha();
    } finally {
      setLoading(false);
    }
  }

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
          {/* 【密码要求提示】仅注册模式显示 */}
          {mode === 'register' && (
            <p className="muted small">密码 8-72 位，需包含字母、数字、符号中的至少两类，不能含空格或用户名。</p>
          )}
          {/* 【验证码区域】输入框 + 验证码图片 */}
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
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button full-width" disabled={loading}>{loading ? '请稍候...' : mode === 'login' ? '登录' : '注册'}</button>
        </form>
        {/* 【模式切换按钮】登录 <-> 注册 */}
        <button className="text-button" onClick={() => {
          setMode(mode === 'login' ? 'register' : 'login');
          setError('');
          void refreshCaptcha();
        }}>
          {mode === 'login' ? '创建新账号' : '使用已有账号登录'}
        </button>
      </section>
    </div>
  );
}
