/**
 * 【学习凭证上传组件】
 * 提供图片上传功能，支持三种上传方式：点击选择、拖拽、粘贴。
 * 核心特性：
 * - 自动压缩大图片（通过 compressImageIfNeeded）
 * - 上传进度实时显示（基于 XHR progress 事件）
 * - 多文件批量上传（最多 20 张）
 * - 上传完成后可预览、删除
 * - 全局粘贴事件监听（支持从剪贴板粘贴截图）
 *
 * 被 TasksPage 的任务提交表单调用，用于上传学习凭证截图。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { X, AlertCircle, Loader, ImagePlus } from 'lucide-react';
import { api } from '../api';
import { compressImageIfNeeded, formatBytes } from '../utils/imageCompress';
import { ImageLightbox } from './ImageLightbox';
import type { Submission } from '../types';

/** 【单张图片大小上限：20MB】 */
const MAX_BYTES = 20 * 1024 * 1024;
/** 【最大上传文件数】 */
const MAX_FILES = 20;

/**
 * 【已上传凭证图片接口】
 * 表示一张已成功上传的凭证图片，包含唯一 ID、可访问 URL、文件名和大小。
 */
export interface ProofImage {
  id: string;
  url: string;
  name: string;
  bytes: number;
}

/**
 * 【上传中文件项接口】
 * 表示正在上传的文件，包含进度百分比和可能的错误信息。
 * 上传成功后从 pending 列表移除，失败时显示错误并在 4 秒后自动清除。
 */
type PendingItem = {
  id: string;
  name: string;
  bytes: number;
  progress: number;
  error?: string;
};

/**
 * 【凭证上传器组件】
 *
 * @param images - 已上传成功的图片列表
 * @param onChange - 图片列表变更回调（添加/删除后调用）
 * @param pasteScopeRef - 可选的 DOM 引用，指定额外的粘贴事件监听范围
 */
export function ProofUploader({
  images,
  onChange,
  pasteScopeRef
}: {
  images: ProofImage[];
  onChange: (next: ProofImage[]) => void;
  /** Optional element on which paste events should also be captured (in addition to the uploader). */
  pasteScopeRef?: React.RefObject<HTMLElement | null>;
}) {
  /** 【上传中的文件列表】 */
  const [pending, setPending] = useState<PendingItem[]>([]);
  /** 【拖拽激活状态（用于视觉反馈）】 */
  const [dragActive, setDragActive] = useState(false);
  /** 【错误提示信息】 */
  const [error, setError] = useState('');
  /** 【灯箱预览的图片索引，null 表示关闭】 */
  const [lightboxIdx, setLightboxIdx] = useState<number | null>(null);
  /** 【隐藏的 file input 引用】 */
  const fileInputRef = useRef<HTMLInputElement>(null);
  /** 【组件根元素引用，用于粘贴事件范围检测】 */
  const rootRef = useRef<HTMLDivElement>(null);

  /** 【剩余可上传槽位数】 */
  const slotsLeft = MAX_FILES - images.length - pending.length;

  /**
   * 【上传单张图片】
   * 完整的单文件上传流程：
   * 1. 生成唯一 ID
   * 2. 校验文件类型（必须是图片）
   * 3. 自动压缩（如果需要）
   * 4. 校验压缩后文件大小
   * 5. 调用 API 上传并追踪进度
   * 6. 成功后更新图片列表，失败后显示错误
   *
   * @param raw - 原始 File 对象
   */
  const uploadOne = useCallback(async (raw: File) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    if (!raw.type.startsWith('image/')) {
      setError(`「${raw.name}」不是图片`);
      return;
    }
    const pendingItem: PendingItem = { id, name: raw.name || '粘贴的截图', bytes: raw.size, progress: 0 };
    setPending((p) => [...p, pendingItem]);
    try {
      // 凭证图只需看清内容即可：限制最长边 1600px、超过 ~1MB 即重编码，
      // 保证压缩后稳稳低于反代上限，又保留足够的可读细节
      const file = await compressImageIfNeeded(raw, { maxDimension: 1600, maxBytes: 1024 * 1024 });
      if (file.size > MAX_BYTES) {
        throw new Error(`图片过大（${formatBytes(file.size)}），上限 ${MAX_BYTES / 1024 / 1024}MB`);
      }
      setPending((p) => p.map((it) => it.id === id ? { ...it, bytes: file.size } : it));
      const result = await api.uploadScreenshot(file, (pct) => {
        setPending((p) => p.map((it) => it.id === id ? { ...it, progress: pct } : it));
      });
      const newImg: ProofImage = { id, url: result.url, name: file.name, bytes: file.size };
      onChange([...images, newImg]);
    } catch (err) {
      const msg = err instanceof Error ? err.message : '上传失败';
      setError(msg);
      setPending((p) => p.map((it) => it.id === id ? { ...it, error: msg, progress: 0 } : it));
      setTimeout(() => setPending((p) => p.filter((it) => it.id !== id)), 4000);
      return;
    }
    setPending((p) => p.filter((it) => it.id !== id));
  }, [images, onChange]);

  /**
   * 【批量添加文件】
   * 将 FileList 或 File 数组过滤为图片文件，检查剩余槽位后逐个上传。
   * 超出上限时给出友好提示。
   *
   * @param files - 待上传的文件列表
   */
  const addFiles = useCallback(async (files: FileList | File[] | null | undefined) => {
    if (!files) return;
    setError('');
    const arr = Array.from(files).filter((f) => f && f.type.startsWith('image/'));
    if (arr.length === 0) {
      setError('没有可上传的图片');
      return;
    }
    const remaining = MAX_FILES - images.length - pending.length;
    if (remaining <= 0) {
      setError(`最多上传 ${MAX_FILES} 张图片`);
      return;
    }
    const toUpload = arr.slice(0, remaining);
    if (arr.length > toUpload.length) {
      setError(`只能再上传 ${remaining} 张，已忽略其余 ${arr.length - toUpload.length} 张`);
    }
    for (const f of toUpload) {
      void uploadOne(f);
    }
  }, [images.length, pending.length, uploadOne]);

  // Global paste handler — works on document; also listens on the uploader root so it works when scope element is focused
  /**
   * 【全局粘贴事件监听效果】
   * 在 document 上监听 paste 事件，当用户从剪贴板粘贴图片时自动上传。
   * 仅在以下位置触发：上传组件内部 或 pasteScopeRef 指定的元素内部。
   * 这样用户可以在聊天输入框等位置直接粘贴截图。
   */
  useEffect(() => {
    function onPaste(e: ClipboardEvent) {
      if (!e.clipboardData) return;
      const items = Array.from(e.clipboardData.items);
      const files = items
        .filter((it) => it.kind === 'file' && it.type.startsWith('image/'))
        .map((it) => it.getAsFile())
        .filter((f): f is File => !!f);
      if (files.length === 0) return;
      const active = document.activeElement as HTMLElement | null;
      const inScope = !active
        || rootRef.current?.contains(active)
        || (pasteScopeRef?.current?.contains(active) ?? false);
      if (!inScope) return;
      e.preventDefault();
      void addFiles(files);
    }
    document.addEventListener('paste', onPaste);
    return () => document.removeEventListener('paste', onPaste);
  }, [addFiles, pasteScopeRef]);

  /**
   * 【拖拽放下处理】
   * 阻止浏览器默认行为并将拖拽的文件交给 addFiles 处理。
   */
  function onDrop(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragActive(false);
    void addFiles(e.dataTransfer.files);
  }

  /**
   * 【删除已上传图片】
   * 从图片列表中移除指定 ID 的图片，触发 onChange 通知父组件。
   */
  function removeImage(id: string) {
    onChange(images.filter((img) => img.id !== id));
  }

  /**
   * 【打开灯箱预览】
   * 设置灯箱索引，显示全屏大图预览。
   */
  function openLightbox(idx: number) {
    setLightboxIdx(idx);
  }

  return (
    <div className="proof-uploader" ref={rootRef}>
      <div
        className={`proof-dropzone${dragActive ? ' drag' : ''}${slotsLeft <= 0 ? ' full' : ''}`}
        onDragOver={(e) => { e.preventDefault(); if (slotsLeft > 0) setDragActive(true); }}
        onDragLeave={() => setDragActive(false)}
        onDrop={onDrop}
        onClick={() => slotsLeft > 0 && fileInputRef.current?.click()}
        role="button"
        tabIndex={0}
      >
        <ImagePlus size={18} />
        <div className="proof-dropzone-text">
          {slotsLeft > 0 ? (
            <>
              <strong>点击 / 拖入 / 粘贴</strong>
              <span>支持多张，单张 ≤ 20MB · 还可上传 {slotsLeft} 张</span>
            </>
          ) : (
            <strong>已达上限 {MAX_FILES} 张</strong>
          )}
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={(e) => {
            void addFiles(e.target.files);
            if (e.target) e.target.value = '';
          }}
        />
      </div>

      {(images.length > 0 || pending.length > 0) && (
        <div className="proof-thumb-grid">
          {images.map((img, i) => (
            <div className="proof-thumb" key={img.id}>
              <button
                type="button"
                className="proof-thumb-img"
                onClick={() => openLightbox(i)}
                title="点击预览"
              >
                <img src={img.url} alt={img.name} loading="lazy" />
              </button>
              <button
                type="button"
                className="proof-thumb-remove"
                title="删除"
                onClick={() => removeImage(img.id)}
              >
                <X size={12} />
              </button>
              <div className="proof-thumb-meta">{formatBytes(img.bytes)}</div>
            </div>
          ))}
          {pending.map((it) => (
            <div className="proof-thumb pending" key={it.id}>
              <div className="proof-thumb-img placeholder">
                {it.error ? <AlertCircle size={20} /> : <Loader size={18} className="spin" />}
              </div>
              <div className="proof-thumb-progress">
                <div className="proof-thumb-progress-fill" style={{ width: `${it.progress}%` }} />
              </div>
              <div className="proof-thumb-meta" title={it.error || it.name}>
                {it.error ? '失败' : `${it.progress}%`}
              </div>
            </div>
          ))}
        </div>
      )}

      {error && (
        <div className="proof-uploader-error">
          <AlertCircle size={14} /> {error}
        </div>
      )}

      {lightboxIdx !== null && (
        <ImageLightbox
          urls={images.map((i) => i.url)}
          index={lightboxIdx}
          onClose={() => setLightboxIdx(null)}
          onNavigate={setLightboxIdx}
        />
      )}
    </div>
  );
}

/** Read-only thumbnail grid for displaying saved proof images on a submission card. */
/**
 * 【只读凭证缩略图条】
 * 用于提交记录卡片上展示已保存的凭证图片，仅支持预览不支持编辑。
 * 点击缩略图打开灯箱大图预览。
 *
 * @param urls - 图片 URL 数组
 */
export function ProofThumbStrip({ urls }: { urls: string[] }) {
  const [idx, setIdx] = useState<number | null>(null);
  if (urls.length === 0) return null;
  return (
    <>
      <div className="proof-thumb-grid readonly">
        {urls.map((url, i) => (
          <button
            key={url + i}
            type="button"
            className="proof-thumb-img readonly"
            onClick={() => setIdx(i)}
            title="点击查看大图"
          >
            <img src={url} alt={`凭证 ${i + 1}`} loading="lazy" />
          </button>
        ))}
      </div>
      {idx !== null && (
        <ImageLightbox urls={urls} index={idx} onClose={() => setIdx(null)} onNavigate={setIdx} />
      )}
    </>
  );
}

/**
 * 【解析提交记录的截图 URL】
 * 从 Submission 对象中提取截图 URL 列表。
 * 优先使用 screenshotUrls（逗号分隔的多图），回退到 screenshotUrl（单图）。
 * 兼容新旧两种数据格式。
 *
 * @param s - 提交记录对象
 * @returns 截图 URL 数组
 */
export function parseScreenshotUrls(s: Submission): string[] {
  const csv = s.screenshotUrls;
  if (csv && csv.trim()) return csv.split(',').map((x) => x.trim()).filter(Boolean);
  if (s.screenshotUrl) return [s.screenshotUrl];
  return [];
}
