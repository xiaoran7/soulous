import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../api', () => ({
  api: {
    rooms: vi.fn(),
    createRoom: vi.fn(),
    joinRoom: vi.fn(),
    roomHeartbeat: vi.fn(),
    leaveRoom: vi.fn()
  }
}));

import { api } from '../api';
import { SharedRooms } from '../studyroom/SharedRooms';

const ROOM_DETAIL = {
  id: 1, name: '图书馆三楼', ownerName: 'A',
  members: [{ userId: 1, name: '我', focusing: false, focusSeconds: 0, self: true }],
  onlineCount: 1, joined: true
};

/**
 * 【SharedRooms 测试】验证 item 8：房间广场列表→加入→展示成员→开始专注上报心跳。
 */
describe('SharedRooms 共享自习室', () => {
  beforeEach(() => {
    vi.mocked(api.rooms).mockReset().mockResolvedValue([{ id: 1, name: '图书馆三楼', ownerName: 'A', onlineCount: 2 }] as never);
    vi.mocked(api.joinRoom).mockReset().mockResolvedValue(ROOM_DETAIL as never);
    vi.mocked(api.roomHeartbeat).mockReset().mockResolvedValue({ ...ROOM_DETAIL, members: [{ userId: 1, name: '我', focusing: true, focusSeconds: 0, self: true }] } as never);
  });

  it('列出房间→加入→显示成员与「开始专注」，点击后上报心跳', async () => {
    render(<SharedRooms />);
    await waitFor(() => expect(screen.getByText('图书馆三楼')).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /加入/ }));
    await waitFor(() => expect(api.joinRoom).toHaveBeenCalledWith(1));

    // 进房后展示「开始专注」与成员
    const focusBtn = await screen.findByRole('button', { name: '开始专注' });
    expect(screen.getByText(/我（我）/)).toBeInTheDocument();

    await userEvent.click(focusBtn);
    await waitFor(() => expect(api.roomHeartbeat).toHaveBeenCalledWith(1, true, 0));
  });
});
