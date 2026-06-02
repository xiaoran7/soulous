/**
 * 【客户端图片缩放】imageResize
 *
 * 头像在界面里只会小尺寸展示，没必要把几 MB 的原图整张上传——大图会被反向代理
 * （nginx client_max_body_size 默认 1MB）直接拦成 413（Content Too Large），
 * 请求根本到不了后端的压缩逻辑。这里在浏览器里先把最长边限制到 maxEdge、
 * 重新编码为 JPEG，体积通常压到一两百 KB，远低于任何上传上限。
 *
 * 设计原则：任何失败都回退原文件，绝不因压缩出错而挡住上传。
 */

/** 【加载图片为 HTMLImageElement】解码失败则 reject */
function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = src;
  });
}

/**
 * 【把图片缩放到不超过 maxEdge 并重新编码为 JPEG】
 *
 * @param file    原始图片文件
 * @param maxEdge 最长边像素上限（默认 512，足够头像清晰显示）
 * @param quality JPEG 质量 0–1（默认 0.85）
 * @returns 缩放后的新 File；任何不适用/失败的情况都原样返回入参文件
 */
export async function downscaleImage(file: File, maxEdge = 512, quality = 0.85): Promise<File> {
  // 动图 GIF 经 canvas 会丢失动画；非图片类型直接放行
  if (file.type === 'image/gif' || !file.type.startsWith('image/')) return file;

  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    const longest = Math.max(img.naturalWidth, img.naturalHeight);
    // 已经足够小：原样上传，避免无谓的重新编码损失
    if (longest <= maxEdge && file.size <= 1_000_000) return file;

    const scale = longest > maxEdge ? maxEdge / longest : 1;
    const w = Math.max(1, Math.round(img.naturalWidth * scale));
    const h = Math.max(1, Math.round(img.naturalHeight * scale));

    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) return file;
    // JPEG 不支持透明：先铺白底，避免 PNG 透明区域变黑
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, w, h);
    ctx.drawImage(img, 0, 0, w, h);

    const blob = await new Promise<Blob | null>((res) => canvas.toBlob(res, 'image/jpeg', quality));
    if (!blob || blob.size >= file.size) return file; // 压不下去就用原图
    const name = file.name.replace(/\.[^.]+$/, '') + '.jpg';
    return new File([blob], name, { type: 'image/jpeg' });
  } catch {
    return file; // 任何异常都回退原文件
  } finally {
    URL.revokeObjectURL(url);
  }
}
