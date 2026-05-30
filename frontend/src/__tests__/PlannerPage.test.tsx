import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 【Mock 接口层】
 * 模拟 api 模块中与规划页面相关的 API 方法，
 * 包括获取 AI 信息、获取目标列表、开始新目标对话、开始打卡等。
 * 使用默认返回值（如 aiInfo 返回 mock provider）确保组件初始化不报错。
 */
vi.mock('../api', () => ({
  api: {
    aiInfo: vi.fn().mockResolvedValue({ provider: 'mock', available: 'false', model: '' }),
    sessionActiveGoals: vi.fn().mockResolvedValue([]),
    sessionAllGoals: vi.fn().mockResolvedValue([]),
    sessionStartNewGoal: vi.fn(),
    sessionStartCheckIn: vi.fn()
  }
}));

import { api } from '../api';
import { PlannerPage } from '../pages/PlannerPage';

/**
 * 【PlannerPage 组件测试套件】
 * 覆盖规划页面的核心交互场景：新建目标对话、查看目标列表并恢复会话、错误处理。
 * 测试了用户输入目标后发起 AI 对话拆解的完整流程。
 */
describe('PlannerPage', () => {
  beforeEach(() => {
    // 【重置会话相关 mock】确保每个测试从干净状态开始
    vi.mocked(api.sessionStartNewGoal).mockReset();
    vi.mocked(api.sessionStartCheckIn).mockReset();
    // 【默认返回空目标列表】模拟新用户没有历史目标的初始状态
    vi.mocked(api.sessionActiveGoals).mockResolvedValue([]);
    vi.mocked(api.sessionAllGoals).mockResolvedValue([]);
  });

  /**
   * 【测试：从目标输入框发起对话拆解会话】
   * 模拟用户输入目标（如"学透 React Hooks"）后点击"开始对话拆解"按钮。
   * 预期：API sessionStartNewGoal 被调用并传入目标文本，
   *       返回的 AI 助手回复（如"你每周可投入几小时？"）应渲染在页面上。
   *       这是规划流程的第一步——用户输入目标，AI 开始引导式提问。
   */
  it('starts a conversational planning session from the goal input', async () => {
    vi.mocked(api.sessionStartNewGoal).mockResolvedValue({
      id: 1,
      goalId: 10,
      goalTitle: '学透 React Hooks',
      kind: 'NEW_GOAL',
      state: 'DRAFTING',
      turnCount: 2,
      pendingPlan: null,
      turns: [
        { id: 1, idx: 0, role: 'USER', content: '学透 React Hooks' },
        { id: 2, idx: 1, role: 'ASSISTANT', content: '你每周可投入几小时？' }
      ],
      suggestedActions: ['just_give_me_a_plan', 'abandon'],
      duplicateCandidates: []
    });

    render(<PlannerPage onRefresh={vi.fn()} />);

    const input = screen.getByPlaceholderText(/3 个月内通过日语 N4/);
    await userEvent.type(input, '学透 React Hooks');
    await userEvent.click(screen.getByRole('button', { name: /开始对话拆解/ }));

    await waitFor(() => expect(api.sessionStartNewGoal).toHaveBeenCalledWith('学透 React Hooks'));
    expect(await screen.findByText('你每周可投入几小时？')).toBeInTheDocument();
  });

  /**
   * 【测试：渲染我的目标列表并恢复已有目标的会话】
   * 模拟用户已有活跃目标（日语 N4，4 个任务完成 1 个），
   * 点击"推进"按钮后发起打卡（check-in）会话。
   * 预期：目标列表正确显示任务进度（1/4 完成），
   *       点击推进后 API sessionStartCheckIn 被调用，
   *       AI 的打卡回复应渲染在页面上。
   */
  it('renders my-goals list with progress and lets user resume one', async () => {
    vi.mocked(api.sessionActiveGoals).mockResolvedValue([
      {
        id: 7,
        title: '日语 N4',
        status: 'ACTIVE',
        targetDate: null,
        sessionCount: 2,
        lastSessionAt: null,
        updatedAt: null,
        totalTasks: 4,
        completedTasks: 1,
        openTasks: 3
      }
    ]);
    vi.mocked(api.sessionStartCheckIn).mockResolvedValue({
      id: 5,
      goalId: 7,
      goalTitle: '日语 N4',
      kind: 'CHECK_IN',
      state: 'DRAFTING',
      turnCount: 1,
      pendingPlan: null,
      turns: [{ id: 9, idx: 0, role: 'ASSISTANT', content: '看到你已完成 1 项任务。' }],
      suggestedActions: [],
      duplicateCandidates: []
    });

    render(<PlannerPage onRefresh={vi.fn()} />);

    expect(await screen.findByText('日语 N4')).toBeInTheDocument();
    expect(screen.getByText(/任务 1\/4 完成/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /推进/ }));

    await waitFor(() => expect(api.sessionStartCheckIn).toHaveBeenCalledWith(7));
    expect(await screen.findByText('看到你已完成 1 项任务。')).toBeInTheDocument();
  });

  /**
   * 【测试：启动会话失败时显示服务器错误信息】
   * 模拟 AI 服务不可用导致 sessionStartNewGoal 抛出错误。
   * 预期：错误信息"AI 服务不可用"应内联显示在页面上，
   *       确保用户在 AI 服务故障时能得到明确反馈。
   */
  it('shows server error when starting a session fails', async () => {
    vi.mocked(api.sessionStartNewGoal).mockRejectedValue(new Error('AI 服务不可用'));
    render(<PlannerPage onRefresh={vi.fn()} />);

    const input = screen.getByPlaceholderText(/3 个月内通过日语 N4/);
    await userEvent.type(input, '学英语');
    await userEvent.click(screen.getByRole('button', { name: /开始对话拆解/ }));

    expect(await screen.findByText('AI 服务不可用')).toBeInTheDocument();
  });
});
