/**
 * 【共享自习室】SharedRooms
 * 轻量在线状态：房间广场（建房/进房）+ 进房后展示在线成员与各自专注计时。
 * 通过心跳轮询维持在线（每 15s 上报，后端 90s 窗口判离线），不引入 WebSocket。
 * 「开始专注」开启本地正计时并上报，房内成员可见彼此的专注时长，营造一起自习的氛围。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { DoorOpen, LogOut, Plus, RefreshCw, Timer, Users } from 'lucide-react';
import { api } from '../api';
import type { RoomDetail, RoomSummary } from '../types';

/** 秒 → MM:SS / H:MM:SS */
function fmt(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

const HEARTBEAT_MS = 15000;

export function SharedRooms() {
  const [rooms, setRooms] = useState<RoomSummary[]>([]);
  const [room, setRoom] = useState<RoomDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [focusing, setFocusing] = useState(false);
  const [seconds, setSeconds] = useState(0);

  // 用 ref 让心跳定时器读到最新的专注状态/秒数，避免闭包过期
  const focusingRef = useRef(false);
  const secondsRef = useRef(0);
  focusingRef.current = focusing;
  secondsRef.current = seconds;

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

  // 本地正计时：专注中每秒 +1
  useEffect(() => {
    if (!focusing) return;
    const t = window.setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => window.clearInterval(t);
  }, [focusing]);

  // 进房后心跳轮询：每 15s 上报在线 + 专注状态，刷新成员列表
  useEffect(() => {
    if (!room) return;
    const id = room.id;
    const beat = async () => {
      try {
        const d = await api.roomHeartbeat(id, focusingRef.current, secondsRef.current);
        setRoom(d);
      } catch { /* 网络抖动忽略，下次心跳重试 */ }
    };
    const t = window.setInterval(() => void beat(), HEARTBEAT_MS);
    return () => window.clearInterval(t);
  }, [room?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function create() {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const d = await api.createRoom(name.trim());
      setRoom(d);
      setName('');
      resetFocus();
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
      setRoom(d);
      resetFocus();
    } catch (err) {
      setError(err instanceof Error ? err.message : '加入失败');
    } finally {
      setBusy(false);
    }
  }

  async function leave() {
    if (!room) return;
    setBusy(true);
    try {
      await api.leaveRoom(room.id);
    } catch { /* ignore */ }
    setRoom(null);
    resetFocus();
    setBusy(false);
    void loadRooms();
  }

  function resetFocus() {
    setFocusing(false);
    setSeconds(0);
  }

  async function toggleFocus() {
    const next = !focusing;
    setFocusing(next);
    // 立即上报一次，房内他人尽快看到状态变化
    if (room) {
      try {
        const d = await api.roomHeartbeat(room.id, next, secondsRef.current);
        setRoom(d);
      } catch { /* ignore */ }
    }
  }

  // ===== 房间内视图 =====
  if (room) {
    return (
      <section className="panel shared-room">
        <div className="panel-title">
          <h2><Users size={16} style={{ verticalAlign: '-3px' }} /> {room.name} <span className="muted small">· 在线 {room.onlineCount}</span></h2>
          <button className="ghost-button small" onClick={() => void leave()} disabled={busy}><LogOut size={14} /> 离开</button>
        </div>
        <div className="shared-room-self">
          <div className="shared-room-timer"><Timer size={16} /> {fmt(seconds)}</div>
          <button className={`primary-button${focusing ? ' is-on' : ''}`} onClick={() => void toggleFocus()}>
            {focusing ? '结束专注' : '开始专注'}
          </button>
        </div>
        <div className="shared-room-members">
          {room.members.map((m) => (
            <div key={m.userId} className={`shared-member${m.focusing ? ' focusing' : ''}`}>
              <span className="shared-member-dot" />
              <strong>{m.name}{m.self ? '（我）' : ''}</strong>
              <span className="muted small">{m.focusing ? `专注中 ${fmt(m.focusSeconds)}` : '在线'}</span>
            </div>
          ))}
          {room.members.length === 0 && <div className="muted small">房间里还没有人，等小伙伴加入吧。</div>}
        </div>
      </section>
    );
  }

  // ===== 房间广场视图 =====
  return (
    <section className="panel shared-room">
      <div className="panel-title">
        <h2><Users size={16} style={{ verticalAlign: '-3px' }} /> 共享自习室</h2>
        <button className="icon-button" onClick={() => void loadRooms()} title="刷新"><RefreshCw size={14} /></button>
      </div>
      <p className="muted small" style={{ marginTop: -4 }}>和别人一起自习，互相看到在线与专注时长，更有动力。</p>
      <div className="shared-create">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="给自习室起个名字（可留空）" maxLength={40} />
        <button className="primary-button" onClick={() => void create()} disabled={busy}><Plus size={14} /> 建房</button>
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
            <button className="secondary-button compact-button" onClick={() => void join(r.id)} disabled={busy}>
              <DoorOpen size={14} /> 加入
            </button>
          </div>
        ))}
      </div>
    </section>
  );
}
