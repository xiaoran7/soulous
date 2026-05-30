/**
 * 【自习室音频混音器】useAudioMixer
 *
 * 两条独立音轨：
 * 1. **环境音**：优先用真实音频文件循环（场景的 ambientFile，含用户上传的自定义音）；
 *    否则用 Web Audio API 程序化合成（雨声/海浪/白噪音/风声/林间），零素材也有声。
 * 2. **音乐**：HTMLAudioElement 循环播放音乐文件（含自定义上传）。
 *
 * 浏览器自动播放策略要求音频必须由**用户手势**触发，否则被静音拦截。
 * 因此本 hook 不自动出声，而是返回一个 `start()`，必须在点击事件里调用
 * （对应界面上显式的「开启声音」按钮）。`playing` 反映当前是否在出声。
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import type { AmbientKind } from './scenes';

/** 【混音器参数】 */
export interface AudioMixerOptions {
  ambient: AmbientKind;
  ambientFile?: string;
  musicSrc?: string;
  ambientVolume: number;
  musicVolume: number;
  ambientEnabled: boolean;
  musicEnabled: boolean;
}

interface SynthHandle {
  output: GainNode;
  stop: () => void;
}

/** 【生成 2 秒可循环白噪音缓冲】 */
function makeNoiseBuffer(ctx: AudioContext): AudioBuffer {
  const len = Math.floor(ctx.sampleRate * 2);
  const buf = ctx.createBuffer(1, len, ctx.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1;
  return buf;
}

function noiseSource(ctx: AudioContext, buffer: AudioBuffer): AudioBufferSourceNode {
  const src = ctx.createBufferSource();
  src.buffer = buffer;
  src.loop = true;
  return src;
}

/**
 * 【按类型构建合成环境音图】对白噪音做不同滤波 + LFO 调制模拟自然声响。
 */
function buildSynth(ctx: AudioContext, kind: AmbientKind, buffer: AudioBuffer): SynthHandle {
  const output = ctx.createGain();
  output.gain.value = 0;
  const nodes: AudioScheduledSourceNode[] = [];
  const start = (n: AudioScheduledSourceNode) => { n.start(); nodes.push(n); };

  switch (kind) {
    case 'rain': {
      const hiss = noiseSource(ctx, buffer);
      const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 1100;
      const patter = ctx.createGain(); patter.gain.value = 0.5;
      const lfo = ctx.createOscillator(); lfo.frequency.value = 0.6;
      const lfoGain = ctx.createGain(); lfoGain.gain.value = 0.18;
      lfo.connect(lfoGain).connect(patter.gain);
      hiss.connect(hp).connect(patter).connect(output);
      const rumble = noiseSource(ctx, buffer);
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 380;
      const rumbleGain = ctx.createGain(); rumbleGain.gain.value = 0.35;
      rumble.connect(lp).connect(rumbleGain).connect(output);
      start(hiss); start(rumble); start(lfo);
      break;
    }
    case 'waves': {
      const surf = noiseSource(ctx, buffer);
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 520;
      const swell = ctx.createGain(); swell.gain.value = 0.45;
      const lfo = ctx.createOscillator(); lfo.frequency.value = 0.12;
      const lfoGain = ctx.createGain(); lfoGain.gain.value = 0.4;
      lfo.connect(lfoGain).connect(swell.gain);
      surf.connect(lp).connect(swell).connect(output);
      start(surf); start(lfo);
      break;
    }
    case 'wind': {
      const air = noiseSource(ctx, buffer);
      const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = 600; bp.Q.value = 0.7;
      const lfo = ctx.createOscillator(); lfo.frequency.value = 0.18;
      const lfoGain = ctx.createGain(); lfoGain.gain.value = 300;
      lfo.connect(lfoGain).connect(bp.frequency);
      const g = ctx.createGain(); g.gain.value = 0.6;
      air.connect(bp).connect(g).connect(output);
      start(air); start(lfo);
      break;
    }
    case 'forest': {
      const leaves = noiseSource(ctx, buffer);
      const hp = ctx.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 2200;
      const g = ctx.createGain(); g.gain.value = 0.28;
      leaves.connect(hp).connect(g).connect(output);
      start(leaves);
      break;
    }
    case 'whitenoise': {
      const n = noiseSource(ctx, buffer);
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 7000;
      const g = ctx.createGain(); g.gain.value = 0.4;
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
 * @returns playing 当前是否出声；start() 在用户手势里调用以开启；stop() 静音。
 */
export function useAudioMixer(opts: AudioMixerOptions) {
  const { ambient, ambientFile, musicSrc, ambientVolume, musicVolume, ambientEnabled, musicEnabled } = opts;

  const [playing, setPlaying] = useState(false);
  const playingRef = useRef(false);

  const ctxRef = useRef<AudioContext | null>(null);
  const noiseRef = useRef<AudioBuffer | null>(null);
  const synthRef = useRef<SynthHandle | null>(null);
  const synthKindRef = useRef<AmbientKind | null>(null);
  const ambientElRef = useRef<HTMLAudioElement | null>(null);
  const musicElRef = useRef<HTMLAudioElement | null>(null);

  /** 停止合成/文件环境音 */
  const stopAmbient = useCallback(() => {
    if (synthRef.current) { synthRef.current.stop(); synthRef.current = null; synthKindRef.current = null; }
    if (ambientElRef.current) ambientElRef.current.pause();
  }, []);

  /** 同步环境音到当前参数（仅在 playing 时出声） */
  const syncAmbient = useCallback(() => {
    if (!playingRef.current || !ambientEnabled) { stopAmbient(); return; }
    if (ambientFile) {
      if (synthRef.current) { synthRef.current.stop(); synthRef.current = null; synthKindRef.current = null; }
      let el = ambientElRef.current;
      if (!el) { el = new Audio(); el.loop = true; ambientElRef.current = el; }
      if (el.src.indexOf(ambientFile) === -1) el.src = ambientFile;
      el.volume = ambientVolume;
      void el.play().catch(() => { /* 被拦截则静默，等用户再次点击开启 */ });
      return;
    }
    if (ambientElRef.current) ambientElRef.current.pause();
    if (!ctxRef.current) {
      const Ctor = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      ctxRef.current = new Ctor();
      noiseRef.current = makeNoiseBuffer(ctxRef.current);
    }
    const ctx = ctxRef.current;
    void ctx.resume().catch(() => { /* 忽略 */ });
    if (synthKindRef.current !== ambient) {
      if (synthRef.current) synthRef.current.stop();
      synthRef.current = buildSynth(ctx, ambient, noiseRef.current!);
      synthRef.current.output.connect(ctx.destination);
      synthKindRef.current = ambient;
    }
    if (synthRef.current) synthRef.current.output.gain.setTargetAtTime(ambientVolume, ctx.currentTime, 0.2);
  }, [ambient, ambientFile, ambientEnabled, ambientVolume, stopAmbient]);

  /** 同步音乐到当前参数 */
  const syncMusic = useCallback(() => {
    let el = musicElRef.current;
    if (!el) { el = new Audio(); el.loop = true; musicElRef.current = el; }
    if (!playingRef.current || !musicEnabled || !musicSrc) { el.pause(); return; }
    if (el.src.indexOf(musicSrc) === -1) el.src = musicSrc;
    el.volume = musicVolume;
    void el.play().catch(() => { /* 被拦截则静默 */ });
  }, [musicEnabled, musicSrc, musicVolume]);

  /** 【开启声音】必须在用户手势（点击）中调用 */
  const start = useCallback(() => {
    playingRef.current = true;
    setPlaying(true);
    // 在手势内同步触发，满足浏览器自动播放策略
    syncAmbient();
    syncMusic();
  }, [syncAmbient, syncMusic]);

  /** 【静音】 */
  const stop = useCallback(() => {
    playingRef.current = false;
    setPlaying(false);
    stopAmbient();
    if (musicElRef.current) musicElRef.current.pause();
  }, [stopAmbient]);

  // 出声时，参数变化（换场景/音量/开关）即时同步
  useEffect(() => { if (playingRef.current) syncAmbient(); }, [syncAmbient]);
  useEffect(() => { if (playingRef.current) syncMusic(); }, [syncMusic]);

  // 卸载清理
  useEffect(() => {
    return () => {
      if (synthRef.current) synthRef.current.stop();
      if (ambientElRef.current) { ambientElRef.current.pause(); ambientElRef.current.src = ''; }
      if (musicElRef.current) { musicElRef.current.pause(); musicElRef.current.src = ''; }
      if (ctxRef.current) void ctxRef.current.close().catch(() => { /* 忽略 */ });
    };
  }, []);

  return { playing, start, stop };
}
