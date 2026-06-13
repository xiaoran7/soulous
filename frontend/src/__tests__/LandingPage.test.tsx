import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { LandingPage } from '../pages/LandingPage';

/**
 * 【LandingPage 测试】落地页已按 stitch 设计精简为单屏 Hero（一个主 CTA + 导航登录 +
 * 右下角宠物）：验证各 CTA 正确触发进入登录/注册。
 */
describe('LandingPage 落地页', () => {
  it('「开始学习」进入注册，「登录」进入登录，宠物按钮进入注册', async () => {
    const onStart = vi.fn();
    render(<LandingPage onStart={onStart} />);

    // 单屏 Hero 渲染
    expect(screen.getByText('FOCUS LEARN GROW')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /开始学习/ }));
    expect(onStart).toHaveBeenLastCalledWith('register');

    await userEvent.click(screen.getByRole('button', { name: '登录' }));
    expect(onStart).toHaveBeenLastCalledWith('login');

    await userEvent.click(screen.getByTitle('和飞雪一起开始'));
    expect(onStart).toHaveBeenLastCalledWith('register');
  });
});
