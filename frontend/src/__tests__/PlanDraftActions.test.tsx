/**
 * 【计划草案操作测试】验证用户痛点：草案不满意时必须有出口——
 * 1. 「弃用草案」按钮一键清空整份草案；
 * 2. 最后一个任务也能删除（删到零 = 弃用草案），确认弹窗给出明确提示。
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../api', () => ({
  api: {
    chatPostMessage: vi.fn(),
    chatPostMessageStream: vi.fn(),
    chatEditPlanTask: vi.fn(),
    chatDeletePlanTask: vi.fn(),
    chatDismissPlan: vi.fn(),
    chatCommit: vi.fn()
  }
}));

import { api } from '../api';
import { ChatConversation } from '../components/ChatConversation';
import type { ChatConversationView } from '../types';

const CONV_WITH_PLAN: ChatConversationView = {
  id: 7,
  title: '测试对话',
  categoryId: null,
  messages: [
    { id: 1, idx: 0, role: 'USER', content: '给我计划' },
    { id: 2, idx: 1, role: 'ASSISTANT', content: '好的（已生成计划草案 ↓）' }
  ],
  pendingPlan: {
    category: 'AI助手入门',
    tasks: [{ title: '管理任务分类', description: '了解大类名', estimatedMinutes: 10, difficulty: 'EASY', taskType: 'SIMPLE' }]
  },
  pendingClarify: null,
  suggestedActions: ['commit', 'adjust']
};

const CONV_NO_PLAN: ChatConversationView = { ...CONV_WITH_PLAN, pendingPlan: null, suggestedActions: [] };

describe('计划草案：弃用与删到零', () => {
  beforeEach(() => {
    vi.mocked(api.chatDismissPlan).mockReset().mockResolvedValue(CONV_NO_PLAN as never);
    vi.mocked(api.chatDeletePlanTask).mockReset().mockResolvedValue(CONV_NO_PLAN as never);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });
  afterEach(() => { vi.restoreAllMocks(); });

  it('「弃用草案」按钮调用 dismiss 接口并移除草案卡片', async () => {
    render(<ChatConversation conversation={CONV_WITH_PLAN} />);
    expect(screen.getByText('计划草案')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /弃用草案/ }));
    await waitFor(() => expect(api.chatDismissPlan).toHaveBeenCalledWith(7));
    await waitFor(() => expect(screen.queryByText('计划草案')).toBeNull());
  });

  it('删除最后一个任务：确认提示说明整份草案将被弃用，删除后草案卡片消失', async () => {
    render(<ChatConversation conversation={CONV_WITH_PLAN} />);
    await userEvent.click(screen.getByTitle('删除'));

    expect(vi.mocked(window.confirm).mock.calls[0][0]).toContain('整份草案将被弃用');
    await waitFor(() => expect(api.chatDeletePlanTask).toHaveBeenCalledWith(7, 0));
    await waitFor(() => expect(screen.queryByText('计划草案')).toBeNull());
  });
});
