import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../api', () => ({
  api: {
    rooms: vi.fn(),
    createRoom: vi.fn(),
    joinRoom: vi.fn(),
    deleteRoom: vi.fn(),
    leaveRoomBeacon: vi.fn()
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
 * 【SharedRooms 广场测试】列出房间→加入交给 onEnter 进沉浸态；
 * 自己的房显示删除入口，删除后刷新列表；onEnter 失败时退房回滚。
 */
describe('SharedRooms 共享自习室广场', () => {
  beforeEach(() => {
    vi.mocked(api.rooms).mockReset().mockResolvedValue([
      { id: 1, name: '图书馆三楼', ownerName: 'A', onlineCount: 2, mine: false },
      { id: 2, name: '我的房', ownerName: '我', onlineCount: 1, mine: true }
    ] as never);
    vi.mocked(api.joinRoom).mockReset().mockResolvedValue(ROOM_DETAIL as never);
    vi.mocked(api.createRoom).mockReset().mockResolvedValue(ROOM_DETAIL as never);
    vi.mocked(api.deleteRoom).mockReset().mockResolvedValue({} as never);
    vi.mocked(api.leaveRoomBeacon).mockReset();
  });

  it('列出房间→加入后把房间详情交给 onEnter（由上层进入沉浸态）', async () => {
    const onEnter = vi.fn().mockResolvedValue(undefined);
    render(<SharedRooms onEnter={onEnter} />);
    await waitFor(() => expect(screen.getByText('图书馆三楼')).toBeInTheDocument());

    await userEvent.click(screen.getAllByRole('button', { name: /加入/ })[0]);
    await waitFor(() => expect(api.joinRoom).toHaveBeenCalledWith(1));
    await waitFor(() => expect(onEnter).toHaveBeenCalledWith(ROOM_DETAIL));
  });

  it('建房成功同样走 onEnter', async () => {
    const onEnter = vi.fn().mockResolvedValue(undefined);
    render(<SharedRooms onEnter={onEnter} />);
    await waitFor(() => expect(screen.getByText('图书馆三楼')).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /建房并进入/ }));
    await waitFor(() => expect(api.createRoom).toHaveBeenCalled());
    await waitFor(() => expect(onEnter).toHaveBeenCalledWith(ROOM_DETAIL));
  });

  it('onEnter 失败时退房回滚并展示错误，停留在广场', async () => {
    const onEnter = vi.fn().mockRejectedValue(new Error('启动专注失败'));
    render(<SharedRooms onEnter={onEnter} />);
    await waitFor(() => expect(screen.getByText('图书馆三楼')).toBeInTheDocument());

    await userEvent.click(screen.getAllByRole('button', { name: /加入/ })[0]);
    await waitFor(() => expect(api.leaveRoomBeacon).toHaveBeenCalledWith(1));
    expect(await screen.findByText('启动专注失败')).toBeInTheDocument();
  });

  it('只有自己的房显示删除入口，确认后删除并刷新列表', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<SharedRooms onEnter={vi.fn()} />);
    await waitFor(() => expect(screen.getByText('我的房')).toBeInTheDocument());

    const delButtons = screen.getAllByRole('button', { name: /删除自习室/ });
    expect(delButtons).toHaveLength(1); // 只有 mine 的房有删除入口

    await userEvent.click(delButtons[0]);
    await waitFor(() => expect(api.deleteRoom).toHaveBeenCalledWith(2));
    expect(api.rooms).toHaveBeenCalledTimes(2); // 初始 + 删除后刷新
  });
});
