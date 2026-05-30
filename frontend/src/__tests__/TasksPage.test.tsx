import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { StudyTask, Submission, SubmissionDetail } from '../types';

/**
 * 【Mock 接口层】
 * 模拟 api 模块中所有与任务页面相关的 API 方法，
 * 包括提交任务、查看提交详情、删除任务、AI 问答等，
 * 以便在隔离环境中测试 TasksPage 组件的 UI 交互逻辑。
 */
vi.mock('../api', () => ({
  api: {
    mySubmissions: vi.fn(),
    submitTask: vi.fn(),
    submissionDetail: vi.fn(),
    deleteTask: vi.fn(),
    startTask: vi.fn(),
    createTask: vi.fn(),
    updateTask: vi.fn(),
    answerAiQuestion: vi.fn(),
    createAppeal: vi.fn(),
    uploadScreenshot: vi.fn()
  }
}));

import { api } from '../api';
import { TasksPage } from '../pages/TasksPage';

/**
 * 【测试数据：模拟学习任务】
 * 一条处于"进行中"状态的学习任务，用于各测试用例的输入数据。
 * 涵盖任务标题、描述、类型、难度、课程名、经验等字段。
 */
const task: StudyTask = {
  id: 1,
  title: '复习栈和队列',
  description: '整理 FILO/FIFO 的差异',
  taskType: 'STUDY',
  difficulty: 'NORMAL',
  courseName: '数据结构',
  estimatedMinutes: 30,
  actualMinutes: 0,
  baseExp: 20,
  status: 'DOING',
  createdAt: '2026-05-15T10:00:00'
};

/**
 * 【测试数据：模拟提交记录】
 * 一条被 AI 拒绝的提交记录，用于测试提交详情查看、错误展示等场景。
 */
const submission: Submission = {
  id: 42,
  textProof: '完成了栈的学习',
  screenshotUrl: '',
  codeSnippet: '',
  proofLink: '',
  studyMinutes: 35,
  submitType: 'TEXT',
  status: 'AI_REJECTED',
  createdAt: '2026-05-15T11:00:00'
};

/**
 * 【测试数据：模拟提交详情（含审核反馈）】
 * 包含提交信息、关联任务、AI 审核结果和管理员评论，
 * 用于测试"查看反馈"功能中管理员评论和审核理由的渲染。
 */
const submissionDetail: SubmissionDetail = {
  submission: { ...submission, adminComment: '请补充更多代码片段。' },
  task,
  review: {
    id: 9,
    result: 'REJECTED',
    score: 45,
    relevanceScore: 30,
    completenessScore: 20,
    qualityScore: 55,
    reason: '凭证不够具体',
    suggestion: '加点代码',
    recommendedExp: 5
  }
};

/**
 * 【TasksPage 组件测试套件】
 * 覆盖任务页面的核心交互场景：提交任务、AI 追问、查看审核详情、删除任务、错误处理等。
 * 每个测试用例在 beforeEach 中重置所有 mock，确保测试间互不干扰。
 */
describe('TasksPage', () => {
  beforeEach(() => {
    // 【重置所有 API mock】确保每个测试用例从干净状态开始，避免 mock 调用计数污染
    Object.values(api).forEach((fn) => 'mockReset' in fn && fn.mockReset());
    // 【默认返回提交列表】模拟页面加载时已有提交记录
    vi.mocked(api.mySubmissions).mockResolvedValue([submission]);
  });

  /**
   * 【测试：提交任务后展示 AI 追问问题】
   * 模拟用户点击"提交任务"→"提交审核"的流程。
   * 预期：API 调用成功后，AI 返回的追问问题应显示在页面上，
   *       同时触发 onRefresh 回调刷新父组件数据。
   */
  it('submits a task and surfaces the AI follow-up question', async () => {
    vi.mocked(api.submitTask).mockResolvedValue({
      task,
      submission: { id: 42 },
      review: {},
      aiQuestion: '你具体用栈解决了什么问题？'
    } as any);
    const onRefresh = vi.fn().mockResolvedValue(undefined);
    render(<TasksPage tasks={[task]} onRefresh={onRefresh} />);

    await userEvent.click(screen.getByRole('button', { name: '提交任务' }));
    await userEvent.click(screen.getByRole('button', { name: /提交审核/ }));

    await waitFor(() => expect(api.submitTask).toHaveBeenCalled());
    expect(await screen.findByText('你具体用栈解决了什么问题？')).toBeInTheDocument();
    expect(onRefresh).toHaveBeenCalled();
  });

  /**
   * 【测试：提交失败时内联显示服务器错误信息】
   * 模拟 API 返回错误（如"提交太频繁"）。
   * 预期：错误信息应直接渲染在页面上（而非弹出 alert），
   *       确保用户体验的错误展示是内联式的。
   */
  it('shows server error inline when submission fails', async () => {
    vi.mocked(api.submitTask).mockRejectedValue(new Error('提交太频繁，请稍后再试'));
    render(<TasksPage tasks={[task]} onRefresh={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: '提交任务' }));
    await userEvent.click(screen.getByRole('button', { name: /提交审核/ }));

    expect(await screen.findByText('提交太频繁，请稍后再试')).toBeInTheDocument();
  });

  /**
   * 【测试：打开提交详情并展示管理员评论】
   * 模拟用户点击"提交与申诉"按钮查看历史提交记录，再点击"查看反馈"。
   * 预期：管理员的评论（adminComment）和 AI 审核理由（review.reason）
   *       应正确渲染在详情面板中。
   */
  it('opens submission detail and renders adminComment', async () => {
    vi.mocked(api.submissionDetail).mockResolvedValue(submissionDetail);
    render(<TasksPage tasks={[task]} onRefresh={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: '提交与申诉' }));
    await waitFor(() => expect(screen.getByText('提交 #42')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /查看反馈/ }));

    expect(await screen.findByText('请补充更多代码片段。')).toBeInTheDocument();
    expect(screen.getByText(/凭证不够具体/)).toBeInTheDocument();
  });

  /**
   * 【测试：确认后硬删除任务】
   * 模拟用户点击"删除"→"确认删除"的二次确认流程。
   * 预期：API deleteTask 被调用且传入正确的任务 ID，
   *       删除成功后触发 onRefresh 刷新列表。
   */
  it('hard-deletes a task after confirmation', async () => {
    vi.mocked(api.deleteTask).mockResolvedValue({} as any);
    const onRefresh = vi.fn().mockResolvedValue(undefined);
    render(<TasksPage tasks={[task]} onRefresh={onRefresh} />);

    await userEvent.click(screen.getByRole('button', { name: /^删除$/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));

    await waitFor(() => expect(api.deleteTask).toHaveBeenCalledWith(1));
    expect(onRefresh).toHaveBeenCalled();
  });

  /**
   * 【测试：删除失败时内联显示错误而非弹窗】
   * 模拟 API 返回删除错误。
   * 预期：错误信息应内联显示在页面上，且不应调用 window.alert。
   *       这确保了错误处理的一致性——所有错误都走内联展示而非原生弹窗。
   */
  it('surfaces delete errors inline instead of using window.alert', async () => {
    vi.mocked(api.deleteTask).mockRejectedValue(new Error('该任务无法删除'));
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    render(<TasksPage tasks={[task]} onRefresh={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /^删除$/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));

    expect(await screen.findByText('该任务无法删除')).toBeInTheDocument();
    expect(alertSpy).not.toHaveBeenCalled();
  });
});
