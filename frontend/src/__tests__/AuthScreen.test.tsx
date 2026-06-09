import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 【Mock 接口层】
 * 模拟 api 模块中与认证相关的 API 方法：
 * - captcha: 获取验证码图片及 ID
 * - login: 用户登录
 * - register: 用户注册
 * 用于测试登录/注册流程及验证码交互。
 */
vi.mock('../api', () => ({
  api: {
    captcha: vi.fn(),
    login: vi.fn(),
    register: vi.fn(),
    sendEmailCode: vi.fn()
  }
}));

import { api } from '../api';
import { AuthScreen } from '../pages/AuthScreen';

/**
 * 【AuthScreen 组件测试套件】
 * 覆盖认证页面的核心交互场景：登录流程、登录失败处理、注册流程。
 * 每个测试都验证了验证码的获取与提交、表单数据的正确传递、
 * 以及成功/失败后的行为（如 onAuthed 回调、错误信息展示、验证码刷新）。
 */
describe('AuthScreen', () => {
  beforeEach(() => {
    // 【重置认证相关 mock】确保每个测试从干净状态开始
    vi.mocked(api.login).mockReset();
    vi.mocked(api.register).mockReset();
    vi.mocked(api.captcha).mockReset();
    vi.mocked(api.sendEmailCode).mockReset();
    vi.mocked(api.sendEmailCode).mockResolvedValue({ ok: true });
    // 【默认返回验证码】模拟页面加载时自动获取验证码图片
    vi.mocked(api.captcha).mockResolvedValue({ id: 'cap-id', image: 'data:image/svg+xml;base64,xxx' });
  });

  /**
   * 【测试：使用验证码登录并通过回调通知父组件】
   * 模拟用户输入用户名、密码、验证码后点击登录。
   * 预期：API login 被调用，参数包含用户名、密码、验证码 ID 和用户输入的验证码值；
   *       登录成功后 onAuthed 回调应被触发，将 token 和用户信息传递给父组件。
   */
  it('logs in with the captcha returned by the server and reports back to parent', async () => {
    vi.mocked(api.login).mockResolvedValue({ token: 'jwt-token-abc', user: { id: 1 } });
    const onAuthed = vi.fn();
    render(<AuthScreen onAuthed={onAuthed} message="" />);

    await waitFor(() => expect(api.captcha).toHaveBeenCalled());

    await userEvent.type(screen.getByPlaceholderText('用户名'), 'demo');
    await userEvent.type(screen.getByPlaceholderText('密码'), 'demo123');
    await userEvent.type(screen.getByLabelText('验证码'), 'ABCD');
    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => expect(onAuthed).toHaveBeenCalled());
    expect(api.login).toHaveBeenCalledWith('demo', 'demo123', 'cap-id', 'ABCD');
  });

  /**
   * 【测试：登录失败时内联显示错误并刷新验证码】
   * 模拟 API 返回登录失败错误（如密码错误）。
   * 预期：错误信息应内联显示在页面上，onAuthed 不应被调用；
   *       验证码应在失败后自动刷新（captcha 被调用第二次），
   *       防止用户重试时使用已失效的验证码。
   */
  it('shows server error inline when login fails and refreshes the captcha', async () => {
    vi.mocked(api.login).mockRejectedValue(new Error('Invalid username or password'));
    const onAuthed = vi.fn();
    render(<AuthScreen onAuthed={onAuthed} message="" />);

    await waitFor(() => expect(api.captcha).toHaveBeenCalledTimes(1));

    await userEvent.type(screen.getByPlaceholderText('用户名'), 'demo');
    await userEvent.type(screen.getByPlaceholderText('密码'), 'wrong');
    await userEvent.type(screen.getByLabelText('验证码'), 'ABCD');
    await userEvent.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('Invalid username or password')).toBeInTheDocument();
    expect(onAuthed).not.toHaveBeenCalled();
    await waitFor(() => expect(api.captcha).toHaveBeenCalledTimes(2));
  });

  /**
   * 【测试：切换到注册模式并用邮箱验证码注册新用户】
   * 模拟用户点击"创建新账号"切换到注册表单，填写各字段后先点「发送验证码」，
   * 再填入收到的验证码后点击注册。
   * 预期：sendEmailCode 以邮箱被调用；register 以所有字段（含邮箱验证码、无图形验证码）被调用；
   *       注册成功后 onAuthed 回调被触发。
   */
  it('switches to register mode and registers a new user with an email code', async () => {
    vi.mocked(api.register).mockResolvedValue({ token: 'new-token', user: { id: 2 } });
    const onAuthed = vi.fn();
    render(<AuthScreen onAuthed={onAuthed} message="" />);

    await waitFor(() => expect(api.captcha).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: '创建新账号' }));

    await userEvent.type(screen.getByPlaceholderText('用户名'), 'newbie');
    await userEvent.type(screen.getByPlaceholderText('密码'), 'Passw0rd!');
    await userEvent.type(screen.getByPlaceholderText('再次输入密码'), 'Passw0rd!');
    await userEvent.type(screen.getByPlaceholderText('昵称'), '新同学');
    await userEvent.type(screen.getByPlaceholderText('邮箱（接收验证码，必填）'), 'newbie@example.com');
    await userEvent.click(screen.getByRole('button', { name: '发送验证码' }));
    await waitFor(() => expect(api.sendEmailCode).toHaveBeenCalledWith('newbie@example.com'));
    await userEvent.type(screen.getByLabelText('邮箱验证码'), '123456');
    await userEvent.click(screen.getByRole('button', { name: '注册' }));

    await waitFor(() => expect(onAuthed).toHaveBeenCalled());
    expect(api.register).toHaveBeenCalledWith('newbie', 'Passw0rd!', 'Passw0rd!', '新同学', 'newbie@example.com', '123456');
  });
});
