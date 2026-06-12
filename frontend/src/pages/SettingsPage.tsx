/**
 * 【账号设置页面】SettingsPage
 * 归在「账号」导航组下，集中账号级操作：
 * - 账号资料：修改昵称、邮箱、头像（从原「资料」页迁移而来，编辑统一收口到这里）
 * - 修改密码：旧密码 + 新密码 + 确认（走 /api/auth/password，改后会踢掉其它会话）
 *   —— 默认收起，避免一进设置就甩出三个密码框；点「修改密码」才展开。
 * - 危险操作：退出所有设备（撤销全部刷新令牌，当前会话也会失效）
 *
 * 设计思路：单列堆叠多个 panel，敏感/低频操作（改密、危险操作）下沉。
 */
import React, { useEffect, useRef, useState } from 'react';
import { KeyRound, ShieldAlert, Save, LogOut, Upload, UserCog, ChevronDown, ChevronUp, Cat } from 'lucide-react';
import { api } from '../api';
import type { Pet, User } from '../types';
import { ClickableAvatar, animationForPet, petStatusLabel } from '../components/shared';
import { PetSprite } from '../PetSprite';
import { downscaleImage } from '../imageResize';

/**
 * 【账号设置页主组件】
 * @param user - 当前用户
 * @param onUpdated - 资料/改密成功后返回的新用户信息，回传父组件同步（顶部导航即时更新）
 * @param onPetUpdated - 宠物改名后同步到 App 全局 pet 状态（导航宠物芯片名即时更新）
 * @param onSessionEnded - 退出所有设备成功后，清空本地会话（回到登录页）
 */
export function SettingsPage({ user, onUpdated, onPetUpdated, onSessionEnded }: {
  user: User;
  onUpdated: (user: User) => void;
  onPetUpdated?: (pet: Pet) => void;
  onSessionEnded: () => void;
}) {
  /** 【账号资料表单】昵称、邮箱 */
  const [form, setForm] = useState({ nickname: user.nickname ?? '', email: user.email ?? '' });
  const [saving, setSaving] = useState(false);
  const [profileMessage, setProfileMessage] = useState('');
  const [profileError, setProfileError] = useState('');
  /** 【头像上传状态】 */
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const fileInputRef = useRef<HTMLInputElement>(null);

  /** 【宠物数据与改名表单】从「我的」页迁移而来，编辑统一收口到这里 */
  const [pet, setPet] = useState<Pet | null>(null);
  const [petName, setPetName] = useState('');
  const [petBusy, setPetBusy] = useState(false);
  const [petMessage, setPetMessage] = useState('');

  /** 【改密表单】 */
  const [pwForm, setPwForm] = useState({ current: '', next: '', confirm: '' });
  const [pwBusy, setPwBusy] = useState(false);
  const [pwMessage, setPwMessage] = useState('');
  const [pwError, setPwError] = useState('');
  /** 【改密区是否展开】默认收起，避免一进设置就甩出密码框 */
  const [showPw, setShowPw] = useState(false);

  /** 【危险操作：退出所有设备的二次确认与忙碌态】 */
  const [confirmLogoutAll, setConfirmLogoutAll] = useState(false);
  const [logoutBusy, setLogoutBusy] = useState(false);

  /** 【同步用户数据】父组件 user 变化时刷新表单 */
  useEffect(() => {
    setForm({ nickname: user.nickname ?? '', email: user.email ?? '' });
  }, [user.id, user.nickname, user.email]);

  /**
   * 【加载宠物数据】挂载时获取宠物信息，回填改名输入框。
   * 宠物可能尚未创建，catch 静默处理；cancelled 防卸载后更新状态。
   */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const p = await api.pet();
        if (!cancelled) { setPet(p); setPetName(p?.name ?? ''); }
      } catch { /* pet may not be ready yet */ }
    })();
    return () => { cancelled = true; };
  }, [user.id]);

  /** 【保存宠物名称】成功后更新本地宠物状态并同步父组件（导航宠物芯片名即时更新） */
  async function savePetName(event: React.FormEvent) {
    event.preventDefault();
    setPetBusy(true); setPetMessage('');
    try {
      const updated = await api.setPetName(petName);
      setPet(updated);
      setPetName(updated.name ?? '');
      onPetUpdated?.(updated);
      setPetMessage('宠物名称已更新。');
    } catch (err) {
      setPetMessage(err instanceof Error ? err.message : '宠物改名失败');
    } finally {
      setPetBusy(false);
    }
  }

  /** 【头像首字母占位】无头像 URL 时显示昵称/用户名首字母 */
  const initial = (form.nickname || user.username || 'S').slice(0, 1).toUpperCase();

  /** 【保存账号资料】提交昵称/邮箱，成功后回传父组件 */
  async function saveProfile(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true); setProfileMessage(''); setProfileError('');
    try {
      const updated = await api.updateMe(form);
      onUpdated(updated);
      setProfileMessage('资料已保存。');
    } catch (err) {
      setProfileError(err instanceof Error ? err.message : '资料保存失败');
    } finally {
      setSaving(false);
    }
  }

  /** 【上传头像】选文件后先在浏览器缩图再上传（避免大图被反代拦成 413） */
  async function handleAvatarFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploading(true); setUploadProgress(0); setProfileError(''); setProfileMessage('');
    try {
      const resized = await downscaleImage(file);
      const updated = await api.uploadAvatar(resized, setUploadProgress);
      onUpdated(updated);
      setProfileMessage('头像已更新。');
    } catch (err) {
      setProfileError(err instanceof Error ? err.message : '头像上传失败');
    } finally {
      setUploading(false); setUploadProgress(0);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  /**
   * 【提交改密】前端先校验两次新密码一致、新旧不同，再交后端做复杂度校验。
   * 成功后清空表单；后端已轮换当前会话 Cookie，无需重新登录。
   */
  async function submitPassword(event: React.FormEvent) {
    event.preventDefault();
    setPwError('');
    setPwMessage('');
    if (pwForm.next !== pwForm.confirm) { setPwError('两次输入的新密码不一致'); return; }
    if (pwForm.next === pwForm.current) { setPwError('新密码不能与当前密码相同'); return; }
    setPwBusy(true);
    try {
      const res = await api.changePassword(pwForm.current, pwForm.next);
      if (res?.user) onUpdated(res.user as User);
      setPwForm({ current: '', next: '', confirm: '' });
      setPwMessage('密码已更新，其它设备的登录已失效。');
    } catch (err) {
      setPwError(err instanceof Error ? err.message : '改密失败');
    } finally {
      setPwBusy(false);
    }
  }

  /** 【退出所有设备】撤销全部令牌后当前会话也失效，清空本地状态回登录页。 */
  async function logoutEverywhere() {
    setLogoutBusy(true);
    try {
      await api.logoutAll();
    } catch { /* 即便失败，本地状态也按已退出处理 */ }
    onSessionEnded();
  }

  return (
    <div className="settings-page">
      {/* ===== 账号资料 + 宠物（合并为一张卡：左账号、右宠物，填满右侧留白） ===== */}
      <section className="panel">
        <div className="panel-title"><h2>账号资料</h2><UserCog size={18} /></div>
        <div className="settings-account-grid">
          {/* 左列：昵称 / 邮箱 / 头像 */}
          <form className="stack-form" onSubmit={saveProfile}>
            <label className="field-label">
              <span>昵称</span>
              <input value={form.nickname} onChange={(e) => setForm({ ...form, nickname: e.target.value })} placeholder="显示在工作台的名称" />
            </label>
            <label className="field-label">
              <span>邮箱</span>
              <input value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="alice@example.com" type="email" />
            </label>

            <div className="field-label">
              <span>头像</span>
              <div className="avatar-upload-row">
                {user.avatarUrl ? (
                  <ClickableAvatar className="avatar-preview small" url={user.avatarUrl} alt="当前头像" />
                ) : (
                  <div className="avatar-preview small fallback">{initial}</div>
                )}
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/png,image/jpeg,image/gif,image/webp"
                  onChange={(e) => void handleAvatarFile(e)}
                  style={{ display: 'none' }}
                />
                <button type="button" className="secondary-button" onClick={() => fileInputRef.current?.click()} disabled={uploading}>
                  <Upload size={14} />{uploading ? `上传中 ${uploadProgress}%` : '上传头像'}
                </button>
              </div>
            </div>

            {profileMessage && <div className="success-message">{profileMessage}</div>}
            {profileError && <div className="form-error">{profileError}</div>}
            <button className="primary-button" disabled={saving}>
              <Save size={16} />{saving ? '保存中' : '保存资料'}
            </button>
          </form>

          {/* 右列：宠物（从「我的」页迁移而来：编辑统一收口到设置） */}
          <div className="settings-pet-col">
            <div className="settings-subhead"><Cat size={15} /> 宠物</div>
            <div className="pet-name-preview" style={{ margin: '0 0 14px' }}>
              <div className="pet-name-frame">
                <PetSprite state={animationForPet(pet)} size={40} />
              </div>
              <div>
                <strong>{petName || pet?.name || 'Soul'}</strong>
                <span>Lv.{pet?.level ?? 1} · {petStatusLabel[pet?.status ?? 'NORMAL'] ?? '安静陪伴'}</span>
              </div>
            </div>
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
              <button className="primary-button" disabled={petBusy || !pet}>
                <Save size={16} />{petBusy ? '保存中' : '保存宠物名称'}
              </button>
            </form>
          </div>
        </div>
      </section>

      {/* ===== 修改密码（默认收起） ===== */}
      <section className="panel">
        <div className="panel-title">
          <h2>修改密码</h2>
          <button type="button" className="secondary-button" onClick={() => setShowPw((v) => !v)}>
            <KeyRound size={14} />
            {showPw ? '收起' : '修改密码'}
            {showPw ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          </button>
        </div>
        {showPw && (
          <form className="stack-form" onSubmit={submitPassword} autoComplete="off">
            <label className="field-label">
              <span>当前密码</span>
              <input type="password" value={pwForm.current} autoComplete="current-password"
                onChange={(e) => setPwForm({ ...pwForm, current: e.target.value })} placeholder="输入当前密码" />
            </label>
            <label className="field-label">
              <span>新密码</span>
              <input type="password" value={pwForm.next} autoComplete="new-password"
                onChange={(e) => setPwForm({ ...pwForm, next: e.target.value })} placeholder="至少 8 位，含字母/数字/符号中两类" />
            </label>
            <label className="field-label">
              <span>确认新密码</span>
              <input type="password" value={pwForm.confirm} autoComplete="new-password"
                onChange={(e) => setPwForm({ ...pwForm, confirm: e.target.value })} placeholder="再次输入新密码" />
            </label>
            <p className="muted small" style={{ margin: 0 }}>
              密码需 8–72 位，不含空格，至少包含字母、数字、符号中的两类，且不含用户名。
            </p>
            {pwMessage && <div className="success-message">{pwMessage}</div>}
            {pwError && <div className="form-error">{pwError}</div>}
            <button className="primary-button" disabled={pwBusy || !pwForm.current || !pwForm.next || !pwForm.confirm}>
              <Save size={16} />{pwBusy ? '提交中' : '更新密码'}
            </button>
          </form>
        )}
      </section>

      {/* ===== 危险操作 ===== */}
      <section className="panel danger-zone">
        <div className="panel-title"><h2>危险操作</h2><ShieldAlert size={18} /></div>
        <div className="danger-row">
          <div className="danger-copy">
            <strong>退出所有设备</strong>
          </div>
          {confirmLogoutAll ? (
            <div className="row-actions">
              <button className="secondary-button danger-text" disabled={logoutBusy} onClick={() => void logoutEverywhere()}>
                <LogOut size={14} />{logoutBusy ? '退出中…' : '确认退出'}
              </button>
              <button className="secondary-button" disabled={logoutBusy} onClick={() => setConfirmLogoutAll(false)}>取消</button>
            </div>
          ) : (
            <button className="secondary-button danger-text" onClick={() => setConfirmLogoutAll(true)}>
              <LogOut size={14} /> 退出所有设备
            </button>
          )}
        </div>
      </section>
    </div>
  );
}
