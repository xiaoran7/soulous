/**
 * 【个人资料页面】ProfilePage
 * 本页面允许用户管理个人账号信息和宠物设置：
 * - 账号资料：修改昵称、邮箱
 * - 头像管理：上传/更换头像（支持上传进度显示）
 * - 资料预览：实时预览修改后的个人资料
 * - 宠物设置：修改宠物名称
 *
 * 设计思路：左侧为编辑表单，右侧为实时预览，所见即所得。
 * 头像上传使用 XMLHttpRequest 的 progress 事件实现进度条。
 */
import React, { useEffect, useRef, useState } from 'react';
import { Save, Settings, Upload, UserRound, Cat } from 'lucide-react';
import { api } from '../api';
import type { Pet, User } from '../types';
import { ClickableAvatar } from '../components/shared';
import { downscaleImage } from '../imageResize';

/**
 * 【个人资料页面主组件】
 * @param user - 当前用户信息（从父组件传入）
 * @param onUpdated - 用户信息更新回调，通知父组件同步状态
 *
 * 状态管理：
 * - form: 表单数据（昵称、邮箱）
 * - uploading/uploadProgress: 头像上传状态和进度
 * - pet/petName: 宠物数据和名称编辑状态
 */
export function ProfilePage({ user, onUpdated }: { user: User; onUpdated: (user: User) => void }) {
  /** 【表单数据】昵称和邮箱的编辑状态 */
  const [form, setForm] = useState({
    nickname: user.nickname ?? '',
    email: user.email ?? ''
  });
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  /** 【头像上传中】控制上传按钮禁用状态 */
  const [uploading, setUploading] = useState(false);
  /** 【上传进度百分比】0-100 */
  const [uploadProgress, setUploadProgress] = useState(0);
  /** 【文件输入引用】用于触发隐藏的文件选择器 */
  const fileInputRef = useRef<HTMLInputElement>(null);

  /** 【宠物数据】从 API 加载的宠物信息 */
  const [pet, setPet] = useState<Pet | null>(null);
  /** 【宠物名称编辑】编辑中的宠物名称 */
  const [petName, setPetName] = useState('');
  const [petBusy, setPetBusy] = useState(false);
  const [petMessage, setPetMessage] = useState('');

  /**
   * 【同步用户数据】当父组件的 user 数据变化时，更新表单状态
   * 依赖 user.id、user.nickname、user.email 三个字段
   */
  useEffect(() => {
    setForm({ nickname: user.nickname ?? '', email: user.email ?? '' });
  }, [user.id, user.nickname, user.email]);

  /**
   * 【加载宠物数据】组件挂载时获取宠物信息
   * 使用 cancelled 标志防止组件卸载后更新状态（避免内存泄漏）
   * 宠物可能尚未创建，因此 catch 中静默处理
   */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const p = await api.pet();
        if (!cancelled) {
          setPet(p);
          setPetName(p?.name ?? '');
        }
      } catch { /* pet may not be ready yet */ }
    })();
    return () => { cancelled = true; };
  }, [user.id]);

  /**
   * 【保存个人资料】提交昵称和邮箱到 API
   * 成功后通过 onUpdated 回调通知父组件更新用户状态
   */
  async function saveProfile(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setMessage('');
    setError('');
    try {
      const updated = await api.updateMe(form);
      onUpdated(updated);
      setMessage('资料已保存。');
    } catch (err) {
      setError(err instanceof Error ? err.message : '资料保存失败');
    } finally {
      setSaving(false);
    }
  }

  /**
   * 【处理头像上传】选择文件后自动上传
   * 使用 api.uploadAvatar 支持进度回调
   * 上传完成后更新用户状态，失败时显示错误
   * 最后清理文件输入框，允许重复上传同一文件
   */
  async function handleAvatarFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setUploadProgress(0);
    setError('');
    setMessage('');
    try {
      // 先在浏览器里把头像缩到小尺寸，避免大图被反代拦成 413
      const resized = await downscaleImage(file);
      const updated = await api.uploadAvatar(resized, setUploadProgress);
      onUpdated(updated);
      setMessage('头像已更新。');
    } catch (err) {
      setError(err instanceof Error ? err.message : '头像上传失败');
    } finally {
      setUploading(false);
      setUploadProgress(0);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  /**
   * 【保存宠物名称】提交新的宠物名称到 API
   * 成功后更新本地宠物状态和显示成功消息
   */
  async function savePetName(event: React.FormEvent) {
    event.preventDefault();
    setPetBusy(true);
    setPetMessage('');
    try {
      const updated = await api.setPetName(petName);
      setPet(updated);
      setPetName(updated.name ?? '');
      setPetMessage('宠物名称已更新。');
    } catch (err) {
      setPetMessage(err instanceof Error ? err.message : '宠物改名失败');
    } finally {
      setPetBusy(false);
    }
  }

  /** 【头像首字母】当没有头像 URL 时，显示昵称/用户名的首字母作为占位 */
  const initial = (form.nickname || user.username || 'S').slice(0, 1).toUpperCase();

  return (
    <div className="two-column">
      {/* ===== 【账号资料编辑面板】左侧表单 ===== */}
      <section className="panel">
        <div className="panel-title"><h2>账号资料</h2><Settings size={18} /></div>
        <form className="stack-form" onSubmit={saveProfile}>
          <label className="field-label">
            <span>昵称</span>
            <input value={form.nickname} onChange={(e) => setForm({ ...form, nickname: e.target.value })} placeholder="显示在工作台的名称" />
          </label>
          <label className="field-label">
            <span>邮箱</span>
            <input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="alice@example.com" type="email" />
          </label>

          {/* 【头像上传区域】显示当前头像预览和上传按钮 */}
          <div className="field-label">
            <span>头像</span>
            <div className="avatar-upload-row">
              {user.avatarUrl ? (
                <ClickableAvatar className="avatar-preview small" url={user.avatarUrl} alt="当前头像" />
              ) : (
                <div className="avatar-preview small fallback">{initial}</div>
              )}
              {/* 【隐藏文件输入】通过按钮触发，限制图片格式 */}
              <input
                ref={fileInputRef}
                type="file"
                accept="image/png,image/jpeg,image/gif,image/webp"
                onChange={(e) => void handleAvatarFile(e)}
                style={{ display: 'none' }}
              />
              <button
                type="button"
                className="secondary-button"
                onClick={() => fileInputRef.current?.click()}
                disabled={uploading}
              >
                <Upload size={14} />{uploading ? `上传中 ${uploadProgress}%` : '上传头像'}
              </button>
            </div>
          </div>

          {message && <div className="success-message">{message}</div>}
          {error && <div className="form-error">{error}</div>}
          <button className="primary-button" disabled={saving}>
            <Save size={16} />{saving ? '保存中' : '保存资料'}
          </button>
        </form>
      </section>

      {/* ===== 【资料预览面板】右侧实时预览 ===== */}
      <section className="panel wide profile-preview">
        <div className="panel-title"><h2>资料预览</h2><UserRound size={18} /></div>
        {/* 【用户信息展示】头像、昵称、用户名、角色 */}
        <div className="profile-hero">
          {user.avatarUrl ? (
            <ClickableAvatar className="avatar-preview" url={user.avatarUrl} alt={`${form.nickname || user.username} 的头像`} />
          ) : (
            <div className="avatar-preview fallback">{initial}</div>
          )}
          <div>
            <h3>{form.nickname || user.username}</h3>
            <p>{user.username} · {user.role}</p>
          </div>
        </div>
        {/* 【用户资料详情】ID、邮箱、头像状态 */}
        <div className="profile-facts">
          <div><span>用户 ID</span><strong>{user.id}</strong></div>
          <div><span>邮箱</span><strong>{form.email || '未填写'}</strong></div>
          <div><span>头像</span><strong>{user.avatarUrl ? '已配置' : '未配置'}</strong></div>
        </div>

        {/* 【宠物设置区域】修改宠物名称 */}
        <div className="panel-title" style={{ marginTop: 16 }}><h2>宠物</h2><Cat size={18} /></div>
        <form className="stack-form" onSubmit={savePetName}>
          <label className="field-label">
            <span>宠物名称</span>
            <input
              value={petName}
              onChange={(e) => setPetName(e.target.value)}
              placeholder={`留空则使用「${user.username}」`}
              maxLength={32}
            />
          </label>
          {petMessage && <div className="form-hint">{petMessage}</div>}
          <button className="secondary-button" disabled={petBusy || !pet}>
            <Save size={14} />{petBusy ? '保存中' : '保存宠物名称'}
          </button>
        </form>
      </section>
    </div>
  );
}
