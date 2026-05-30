import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Appeal, Submission, User } from '../types';

/**
 * 【Mock 接口层】
 * 模拟 api 模块中与管理员审核相关的 API 方法，
 * 包括获取提交列表、申诉列表、提交详情、批准/驳回提交、处理申诉、创建用户等。
 * 这些方法覆盖了管理员后台的所有核心操作。
 */
vi.mock('../api', () => ({
  api: {
    adminSubmissions: vi.fn(),
    adminAppeals: vi.fn(),
    adminSubmission: vi.fn(),
    approveSubmission: vi.fn(),
    rejectSubmission: vi.fn(),
    needMoreSubmission: vi.fn(),
    reviewAppeal: vi.fn(),
    adminCreateUser: vi.fn()
  }
}));

import { api } from '../api';
import { AdminPage } from '../pages/AdminPage';

/**
 * 【测试数据：管理员用户】
 * 角色为 ADMIN 的用户对象，用于测试管理员权限场景。
 */
const adminUser: User = {
  id: 1, username: 'admin', nickname: '审核老师', email: '', avatarUrl: '', role: 'ADMIN'
};

/**
 * 【测试数据：待审核的提交记录】
 * 一条被 AI 拒绝的提交，模拟管理员需要人工复审的场景。
 */
const submission: Submission = {
  id: 7, textProof: '提交内容', screenshotUrl: '', codeSnippet: '', proofLink: '',
  studyMinutes: 30, submitType: 'TEXT', status: 'AI_REJECTED', createdAt: '2026-05-15T10:00:00'
};

/**
 * 【测试数据：待处理的申诉记录】
 * 状态为 PENDING 的申诉，用于测试申诉审核流程。
 */
const appeal: Appeal = {
  id: 3, appealReason: '已补充材料', status: 'PENDING', adminComment: '', createdAt: '2026-05-15T10:00:00'
};

/**
 * 【测试数据：提交详情（含审核结果、任务、用户、宠物等级）】
 * 模拟管理员打开某条提交后看到的完整详情面板数据。
 */
const detail = {
  submission,
  review: { id: 9, result: 'REJECTED', score: 50, relevanceScore: 30, completenessScore: 30, qualityScore: 60, reason: '不够具体', suggestion: '加点细节', recommendedExp: 8 },
  task: { id: 11, title: '复习栈', baseExp: 20 },
  user: { id: 5, nickname: '小明', username: 'demo' },
  petLevel: 3
};

/**
 * 【AdminPage 组件测试套件】
 * 覆盖管理员审核后台的核心场景：权限校验、提交审核（批准/驳回）、申诉处理。
 * 测试数据在 beforeEach 中初始化，模拟管理员查看待审核列表和处理申诉的真实流程。
 */
describe('AdminPage', () => {
  beforeEach(() => {
    // 【重置所有 API mock】确保每个测试从干净状态开始
    Object.values(api).forEach((fn) => 'mockReset' in fn && fn.mockReset());
    // 【默认返回待审核提交列表】模拟管理员进入后台时看到的提交列表
    vi.mocked(api.adminSubmissions).mockResolvedValue([{ submission, petLevel: 3 }] as any);
    // 【默认返回待处理申诉列表】模拟管理员看到的申诉队列
    vi.mocked(api.adminAppeals).mockResolvedValue([{
      appeal,
      submission,
      task: { id: 11, title: '复习栈', baseExp: 20 },
      user: { id: 5, nickname: '小明', username: 'demo', role: 'USER' },
      petLevel: 3,
      aiReview: { id: 9, result: 'REJECTED', score: 50, relevanceScore: 30, completenessScore: 30, qualityScore: 60, reason: '不够具体', suggestion: '加点细节', recommendedExp: 8 }
    }] as any);
  });

  /**
   * 【测试：非管理员用户被拦截并显示提示】
   * 模拟普通用户（role: 'USER'）尝试访问管理员后台。
   * 预期：页面应显示"无法进入审核后台"的提示信息，
   *       确保权限校验在前端层面生效。
   */
  it('blocks non-admin users with a notice', () => {
    const normalUser: User = { ...adminUser, role: 'USER' };
    render(<AdminPage user={normalUser} onRefresh={vi.fn()} />);
    expect(screen.getByText(/无法进入审核后台/)).toBeInTheDocument();
  });

  /**
   * 【测试：打开提交详情并以自定义经验和评论批准提交】
   * 模拟管理员点击某条提交查看详情，修改发放经验值为 15，然后点击"人工通过"。
   * 预期：API approveSubmission 被调用，参数包含提交 ID、自定义经验、评论；
   *       审核完成后 onRefresh 回调应被触发刷新列表。
   */
  it('opens a submission and approves it with custom exp + comment', async () => {
    vi.mocked(api.adminSubmission).mockResolvedValue(detail as any);
    vi.mocked(api.approveSubmission).mockResolvedValue({} as any);
    const onRefresh = vi.fn().mockResolvedValue(undefined);
    render(<AdminPage user={adminUser} onRefresh={onRefresh} />);

    await waitFor(() => expect(screen.getByText('提交 #7')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /提交 #7/ }));

    await waitFor(() => expect(screen.getByText('复习栈')).toBeInTheDocument());

    const expInput = screen.getByLabelText('发放经验');
    await userEvent.clear(expInput);
    await userEvent.type(expInput, '15');

    await userEvent.click(screen.getByRole('button', { name: /人工通过/ }));

    await waitFor(() => expect(api.approveSubmission).toHaveBeenCalled());
    const [id, exp, comment] = vi.mocked(api.approveSubmission).mock.calls[0];
    expect(id).toBe(7);
    expect(exp).toBe(15);
    expect(comment).toBeTruthy();
    expect(onRefresh).toHaveBeenCalled();
  });

  /**
   * 【测试：驳回提交时服务器错误内联显示】
   * 模拟管理员驳回一条提交，但 API 返回错误（如"该提交已被处理"）。
   * 预期：错误信息应内联显示在页面上，确保并发审核冲突时用户能得到明确反馈。
   */
  it('rejects a submission and surfaces server error inline', async () => {
    vi.mocked(api.adminSubmission).mockResolvedValue(detail as any);
    vi.mocked(api.rejectSubmission).mockRejectedValue(new Error('该提交已被处理'));
    render(<AdminPage user={adminUser} onRefresh={vi.fn()} />);

    await waitFor(() => screen.getByText('提交 #7'));
    await userEvent.click(screen.getByRole('button', { name: /提交 #7/ }));
    await waitFor(() => screen.getByRole('button', { name: '驳回' }));
    await userEvent.click(screen.getByRole('button', { name: '驳回' }));

    expect(await screen.findByText('该提交已被处理')).toBeInTheDocument();
  });

  /**
   * 【测试：批准申诉队列中的一条申诉】
   * 模拟管理员点击"通过申诉"按钮处理待审核的申诉。
   * 预期：API reviewAppeal 被调用，参数包含申诉 ID（3）、审核结果（'APPROVED'）、
   *       审核评论（字符串）和推荐经验（数字），确保所有必填字段都被正确传递。
   */
  it('approves an appeal from the queue', async () => {
    vi.mocked(api.reviewAppeal).mockResolvedValue({} as any);
    render(<AdminPage user={adminUser} onRefresh={vi.fn()} />);

    await waitFor(() => expect(screen.getByText('申诉 #3')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /通过申诉/ }));

    await waitFor(() => expect(api.reviewAppeal).toHaveBeenCalledWith(3, 'APPROVED', expect.any(String), expect.any(Number)));
  });
});
