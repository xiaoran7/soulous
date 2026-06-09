import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { LandingPage } from '../pages/LandingPage';

/**
 * 【LandingPage 测试】验证 item 4：落地页 CTA 正确触发进入登录/注册。
 */
describe('LandingPage 落地页', () => {
  it('「开始使用」进入注册，「登录」进入登录', async () => {
    const onStart = vi.fn();
    render(<LandingPage onStart={onStart} />);

    // 功能亮点存在
    expect(screen.getByText('宠物养成 · 市场')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '开始使用' }));
    expect(onStart).toHaveBeenLastCalledWith('register');

    await userEvent.click(screen.getByRole('button', { name: '登录' }));
    expect(onStart).toHaveBeenLastCalledWith('login');

    await userEvent.click(screen.getByRole('button', { name: '已有账号登录' }));
    expect(onStart).toHaveBeenLastCalledWith('login');
  });
});
