import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../api', () => ({
  api: {
    petSpecies: vi.fn(),
    petOwned: vi.fn(),
    wallet: vi.fn(),
    adoptPet: vi.fn(),
    buyPet: vi.fn(),
    setActivePet: vi.fn()
  }
}));

import { api } from '../api';
import { PetMarket } from '../components/PetMarket';

const SPECIES = [
  { id: 1, slug: 'feixue', name: '飞雪', rarity: 'COMMON', price: 0, starter: true, spritePath: '/x', description: '入门' },
  { id: 3, slug: 'ember', name: '炭炭', rarity: 'RARE', price: 120, starter: false, spritePath: '/x', description: '稀有' }
];

/**
 * 【PetMarket 测试】验证 item 6：无宠物时入门款显示「免费领养」并能领养；付费款显示价格。
 */
describe('PetMarket 宠物市场', () => {
  beforeEach(() => {
    vi.mocked(api.petSpecies).mockReset().mockResolvedValue(SPECIES as never);
    vi.mocked(api.petOwned).mockReset().mockResolvedValue([] as never);
    vi.mocked(api.wallet).mockReset().mockResolvedValue({ balance: 50, ledger: [] } as never);
    vi.mocked(api.adoptPet).mockReset().mockResolvedValue({ id: 9 } as never);
  });

  it('无宠物时：入门款可免费领养、付费款显示价格；领养调用接口并回调刷新', async () => {
    const onChanged = vi.fn();
    render(<PetMarket onClose={() => {}} onChanged={onChanged} />);

    await waitFor(() => expect(screen.getByText('飞雪')).toBeInTheDocument());
    // 余额展示
    expect(screen.getByText('50')).toBeInTheDocument();
    // 入门款（feixue）→ 免费领养；付费款（ember 120）→ 按钮含价格 120
    const adoptBtn = screen.getByRole('button', { name: '免费领养' });
    const priceBtn = screen.getAllByRole('button').find((b) => /120/.test(b.textContent || ''));
    expect(priceBtn).toBeTruthy();

    await userEvent.click(adoptBtn);
    await waitFor(() => expect(api.adoptPet).toHaveBeenCalledWith('feixue'));
    expect(onChanged).toHaveBeenCalled();
  });
});
