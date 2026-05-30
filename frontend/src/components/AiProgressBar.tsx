/**
 * 【AI 进度条组件】
 * 用于 AI 长耗时调用（拆解、对话、审核等）的进度指示器。
 * 由于 AI 处理时间不可预测，采用加权递增模拟策略：
 * - 激活时从 2% 开始，按指数衰减速率爬升到 ~92%
 * - 完成时弹到 100% 并在 700ms 后淡出
 * - 越接近 92% 增速越慢，避免用户感觉卡住
 *
 * 使用方式：
 *   <AiProgressBar active={busy} label="AI 正在拆解目标..." />
 *
 * 可通过 estimateMs 控制模拟进度的爬升速度（默认 8 秒到达 92%）。
 */
import React, { useEffect, useRef, useState } from 'react';
import { Loader, Sparkles } from 'lucide-react';

/**
 * AI 进度条 — 用于 AI 长耗时调用（拆解、对话、审核等）。
 * 真实进度不可得，通过加权递增模拟，给用户即时反馈。
 *
 * 使用方式：
 *   <AiProgressBar active={busy} label="AI 正在拆解目标..." />
 * 当 active 变为 true 时，自动从 0 开始爬升并停在 ~92%；
 * 当 active 再次变为 false 时，弹到 100% 并淡出。
 */
export function AiProgressBar({
  active,
  label = 'AI 思考中...',
  hint,
  estimateMs = 8000
}: {
  /** 【是否激活：true 开始模拟进度，false 触发完成动画】 */
  active: boolean;
  /** 【进度条左侧的提示文本】 */
  label?: string;
  /** 【进度条下方的辅助提示文本】 */
  hint?: string;
  /** 【预估完成时间（毫秒），控制模拟进度的爬升速率，默认 8 秒】 */
  estimateMs?: number;
}) {
  /** 【当前模拟进度百分比（0-100）】 */
  const [progress, setProgress] = useState(0);
  /** 【组件是否可见（淡出动画期间仍为 true）】 */
  const [visible, setVisible] = useState(false);
  /** 【是否已完成（切换图标和文案）】 */
  const [done, setDone] = useState(false);
  /** 【进度推进定时器引用】 */
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  /** 【淡出延迟定时器引用】 */
  const fadeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (active) {
      /**
       * 【激活阶段】
       * 清除可能残留的淡出定时器，重置状态，
       * 启动定时器以指数衰减速率推进进度到 92%。
       */
      if (fadeTimerRef.current) { clearTimeout(fadeTimerRef.current); fadeTimerRef.current = null; }
      setVisible(true);
      setDone(false);
      setProgress(2);
      const tickMs = 180;
      // 总步数大约让 92% 在 estimateMs 时到达
      const stepsToTarget = Math.max(20, Math.floor(estimateMs / tickMs));
      let elapsedSteps = 0;
      timerRef.current = setInterval(() => {
        elapsedSteps += 1;
        setProgress((prev) => {
          const targetCeil = 92;
          // 使用指数衰减：越接近 92 增速越慢
          const remaining = targetCeil - prev;
          if (remaining <= 0.5) return prev;
          const base = remaining / stepsToTarget;
          const jitter = base * (0.7 + Math.random() * 0.6);
          const next = prev + Math.max(0.3, jitter);
          return Math.min(targetCeil, next);
        });
      }, tickMs);
    } else {
      /**
       * 【完成阶段】
       * 清除推进定时器，跳到 100%，700ms 后淡出并重置。
       * 700ms 的延迟让用户能看到完成动画。
       */
      if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
      if (visible) {
        setDone(true);
        setProgress(100);
        fadeTimerRef.current = setTimeout(() => {
          setVisible(false);
          setProgress(0);
          setDone(false);
        }, 700);
      }
    }
    return () => {
      if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, estimateMs]);

  if (!visible) return null;
  return (
    <div className="ai-progress" role="status" aria-live="polite">
      <div className="ai-progress-head">
        {done ? <Sparkles size={13} /> : <Loader size={13} className="spin" />}
        <span>{done ? '已完成' : label}</span>
        <span className="ai-progress-percent">{Math.round(progress)}%</span>
      </div>
      <div className="ai-progress-track">
        <div
          className={`ai-progress-fill${done ? ' done' : ''}`}
          style={{ width: `${progress}%` }}
        />
      </div>
      {hint && !done && <div className="ai-progress-hint">{hint}</div>}
    </div>
  );
}
