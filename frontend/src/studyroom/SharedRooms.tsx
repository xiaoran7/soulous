/**
 * 【共享自习室 · 房间广场】SharedRooms
 * 只负责广场：列出房间（含在线人数）、建房、加入、房主删房。
 * 建房/加入成功后把 RoomDetail 交给上层 onEnter——由 FocusPage 启动真实专注会话
 * 并进入沉浸态自习室（房内成员/心跳也在 FocusPage 维护），不再在面板里开小房间视图。
 * onEnter 抛错（如启动会话失败）时自动退房回滚，停留在广场并展示错误。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { DoorOpen, Plus, RefreshCw, Trash2, Users } from 'lucide-react';
import { api } from '../api';
import type { RoomDetail, RoomSummary } from '../types';

export function SharedRooms({ onEnter }: { onEnter: (room: RoomDetail) => Promise<void> | void }) {
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const loadRooms = useCallback(async () => {
    try {
      const list = await api.rooms();
      setRooms(Array.isArray(list) ? list : []);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载房间失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadRooms(); }, [loadRooms]);

  /** 拿到房间详情后进入沉浸态；上层失败则退房回滚，留在广场 */
  async function enter(detail: RoomDetail) {
    try {
      await onEnter(detail);
    } catch (err) {
      api.leaveRoomBeacon(detail.id);
      setError(err instanceof Error ? err.message : '进入自习室失败');
      void loadRooms();
    }
  }

  async function create() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const d = await api.createRoom(name.trim());
      setName('');
      await enter(d);
    } catch (err) {
      setError(err instanceof Error ? err.message : '建房失败');
    } finally {
      setBusy(false);
    }
  }

  async function join(id: number) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const d = await api.joinRoom(id);
      await enter(d);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加入失败');
      void loadRooms(); // 房间可能已被删，刷新列表
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    if (busy || !window.confirm('删除这个自习室？房内成员会被请出。')) return;
    setBusy(true);
    setError('');
    try {
      await api.deleteRoom(id);
      await loadRooms();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel shared-room">
      <div className="panel-title">
        <h2><Users size={16} style={{ verticalAlign: '-3px' }} /> 共享自习室</h2>
        <button className="icon-button" onClick={() => void loadRooms()} title="刷新"><RefreshCw size={14} /></button>
      </div>
      <p className="muted small" style={{ marginTop: -4 }}>建房或加入后直接进入自习室，房内能看到彼此的在线与专注时长。</p>
      <div className="shared-create">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="给自习室起个名字（可留空）" maxLength={40}
          onKeyDown={(e) => { if (e.key === 'Enter') void create(); }} />
        <button className="primary-button" onClick={() => void create()} disabled={busy}><Plus size={14} /> 建房并进入</button>
      </div>
      {error && <div className="form-error" style={{ marginTop: 8 }}>{error}</div>}
      <div className="shared-room-list">
        {loading && <div className="muted small">加载中…</div>}
        {!loading && rooms.length === 0 && <div className="muted small">还没有自习室，建一个邀请同学一起学吧。</div>}
        {rooms.map((r) => (
          <div className="shared-room-item" key={r.id}>
            <div>
              <strong>{r.name}</strong>
              <span className="muted small">房主 {r.ownerName} · 在线 {r.onlineCount}</span>
            </div>
            <div className="shared-room-actions">
              {r.mine && (
                <button className="ghost-button compact-button" onClick={() => void remove(r.id)} disabled={busy} title="删除自习室">
                  <Trash2 size={14} />
                </button>
              )}
              <button className="secondary-button compact-button" onClick={() => void join(r.id)} disabled={busy}>
                <DoorOpen size={14} /> 加入
              </button>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
