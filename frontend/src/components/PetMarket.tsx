/**
 * 【宠物市场】PetMarket
 * 弹层形式展示：当前金币余额 + 已拥有宠物（可切换出战）+ 上架品种目录（免费领养首只 / 金币购买）。
 * 每只宠物独立成长；新用户首只入门款免费领养，其余款式用金币购买。
 */
import React, { useEffect, useState } from 'react';
import { Coins, Sparkles } from 'lucide-react';
import { api } from '../api';
import type { Pet, PetSpecies } from '../types';
import { PetSprite } from '../PetSprite';
import { ModalShell } from './Modal';

const rarityLabel: Record<string, string> = { COMMON: '普通', RARE: '稀有', EPIC: '史诗' };

export function PetMarket({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const [species, setSpecies] = useState<PetSpecies[]>([]);
  const [owned, setOwned] = useState<Pet[]>([]);
  const [balance, setBalance] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string>('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function reload() {
    try {
      const [sp, ow, w] = await Promise.all([api.petSpecies(), api.petOwned(), api.wallet()]);
      setSpecies(Array.isArray(sp) ? sp : []);
      setOwned(Array.isArray(ow) ? ow : []);
      setBalance(w?.balance ?? 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载市场失败');
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => { void reload(); }, []);

  const ownedBySlug = new Map(owned.map((p) => [p.species?.slug ?? '', p]));
  const hasAnyPet = owned.length > 0;

  async function run(key: string, fn: () => Promise<unknown>, ok: string) {
    if (busy) return;
    setBusy(key);
    setError('');
    setNotice('');
    try {
      await fn();
      setNotice(ok);
      await reload();
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败');
    } finally {
      setBusy('');
    }
  }

  return (
    <ModalShell width={620} onClose={onClose}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <strong style={{ fontSize: 16 }}>宠物市场</strong>
        <span className="checkin-balance" style={{ fontSize: 14 }}><Coins size={15} /> {balance}</span>
      </div>

      {loading && <div className="muted small">加载中…</div>}
      {error && <div className="form-error" style={{ marginBottom: 8 }}>{error}</div>}
      {notice && <div className="notice" style={{ marginBottom: 8 }}>{notice}</div>}

      {!loading && (
        <div className="pet-market-grid">
          {species.map((sp) => {
            const mine = ownedBySlug.get(sp.slug);
            const active = mine?.active;
            const canAdopt = !hasAnyPet && sp.starter;
            const affordable = balance >= sp.price;
            return (
              <div key={sp.slug} className={`pet-market-card${active ? ' active' : ''}`}>
                <div className="pet-market-frame">
                  <PetSprite state="idle" size={64} sheet={sp.spritePath} label={sp.name} />
                </div>
                <div className="pet-market-meta">
                  <strong>{sp.name}</strong>
                  <span className="chip small">{rarityLabel[sp.rarity ?? ''] ?? sp.rarity ?? '普通'}</span>
                </div>
                <p className="muted small">{sp.description}</p>
                {mine ? (
                  active ? (
                    <button className="secondary-button compact-button" disabled><Sparkles size={13} /> 出战中</button>
                  ) : (
                    <button className="secondary-button compact-button" disabled={!!busy}
                      onClick={() => void run('a' + sp.slug, () => api.setActivePet(mine.id), `${sp.name} 已出战`)}>
                      设为出战
                    </button>
                  )
                ) : canAdopt ? (
                  <button className="primary-button compact-button" disabled={!!busy}
                    onClick={() => void run('d' + sp.slug, () => api.adoptPet(sp.slug), `领养了 ${sp.name}！`)}>
                    免费领养
                  </button>
                ) : (
                  <button className="primary-button compact-button" disabled={!!busy || (sp.price > 0 && !affordable)}
                    onClick={() => void run('b' + sp.slug, () => api.buyPet(sp.slug), `购买了 ${sp.name}！`)}>
                    {sp.price > 0 ? <><Coins size={13} /> {sp.price}{affordable ? '' : '（不足）'}</> : '免费获得'}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
      <div className="row-actions" style={{ marginTop: 16, justifyContent: 'flex-end' }}>
        <button className="secondary-button" onClick={onClose}>关闭</button>
      </div>
    </ModalShell>
  );
}
