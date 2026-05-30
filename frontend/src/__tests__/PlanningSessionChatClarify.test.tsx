import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 【Mock 接口层】
 * 仅模拟澄清提交链路用到的 sessionPostMessageStream（流式发送），
 * 返回一个不再带 pendingClarify 的新会话，模拟用户点选作答后 AI 继续对话。
 */
vi.mock('../api', () => ({
  api: {
    sessionPostMessageStream: vi.fn(),
    sessionPostMessage: vi.fn(),
    sessionCommit: vi.fn(),
    sessionAbandon: vi.fn(),
    sessionEditPlanTask: vi.fn(),
    sessionDeletePlanTask: vi.fn()
  }
}));

import { api } from '../api';
import { PlanningSessionChat } from '../components/PlanningSessionChat';
import type { SessionView } from '../types';

/** 含一道澄清选择题的会话，且 assistant 轮原文里夹带了 CLARIFY_JSON 信封（用于验证剥离）。 */
function clarifySession(): SessionView {
  return {
    id: 1,
    goalId: 10,
    goalTitle: '入门机器学习',
    kind: 'NEW_GOAL',
    state: 'DRAFTING',
    turnCount: 2,
    pendingPlan: null,
    pendingClarify: {
      questions: [
        {
          id: 'tool',
          question: '你打算用什么语言？',
          multiSelect: false,
          options: [
            { label: 'Python', hint: '适合数据/AI' },
            { label: 'Java' }
          ]
        }
      ]
    },
    turns: [
      { id: 1, idx: 0, role: 'USER', content: '入门机器学习' },
      {
        id: 2,
        idx: 1,
        role: 'ASSISTANT',
        content: '先确认一点，点选即可：\n<CLARIFY_JSON>{"questions":[{"id":"tool","question":"你打算用什么语言？","multiSelect":false,"options":[{"label":"Python"},{"label":"Java"}]}]}</CLARIFY_JSON>'
      }
    ],
    suggestedActions: ['just_give_me_a_plan', 'abandon'],
    duplicateCandidates: []
  };
}

describe('PlanningSessionChat — 澄清选项卡片', () => {
  beforeEach(() => {
    vi.mocked(api.sessionPostMessageStream).mockReset();
  });

  it('renders selectable clarify options and never leaks the raw CLARIFY_JSON envelope', () => {
    render(<PlanningSessionChat session={clarifySession()} onClose={vi.fn()} onCommitted={vi.fn()} />);

    // 选项卡渲染
    expect(screen.getByText('你打算用什么语言？')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Python/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Java/ })).toBeInTheDocument();
    // 原始信封不应泄露到聊天气泡
    expect(screen.queryByText(/CLARIFY_JSON/)).not.toBeInTheDocument();
  });

  it('composes the picked option into a message and sends it via the streaming endpoint', async () => {
    vi.mocked(api.sessionPostMessageStream).mockResolvedValue({
      ...clarifySession(),
      state: 'DRAFTING',
      pendingClarify: null,
      turns: [
        ...clarifySession().turns,
        { id: 3, idx: 2, role: 'USER', content: '你打算用什么语言？ Python' },
        { id: 4, idx: 3, role: 'ASSISTANT', content: '好的，给你安排 Python 路线。' }
      ]
    });

    render(<PlanningSessionChat session={clarifySession()} onClose={vi.fn()} onCommitted={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /Python/ }));
    await userEvent.click(screen.getByRole('button', { name: /提交选择/ }));

    await waitFor(() => expect(api.sessionPostMessageStream).toHaveBeenCalledTimes(1));
    const [, sentMessage] = vi.mocked(api.sessionPostMessageStream).mock.calls[0];
    expect(sentMessage).toContain('你打算用什么语言？');
    expect(sentMessage).toContain('Python');
  });
});
