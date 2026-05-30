/**
 * 【图片灯箱/大图预览组件】
 * 全屏模态图片查看器，支持：
 * - 键盘导航（← → 切换图片，Esc 关闭）
 * - 左右箭头按钮切换
 * - 原图下载
 * - 点击背景关闭
 * - 多图计数器显示
 * - 打开时锁定 body 滚动
 *
 * 被 ProofUploader、ProofThumbStrip、ClickableAvatar 等组件调用。
 */
import React, { useCallback, useEffect } from 'react';
import { ChevronLeft, ChevronRight, X, Download } from 'lucide-react';

export function ImageLightbox({
  urls,
  index,
  onClose,
  onNavigate
}: {
  /** 【图片 URL 数组】 */
  urls: string[];
  /** 【当前显示的图片索引】 */
  index: number;
  /** 【关闭回调】 */
  onClose: () => void;
  /** 【切换图片回调，传入新的索引值】 */
  onNavigate: (next: number) => void;
}) {
  const total = urls.length;
  const current = urls[index];

  /** 【上一张图片（循环轮播）】 */
  const goPrev = useCallback(() => onNavigate((index - 1 + total) % total), [index, total, onNavigate]);
  /** 【下一张图片（循环轮播）】 */
  const goNext = useCallback(() => onNavigate((index + 1) % total), [index, total, onNavigate]);

  useEffect(() => {
    /**
     * 【键盘事件处理】
     * Escape 关闭灯箱，左右箭头切换图片。
     * 同时锁定 body 滚动，防止背景页面跟随滚动。
     */
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowLeft' && total > 1) goPrev();
      else if (e.key === 'ArrowRight' && total > 1) goNext();
    }
    window.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [onClose, goPrev, goNext, total]);

  if (!current) return null;

  return (
    <div className="lightbox-backdrop" onClick={onClose}>
      <button className="lightbox-btn lightbox-close" onClick={(e) => { e.stopPropagation(); onClose(); }} title="关闭 (Esc)">
        <X size={20} />
      </button>
      <a
        className="lightbox-btn lightbox-download"
        href={current}
        download
        onClick={(e) => e.stopPropagation()}
        title="下载原图"
      >
        <Download size={18} />
      </a>
      {total > 1 && (
        <>
          <button className="lightbox-btn lightbox-prev" onClick={(e) => { e.stopPropagation(); goPrev(); }} title="上一张 (←)">
            <ChevronLeft size={22} />
          </button>
          <button className="lightbox-btn lightbox-next" onClick={(e) => { e.stopPropagation(); goNext(); }} title="下一张 (→)">
            <ChevronRight size={22} />
          </button>
        </>
      )}
      <div className="lightbox-stage" onClick={(e) => e.stopPropagation()}>
        <img src={current} alt="凭证预览" />
        {total > 1 && <div className="lightbox-counter">{index + 1} / {total}</div>}
      </div>
    </div>
  );
}
