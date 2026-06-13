import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../api', () => ({
  api: {
    feedPet: vi.fn()
  }
}));

import { PetPage } from '../pages/PetPage';
import type { Pet } from '../types';

function petAt(level: number): Pet {
  return {
    id: 1, name: 'Feixue', avatarUrl: '', level, currentExp: 10, nextLevelExp: 108,
    mood: 80, satiety: 80, growthStage: 'GROWING', status: 'NORMAL'
  } as Pet;
}

/** 读取主舞台精灵当前播放的动画状态 */
function stageState(container: HTMLElement): string | null {
  return container.querySelector('.pet-stage-frame .pet-sprite')?.getAttribute('data-pet-state') ?? null;
}

/**
 * 【PetPage 动作解锁测试】9 宫格动作预览已按 stitch 设计精简为「玩耍/休息」循环切换，
 * 解锁语义不变（PET_ACTION_UNLOCK_LEVEL 仍是唯一数据源）：玩耍只在已解锁的活泼动作间循环。
 */
describe('PetPage 动作解锁', () => {
  beforeEach(() => vi.clearAllMocks());

  it('Lv5：玩耍只循环已解锁的 waving（Lv3），不会播放未解锁动作', async () => {
    const { container } = render(<PetPage pet={petAt(5)} />);

    // 四个固定动作按钮 + 属性卡
    for (const name of ['喂食', '玩耍', '市场', '休息']) {
      expect(screen.getByRole('button', { name: new RegExp(name) })).toBeInTheDocument();
    }
    expect(screen.getByText('Lv.5')).toBeInTheDocument();
    expect(stageState(container)).toBe('idle');

    // Lv5 时活泼动作里只有 waving(Lv3) 解锁：连点两次都停在 waving
    await userEvent.click(screen.getByRole('button', { name: /玩耍/ }));
    expect(stageState(container)).toBe('waving');
    await userEvent.click(screen.getByRole('button', { name: /玩耍/ }));
    expect(stageState(container)).toBe('waving');

    // 休息回到 waiting（Lv1 即解锁）
    await userEvent.click(screen.getByRole('button', { name: /休息/ }));
    expect(stageState(container)).toBe('waiting');
  });

  it('满级 Lv30：玩耍按解锁顺序循环全部活泼动作', async () => {
    const { container } = render(<PetPage pet={petAt(30)} />);
    expect(stageState(container)).toBe('idle');

    // PLAY_ACTIONS 顺序：waving → jumping → running → running-right → running-left
    const playBtn = screen.getByRole('button', { name: /玩耍/ });
    await userEvent.click(playBtn);
    expect(stageState(container)).toBe('waving');
    await userEvent.click(playBtn);
    expect(stageState(container)).toBe('jumping');
    await userEvent.click(playBtn);
    expect(stageState(container)).toBe('running');
  });

  it('未领养（pet=null）时展示领养引导与去市场按钮', async () => {
    render(<PetPage pet={null} />);
    expect(await screen.findByText('领养你的第一只伙伴')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /去宠物市场/ })).toBeInTheDocument();
  });
});
