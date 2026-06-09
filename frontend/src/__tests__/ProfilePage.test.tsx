import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

vi.mock('../api', () => ({
  api: {
    pet: vi.fn(),
    petOwned: vi.fn(),
    wallet: vi.fn(),
    checkinStatus: vi.fn()
  }
}));

import { api } from '../api';
import { ProfilePage } from '../pages/ProfilePage';
import type { User } from '../types';

const USER: User = { id: 7, username: 'u7', nickname: '小七', email: 'u7@example.com', avatarUrl: '', role: 'USER', coinBalance: 0 };

/**
 * 【ProfilePage 测试】验证 item 12：我的页展示金币/连续打卡/宠物数/流水等真实数据。
 */
describe('ProfilePage 数据概览', () => {
  beforeEach(() => {
    vi.mocked(api.pet).mockReset().mockResolvedValue({ id: 1, name: '飞雪', level: 4, currentExp: 30, nextLevelExp: 200, mood: 80, satiety: 80, growthStage: 'GROWING', status: 'NORMAL', active: true } as never);
    vi.mocked(api.petOwned).mockReset().mockResolvedValue([{ id: 1, name: '飞雪', level: 4, active: true }, { id: 2, name: '炭炭', level: 1, active: false }] as never);
    vi.mocked(api.wallet).mockReset().mockResolvedValue({ balance: 88, ledger: [{ id: 1, amount: 10, balanceAfter: 88, source: 'CHECKIN', reason: '每日打卡', createdAt: '2026-06-09T10:00:00' }] } as never);
    vi.mocked(api.checkinStatus).mockReset().mockResolvedValue({ checkedInToday: true, streak: 6, balance: 88 } as never);
  });

  it('展示金币余额、连续打卡、拥有宠物数与流水', async () => {
    render(<ProfilePage user={USER} />);
    await waitFor(() => expect(screen.getByText('88')).toBeInTheDocument()); // 金币
    expect(screen.getByText('6')).toBeInTheDocument();   // 连续打卡天数
    expect(screen.getByText('2')).toBeInTheDocument();   // 拥有宠物数
    expect(screen.getByText('每日打卡')).toBeInTheDocument(); // 流水来源
    expect(screen.getByText('+10')).toBeInTheDocument();      // 流水金额
  });
});
