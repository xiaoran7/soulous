export interface CompressOptions {
  maxDimension?: number;
  maxBytes?: number;
  quality?: number;
  mimeType?: 'image/webp' | 'image/jpeg' | 'image/png';
}

const DEFAULT_MAX_DIMENSION = 2400;
const DEFAULT_MAX_BYTES = 2 * 1024 * 1024;
const DEFAULT_QUALITY = 0.85;

function shouldSkip(file: File) {
  return file.type === 'image/gif' || file.type === 'image/webp';
}

export async function compressImageIfNeeded(file: File, opts: CompressOptions = {}): Promise<File> {
  if (!file.type.startsWith('image/')) return file;
  if (shouldSkip(file)) return file;

  const maxDim = opts.maxDimension ?? DEFAULT_MAX_DIMENSION;
  const maxBytes = opts.maxBytes ?? DEFAULT_MAX_BYTES;
  const quality = opts.quality ?? DEFAULT_QUALITY;
  const targetType = opts.mimeType ?? 'image/webp';

  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return file;
  }

  const longest = Math.max(bitmap.width, bitmap.height);
  const needsResize = longest > maxDim;
  const needsRecompress = file.size > maxBytes;
  if (!needsResize && !needsRecompress) {
    bitmap.close?.();
    return file;
  }

  const scale = needsResize ? maxDim / longest : 1;
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    bitmap.close?.();
    return file;
  }
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();

  const blob: Blob | null = await new Promise((resolve) =>
    canvas.toBlob(resolve, targetType, quality)
  );
  if (!blob) return file;
  if (blob.size >= file.size && !needsResize) return file;

  const ext = targetType === 'image/webp' ? '.webp' : targetType === 'image/jpeg' ? '.jpg' : '.png';
  const baseName = file.name.replace(/\.[^.]+$/, '') || 'screenshot';
  return new File([blob], `${baseName}${ext}`, { type: targetType, lastModified: Date.now() });
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}
