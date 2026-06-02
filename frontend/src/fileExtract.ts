/**
 * 【附件文本提取】fileExtract
 *
 * 自习室 AI 拆解对话支持「上传文件喂给 AI」：在浏览器端把用户选的文件提取成纯文本，
 * 由调用方拼进发送给 AI 的消息正文。当前支持：
 *  - Markdown / 纯文本（.md / .txt / text/*）：直接读取
 *  - PDF（.pdf / application/pdf）：用 pdfjs 逐页提取文字层
 *
 * 设计要点：
 *  - 全程在前端完成，不新增后端接口，复用既有的 AI 流式发送链路。
 *  - 统一截断到 MAX_EXTRACT_CHARS，避免超长内容撑爆大模型上下文/触发限流。
 *  - 扫描版 PDF（仅图片、无文字层）会提取到空文本，由调用方据 charCount===0 提示用户。
 */
// Vite 会把 worker 打包成独立资源并给出可访问 URL；pdfjs 需要它来在 worker 线程里解析 PDF。
// 仅作为 URL 字符串引用，不会执行 pdfjs 运行时。
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

/**
 * 【懒加载 pdfjs】
 * pdfjs-dist 主包在模块求值时会引用浏览器专有的 DOMMatrix 等全局对象，
 * 在 Node/jsdom（单元测试）环境下直接 import 会抛错。这里改为「真正解析 PDF 时」
 * 才动态加载，既修复了测试期的导入崩溃，也让 pdf.worker 这块大体积代码按需加载。
 */
let pdfjsPromise: Promise<typeof import('pdfjs-dist')> | null = null;
function loadPdfjs(): Promise<typeof import('pdfjs-dist')> {
  if (!pdfjsPromise) {
    pdfjsPromise = import('pdfjs-dist').then((pdfjs) => {
      pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;
      return pdfjs;
    });
  }
  return pdfjsPromise;
}

/** 【提取文本字符上限】超过则截断，并在结果里标记 truncated。 */
export const MAX_EXTRACT_CHARS = 30000;

/** 【提取结果】 */
export interface ExtractResult {
  /** 提取到的纯文本（已按上限截断） */
  text: string;
  /** 是否因超过上限被截断 */
  truncated: boolean;
  /** 截断后的字符数 */
  charCount: number;
}

/** 【是否纯文本类型】.md / .txt / 任意 text/* MIME */
function isTextLike(file: File): boolean {
  const name = file.name.toLowerCase();
  return file.type.startsWith('text/')
    || name.endsWith('.md')
    || name.endsWith('.markdown')
    || name.endsWith('.txt');
}

/** 【是否 PDF】 */
function isPdf(file: File): boolean {
  return file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
}

/** 【从 PDF 逐页提取文字层】页与页之间用换行分隔 */
async function extractPdf(file: File): Promise<string> {
  const pdfjs = await loadPdfjs();
  const buf = await file.arrayBuffer();
  const loadingTask = pdfjs.getDocument({ data: buf });
  const doc = await loadingTask.promise;
  try {
    const pages: string[] = [];
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i);
      const content = await page.getTextContent();
      const line = content.items
        // pdfjs 的文本项为 TextItem（含 str）或 TextMarkedContent（无 str）
        .map((item) => ('str' in item ? item.str : ''))
        .join(' ');
      pages.push(line);
      // 及时释放页面资源，长 PDF 内存更稳
      page.cleanup();
    }
    return pages.join('\n').trim();
  } finally {
    await loadingTask.destroy();
  }
}

/** 【按上限截断并组装结果】 */
function clamp(raw: string): ExtractResult {
  const truncated = raw.length > MAX_EXTRACT_CHARS;
  const text = truncated ? raw.slice(0, MAX_EXTRACT_CHARS) : raw;
  return { text, truncated, charCount: text.length };
}

/**
 * 【提取文件文本】
 * @throws 当文件类型不受支持时抛出 Error。
 */
export async function extractText(file: File): Promise<ExtractResult> {
  if (isPdf(file)) {
    return clamp(await extractPdf(file));
  }
  if (isTextLike(file)) {
    return clamp((await file.text()).trim());
  }
  throw new Error('暂不支持该文件类型，仅支持 Markdown / 纯文本 / PDF');
}
