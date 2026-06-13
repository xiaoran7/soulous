/**
 * 【AssistantMarkdown 渲染测试】
 * AI 回复里的 GFM 表格 / 代码块 / 标题必须渲染成真实 DOM 元素，
 * 而不是把 markdown 源码当纯文本糊出来（用户痛点：课表表格糊成竖线串）。
 */
import React from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AssistantMarkdown } from '../components/ChatConversation';

const SAMPLE = [
  '### 本周课表',
  '',
  '| 节次 | 周一 | 周二 |',
  '|------|------|------|',
  '| 1-2 节 | 数据结构 | 离散数学 |',
  '',
  '```js',
  'console.log("hi")',
  '```',
  '',
  '- 列表项甲',
  '- 列表项乙',
].join('\n');

describe('AssistantMarkdown', () => {
  it('GFM 表格渲染为 <table>，单元格内容可见', () => {
    const { container } = render(<AssistantMarkdown text={SAMPLE} />);
    const table = container.querySelector('.md-body table');
    expect(table).not.toBeNull();
    expect(table!.textContent).toContain('数据结构');
    expect(container.querySelectorAll('.md-body td').length).toBeGreaterThanOrEqual(3);
  });

  it('代码块渲染为 <pre><code>，标题渲染为 <h3>，列表渲染为 <li>', () => {
    const { container } = render(<AssistantMarkdown text={SAMPLE} />);
    expect(container.querySelector('.md-body pre code')?.textContent).toContain('console.log');
    expect(container.querySelector('.md-body h3')?.textContent).toBe('本周课表');
    expect(container.querySelectorAll('.md-body li').length).toBe(2);
  });

  it('纯文本不会被包成表格或代码块', () => {
    const { container } = render(<AssistantMarkdown text={'链表是一种线性数据结构。'} />);
    expect(container.querySelector('table')).toBeNull();
    expect(container.querySelector('pre')).toBeNull();
    expect(container.textContent).toContain('链表是一种线性数据结构。');
  });
});
