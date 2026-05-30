/**
 * 【通用居中弹层】ModalShell
 * 用 React Portal 渲染到 document.body，避免被任何带 transform/filter 的祖先元素
 * "捕获"导致 position:fixed 失效、弹层跑到左上角或被裁剪。
 * 固定全屏遮罩 + 点击遮罩关闭 + 内容自适应宽度、视口居中。
 */
import React from 'react';
import { createPortal } from 'react-dom';

export function ModalShell({ width = 360, children, onClose }: {
  width?: number;
  children: React.ReactNode;
  onClose: () => void;
}) {
  return createPortal(
    <div
      onClick={onClose}
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.35)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: 16 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ width: `min(${width}px, 92vw)`, boxSizing: 'border-box', maxHeight: '85vh', overflowY: 'auto', background: 'var(--paper, #fff)', border: '1px solid var(--line, #eee)', borderRadius: 12, padding: 18, boxShadow: '0 8px 30px rgba(0,0,0,0.2)' }}
      >
        {children}
      </div>
    </div>,
    document.body
  );
}
