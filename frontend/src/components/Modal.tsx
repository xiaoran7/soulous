/**
 * 【通用居中弹层】ModalShell
 * 用 React Portal 渲染到 document.body，避免被任何带 transform/filter 的祖先元素
 * "捕获"导致 position:fixed 失效、弹层跑到左上角或被裁剪。
 * 固定全屏遮罩 + 点击遮罩关闭 + 内容自适应宽度、视口居中。
 * 视觉：第三层玻璃（DESIGN.md Tonal Stacking——高不透明度玻璃 + 1.5px 亮白边，表示"离用户最近"）。
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
      className="modal-backdrop"
      style={{ position: 'fixed', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: 16 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="modal-glass"
        style={{ width: `min(${width}px, 92vw)`, boxSizing: 'border-box', maxHeight: '85vh', overflowY: 'auto', padding: 20 }}
      >
        {children}
      </div>
    </div>,
    document.body
  );
}
