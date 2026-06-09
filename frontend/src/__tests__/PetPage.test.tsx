import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

vi.mock('../api', () => ({
  api: {
    petLogs: vi.fn().mockResolvedValue([]),
    feedPet: vi.fn(),
    setPetAvatar: vi.fn(),
    uploadScreenshot: vi.fn()
  }
}));
// 趋势图懒加载 recharts，测试里替换为空组件避免重依赖
vi.mock('../components/PetCharts', () => ({ default: () => <div data-testid="trend" /> }));

import { PetPage } from '../pages/PetPage';
import type { Pet } from '../types';

function petAt(level: number): Pet {
  return {
    id: 1, name: 'Feixue', avatarUrl: '', level, currentExp: 10, nextLevelExp: 108,
    mood: 80, satiety: 80, growthStage: 'GROWING', status: 'NORMAL'
  } as Pet;
}

/**
 * 【PetPage 动作解锁测试】验证 item 14：动作按等级解锁，未达等级锁定不可选，满级全开。
 */
describe('PetPage 动作解锁', () => {
  beforeEach(() => vi.clearAllMocks());

  it('Lv5：解锁 4 个、锁定 5 个；待机可选、复核锁定', async () => {
    const { container } = render(<PetPage pet={petAt(5)} />);
    await waitFor(() => expect(container.querySelectorAll('.pet-action')).toHaveLength(9));

    // Lv5 解锁：idle/waiting/waving(3)/failed(5) = 4；其余 5 个锁定
    expect(container.querySelectorAll('.pet-action.locked')).toHaveLength(5);
    expect(screen.getByRole('button', { name: /待机/ })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: /低落/ })).not.toBeDisabled(); // Lv5
    expect(screen.getByRole('button', { name: /奔跑/ })).toBeDisabled();     // Lv8
    expect(screen.getByRole('button', { name: /复核/ })).toBeDisabled();     // Lv30
  });

  it('满级 Lv30：全部 9 个动作解锁', async () => {
    const { container } = render(<PetPage pet={petAt(30)} />);
    await waitFor(() => expect(container.querySelectorAll('.pet-action')).toHaveLength(9));
    expect(container.querySelectorAll('.pet-action.locked')).toHaveLength(0);
    expect(screen.getByRole('button', { name: /复核/ })).not.toBeDisabled();
  });

  it('未领养（pet=null）时展示领养引导与去市场按钮', async () => {
    render(<PetPage pet={null} />);
    expect(await screen.findByText('领养你的第一只伙伴')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /去宠物市场/ })).toBeInTheDocument();
  });
});
