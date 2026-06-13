import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../api', () => ({
  api: {
    checkinStatus: vi.fn(),
    checkin: vi.fn()
  }
}));
vi.mock('../components/TrendChart', () => ({ default: () => <div data-testid="trend" /> }));

import { api } from '../api';
import { Dashboard, resetCheckinCache } from '../pages/Dashboard';

const noop = () => {};

/**
 * 【Dashboard 每日签到测试】签到已并入问候卡（独立签到卡按 stitch 设计弃用）：
 * 未签到可领取，领取后调用接口、展示奖励文案，并用响应里的宠物快照局部刷新（onPetSync），
 * 不再整页重拉。
 */
describe('Dashboard 每日签到', () => {
  beforeEach(() => {
    resetCheckinCache(); // 清模块缓存，避免用例间状态串扰
    vi.mocked(api.checkinStatus).mockReset();
    vi.mocked(api.checkin).mockReset();
  });

  it('未签到时可领取，领取后展示奖励并用宠物快照局部刷新', async () => {
    vi.mocked(api.checkinStatus).mockResolvedValue({ checkedInToday: false, streak: 2, balance: 50 });
    const pet = { id: 1, name: 'Feixue', level: 3, currentExp: 28, nextLevelExp: 108 } as never;
    vi.mocked(api.checkin).mockResolvedValue({ claimed: true, streak: 3, expReward: 18, coinReward: 14, balance: 64, pet });
    const onPetSync = vi.fn();

    render(
      <Dashboard tasks={[]} pet={null} summary={null}
        onRefresh={noop} onPetSync={onPetSync} onOpenTasks={noop} onOpenReview={noop} onOpenPet={noop} />
    );

    // 问候卡内签到状态加载完成：奖励行显示连续天数
    await waitFor(() => expect(screen.getByText('连续打卡 2 天')).toBeInTheDocument());
    const claimBtn = screen.getByRole('button', { name: '签到领取' });

    await userEvent.click(claimBtn);

    await waitFor(() => expect(api.checkin).toHaveBeenCalledTimes(1));
    // 用响应里的宠物快照局部刷新，而不是整页重拉
    expect(onPetSync).toHaveBeenCalledWith(pet);
    // 领取后展示奖励文案 + 按钮变已签到
    expect(await screen.findByText(/\+18 经验/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '已签到 ✓' })).toBeDisabled();
  });

  it('已签到时按钮禁用、不可重复领取', async () => {
    vi.mocked(api.checkinStatus).mockResolvedValue({ checkedInToday: true, streak: 5, balance: 80 });
    render(
      <Dashboard tasks={[]} pet={null} summary={null}
        onRefresh={noop} onPetSync={noop} onOpenTasks={noop} onOpenReview={noop} onOpenPet={noop} />
    );
    await waitFor(() => expect(screen.getByRole('button', { name: '已签到 ✓' })).toBeDisabled());
    expect(screen.getByText('连续打卡 5 天')).toBeInTheDocument();
    expect(api.checkin).not.toHaveBeenCalled();
  });
});
