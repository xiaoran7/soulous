/**
 * 【自习室自定义素材存储】customStore
 *
 * 用户上传的场景图 / 音乐文件以 Blob 形式存进 IndexedDB（localStorage 放不下大文件），
 * 刷新后依然在。读取时用 URL.createObjectURL 生成临时地址供 <img>/<audio> 使用。
 *
 * 一条记录 = 一个自定义场景或一首自定义音乐。
 */
import type { AmbientKind } from './scenes';

/** 【IndexedDB 数据库名 / 版本 / 表名】 */
const DB_NAME = 'soulous-studyroom';
const DB_VERSION = 1;
const STORE = 'custom';

/** 【自定义素材记录】 */
export interface CustomItem {
  /** 唯一 ID，如 custom-scene-169... / custom-music-169... / custom-ambient-169... */
  id: string;
  /** 类型：场景图（含动图/视频） / 背景音乐 / 背景环境音 */
  kind: 'scene' | 'music' | 'ambient';
  /** 展示名 */
  name: string;
  /** 文件二进制（场景可为图片、GIF 或视频；音频为音乐/环境音） */
  blob: Blob;
  /** 仅场景：选用的合成环境音类型（无上传环境音时用合成兜底） */
  ambient?: AmbientKind;
  /** 创建时间戳 */
  createdAt: number;
}

/** 【打开数据库】首次创建对象仓库 */
function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'id' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/** 【新增一条自定义素材】 */
export async function addCustom(item: CustomItem): Promise<void> {
  const db = await openDB();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).put(item);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}

/** 【列出全部自定义素材】按创建时间升序 */
export async function listCustom(): Promise<CustomItem[]> {
  const db = await openDB();
  const items = await new Promise<CustomItem[]>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readonly');
    const req = tx.objectStore(STORE).getAll();
    req.onsuccess = () => resolve((req.result as CustomItem[]) ?? []);
    req.onerror = () => reject(req.error);
  });
  db.close();
  return items.sort((a, b) => a.createdAt - b.createdAt);
}

/** 【删除一条自定义素材】 */
export async function deleteCustom(id: string): Promise<void> {
  const db = await openDB();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    tx.objectStore(STORE).delete(id);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  db.close();
}
