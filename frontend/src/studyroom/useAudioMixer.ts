/**
 * 【自习室音频混音器】useAudioMixer
 *
 * 两条独立音轨：
 * 1. **环境音**：优先用真实音频文件循环（场景的 ambientFile）；若未提供文件，
 *    则用 Web Audio API **程序化合成**（雨声/海浪/白噪音/风声/林间），
 *    保证零素材也有环境音陪伴——这是自习室"进入状态"的核心。
 * 2. **音乐**：用 HTMLAudioElement 循环播放音乐文件（musicSrc）。暂无素材时
 *    传空即静默，滑块仍可调，后续放入素材自动生效。
 *
 * 浏览器策略：AudioContext 必须在用户手势后才能出声。本 hook 设计为
 * 仅在 playing=true（用户点击"进入自习室"后）才创建/恢复音频，符合规则。
 */
import { useEffect, useRef } from 'react';

import type { AmbientKind } from './scenes';

/** 【混音器参数】 */
export interface AudioMixerOptions {
  /** 内置合成环境音类型 */
  ambient: AmbientKind;
  /** 可选：真实环境音文件路径（优先于合成） */
  ambientFile?: string;
  /** 可选：音乐文件路径（为空则音乐静默） */
  musicSrc?: string;
  /** 环境音音量 0–1 */
  ambientVolume: number;
  /** 音乐音量 0–1 */
  musicVolume: number;
  /** 是否开启环境音 */
  ambientEnabled: boolean;
  /** 是否开启音乐 */
  musicEnabled: boolean;
  /** 是否处于播放态（进入自习室即 true） */
  playing: boolean;
}

/** 【合成环境音句柄】持有节点与停止函数 */
interface SynthHandle {
  output: GainNode;
  stop: () => void;
}

/** 【生成 2 秒可循环白噪音缓冲】供各类环境音作为声源 */
function makeNoiseBuffer(ctx: AudioContext): AudioBuffer {
  const len = Math.floor(ctx.sampleRate * 2);
  const buf = ctx.createBuffer(1, len, ctx.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1;
  return buf;
}

/** 【创建循环噪声源】 */
function noiseSource(ctx: AudioContext, buffer: AudioBuffer): AudioBufferSourceNode {
  const src = ctx.createBufferSource();
  src.buffer = buffer;
  src.loop = true;
  return src;
}

/**
 * 【按类型构建合成环境音图】
 * 各类型通过对白噪音做不同滤波 + 低频调制（LFO）模拟自然声响。
 * 返回一个统一的 output GainNode（音量在外层控制）与 stop 清理函数。
 */
function buildSynth(ctx: AudioContext, kind: AmbientKind, buffer: AudioBuffer): SynthHandle {
  const output = ctx.createGain();
  output.gain.value = 0;
  const nodes: AudioScheduledSourceNode[] = [];

  /** 启动并登记一个声源，便于统一停止 */
  const start = (n: AudioScheduledSourceNode) => { n.start(); nodes.push(n); };

  switch (kind) {
    case 'rain': {
      // 高频嘶嘶（雨打）+ 低频闷响（雨幕），并用慢 LFO 给雨"忽大忽小"
      const hiss = noiseSource(ctx, buffer);
      const hp = ctx.createBiquadFilter();
      hp.type = 'highpass';
      hp.frequency.value = 1100;
      const patter = ctx.createGain();
      patter.gain.value = 0.5;
      const lfo = ctx.createOscillator();
      lfo.frequency.value = 0.6;
      const lfoGain = ctx.createGain();
      lfoGain.gain.value = 0.18;
      lfo.connect(lfoGain).connect(patter.gain);
      hiss.connect(hp).connect(patter).connect(output);

      const rumble = noiseSource(ctx, buffer);
      const lp = ctx.createBiquadFilter();
      lp.type = 'lowpass';
      lp.frequency.value = 380;
      const rumbleGain = ctx.createGain();
      rumbleGain.gain.value = 0.35;
      rumble.connect(lp).connect(rumbleGain).connect(output);

      start(hiss); start(rumble); start(lfo);
      break;
    }
    case 'waves': {
      // 低通噪声 + 极慢 LFO 制造潮汐涨落
      const surf = noiseSource(ctx, buffer);
      const lp = ctx.createBiquadFilter();
      lp.type = 'lowpass';
      lp.frequency.value = 520;
      const swell = ctx.createGain();
      swell.gain.value = 0.45;
      const lfo = ctx.createOscillator();
      lfo.frequency.value = 0.12;
      const lfoGain = ctx.createGain();
      lfoGain.gain.value = 0.4;
      lfo.connect(lfoGain).connect(swell.gain);
      surf.connect(lp).connect(swell).connect(output);
      start(surf); start(lfo);
      break;
    }
    case 'wind': {
      // 带通噪声 + LFO 调制滤波频率，模拟风的呼啸起伏
      const air = noiseSource(ctx, buffer);
      const bp = ctx.createBiquadFilter();
      bp.type = 'bandpass';
      bp.frequency.value = 600;
      bp.Q.value = 0.7;
      const lfo = ctx.createOscillator();
      lfo.frequency.value = 0.18;
      const lfoGain = ctx.createGain();
      lfoGain.gain.value = 300;
      lfo.connect(lfoGain).connect(bp.frequency);
      const g = ctx.createGain();
      g.gain.value = 0.6;
      air.connect(bp).connect(g).connect(output);
      start(air); start(lfo);
      break;
    }
    case 'forest': {
      // 轻柔高通噪声（叶动/微风），低音量，营造林间静谧
      const leaves = noiseSource(ctx, buffer);
      const hp = ctx.createBiquadFilter();
      hp.type = 'highpass';
      hp.frequency.value = 2200;
      const g = ctx.createGain();
      g.gain.value = 0.28;
      leaves.connect(hp).connect(g).connect(output);
      start(leaves);
      break;
    }
    case 'whitenoise': {
      // 经典低通白噪音，稳定专注底噪
      const n = noiseSource(ctx, buffer);
      const lp = ctx.createBiquadFilter();
      lp.type = 'lowpass';
      lp.frequency.value = 7000;
      const g = ctx.createGain();
      g.gain.value = 0.4;
      n.connect(lp).connect(g).connect(output);
      start(n);
      break;
    }
    case 'silent':
    default:
      break;
  }

  return {
    output,
    stop: () => {
      nodes.forEach(n => { try { n.stop(); } catch { /* 已停止 */ } });
      try { output.disconnect(); } catch { /* 已断开 */ }
    },
  };
}

/**
 * 【音频混音 Hook】
 * 根据参数管理环境音（合成或文件）与音乐（文件）的播放与音量。
 * 自身不返回内容，副作用即音频播放；卸载时清理所有资源。
 */
export function useAudioMixer(opts: AudioMixerOptions) {
  const { ambient, ambientFile, musicSrc, ambientVolume, musicVolume, ambientEnabled, musicEnabled, playing } = opts;

  const ctxRef = useRef<AudioContext | null>(null);
  const noiseRef = useRef<AudioBuffer | null>(null);
  const synthRef = useRef<SynthHandle | null>(null);
  const synthKindRef = useRef<AmbientKind | null>(null);
  const ambientElRef = useRef<HTMLAudioElement | null>(null);
  const musicElRef = useRef<HTMLAudioElement | null>(null);

  // ===== 环境音（合成 or 文件） =====
  useEffect(() => {
    // 未播放或未开启：停掉环境音
    if (!playing || !ambientEnabled) {
      if (synthRef.current) { synthRef.current.stop(); synthRef.current = null; synthKindRef.current = null; }
      if (ambientElRef.current) { ambientElRef.current.pause(); }
      return;
    }

    // 优先真实音频文件
    if (ambientFile) {
      if (synthRef.current) { synthRef.current.stop(); synthRef.current = null; synthKindRef.current = null; }
      let el = ambientElRef.current;
      if (!el) { el = new Audio(); el.loop = true; ambientElRef.current = el; }
      if (el.src.indexOf(ambientFile) === -1) el.src = ambientFile;
      el.volume = ambientVolume;
      void el.play().catch(() => { /* 文件缺失/被拦截则静默 */ });
      return;
    }

    // 否则用 Web Audio 合成
    if (ambientElRef.current) ambientElRef.current.pause();
    if (!ctxRef.current) {
      const Ctor = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      ctxRef.current = new Ctor();
      noiseRef.current = makeNoiseBuffer(ctxRef.current);
    }
    const ctx = ctxRef.current;
    void ctx.resume().catch(() => { /* 忽略 */ });

    // 场景（ambient 类型）变了才重建合成图
    if (synthKindRef.current !== ambient) {
      if (synthRef.current) synthRef.current.stop();
      synthRef.current = buildSynth(ctx, ambient, noiseRef.current!);
      synthRef.current.output.connect(ctx.destination);
      synthKindRef.current = ambient;
    }
    // 平滑设置音量
    if (synthRef.current) {
      synthRef.current.output.gain.setTargetAtTime(ambientVolume, ctx.currentTime, 0.2);
    }
  }, [playing, ambientEnabled, ambient, ambientFile, ambientVolume]);

  // ===== 音乐（文件） =====
  useEffect(() => {
    let el = musicElRef.current;
    if (!el) { el = new Audio(); el.loop = true; musicElRef.current = el; }

    if (!playing || !musicEnabled || !musicSrc) {
      el.pause();
      return;
    }
    if (el.src.indexOf(musicSrc) === -1) el.src = musicSrc;
    el.volume = musicVolume;
    void el.play().catch(() => { /* 文件缺失/被拦截则静默 */ });
  }, [playing, musicEnabled, musicSrc, musicVolume]);

  // 音乐音量实时跟随（不触发重新 play）
  useEffect(() => {
    if (musicElRef.current) musicElRef.current.volume = musicVolume;
  }, [musicVolume]);
  useEffect(() => {
    if (ambientElRef.current) ambientElRef.current.volume = ambientVolume;
  }, [ambientVolume]);

  // ===== 卸载清理 =====
  useEffect(() => {
    return () => {
      if (synthRef.current) synthRef.current.stop();
      if (ambientElRef.current) { ambientElRef.current.pause(); ambientElRef.current.src = ''; }
      if (musicElRef.current) { musicElRef.current.pause(); musicElRef.current.src = ''; }
      if (ctxRef.current) void ctxRef.current.close().catch(() => { /* 忽略 */ });
    };
  }, []);
}
