/**
 * 【宠物精灵图动画组件】
 * 通过 CSS transform 移动 spritesheet 实现逐帧动画效果。
 * 使用 8×9 的 spritesheet atlas（1536×1872 像素，每格 192×208），
 * 覆盖 idle / running-right / running-left / waving / jumping / failed /
 * waiting / running / review 共 9 个动画状态。
 *
 * 动画播放原理：
 * 1. 将整个 spritesheet 作为 <img> 的 src
 * 2. 通过 CSS transform: translate3d() 移动图片，只显示当前帧所在的格子
 * 3. 使用 setTimeout 按每帧的持续时间自动推进到下一帧
 * 4. 状态切换时重置帧索引为 0
 */
import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';

/** 【宠物动画状态枚举】 */
export type PetAnimationState =
  | 'idle'           /** 空闲待机 */
  | 'running-right'  /** 向右奔跑 */
  | 'running-left'   /** 向左奔跑 */
  | 'waving'         /** 挥手打招呼 */
  | 'jumping'        /** 兴奋跳跃 */
  | 'failed'         /** 失败沮丧 */
  | 'waiting'        /** 耐心等待 */
  | 'running'        /** 奔跑（通用） */
  | 'review';        /** 审核中 */

/** 【动画配置：行号 + 每帧持续时间数组（毫秒）】 */
type AnimationConfig = {
  row: number;
  durations: number[];
};

/** 【Spritesheet 资源路径】 */
const FEIXUE_SPRITESHEET = '/pets/feixue/spritesheet.webp';
/** 【单格宽度（像素）】 */
const CELL_WIDTH = 192;
/** 【单格高度（像素）】 */
const CELL_HEIGHT = 208;
/** 【spritesheet 列数】 */
const COLUMNS = 8;
/** 【spritesheet 行数（对应 9 个动画状态）】 */
const ROWS = 9;

/**
 * 【动画配置映射表】
 * 每个动画状态对应 spritesheet 的一行，durations 数组的长度即为该动画的总帧数。
 * 每个 duration 值控制该帧的显示时长（毫秒），实现不同节奏的动画效果。
 */
const animations: Record<PetAnimationState, AnimationConfig> = {
  idle: { row: 0, durations: [280, 110, 110, 140, 140, 320] },
  'running-right': { row: 1, durations: [120, 120, 120, 120, 120, 120, 120, 220] },
  'running-left': { row: 2, durations: [120, 120, 120, 120, 120, 120, 120, 220] },
  waving: { row: 3, durations: [140, 140, 140, 280] },
  jumping: { row: 4, durations: [140, 140, 140, 140, 280] },
  failed: { row: 5, durations: [140, 140, 140, 140, 140, 140, 140, 240] },
  waiting: { row: 6, durations: [150, 150, 150, 150, 150, 260] },
  running: { row: 7, durations: [120, 120, 120, 120, 120, 220] },
  review: { row: 8, durations: [150, 150, 150, 150, 150, 280] }
};

/**
 * 【宠物精灵图组件】
 * 渲染一个基于 spritesheet 的逐帧动画宠物形象。
 *
 * @param state - 动画状态，决定播放哪一行的动画
 * @param size - 显示尺寸（宽度），高度按比例自动计算，默认 132px
 * @param label - 无障碍标签文本，默认 'Feixue'
 */
export function PetSprite({
  state,
  size = 132,
  label = 'Feixue',
  sheet
}: {
  state: PetAnimationState;
  size?: number;
  label?: string;
  /** 【可选 spritesheet 路径】不同品种可传各自的图；省略则用默认 Feixue 图 */
  sheet?: string | null;
}) {
  /** 【当前帧索引，从 0 开始循环】 */
  const [frame, setFrame] = useState(0);
  const config = animations[state] ?? animations.idle;
  /** 【按宽高比计算实际渲染高度】 */
  const height = Math.round(size * CELL_HEIGHT / CELL_WIDTH);

  /**
   * 【状态切换时重置帧索引】
   * 确保每次动画状态变化都从第一帧开始播放，
   * 避免从中间帧切换导致的视觉跳变。
   */
  useEffect(() => {
    setFrame(0);
  }, [state]);

  /**
   * 【帧推进定时器】
   * 根据当前帧的持续时间设置 setTimeout，时间到后推进到下一帧（循环播放）。
   * frame 变化时重新设置定时器，形成自驱动的动画循环。
   */
  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setFrame((current) => (current + 1) % config.durations.length);
    }, config.durations[frame] ?? 160);

    return () => window.clearTimeout(timeout);
  }, [config, frame]);

  /**
   * 【spritesheet 位移样式】
   * 通过 translate3d 将 spritesheet 向左上方移动，
   * 使目标帧所在的格子对齐到组件的可视区域。
   * 使用 translate3d 触发 GPU 加速，确保动画流畅。
   */
  const sheetStyle = {
    width: size * COLUMNS,
    height: height * ROWS,
    transform: `translate3d(${-frame * size}px, ${-config.row * height}px, 0)`
  } satisfies CSSProperties;

  return (
    <div
      className="pet-sprite"
      data-pet-state={state}
      data-pet-frame={frame}
      role="img"
      aria-label={`${label} ${state}`}
      style={{ width: size, height }}
    >
      <img
        aria-hidden="true"
        className="pet-sprite__sheet"
        src={sheet || FEIXUE_SPRITESHEET}
        alt=""
        draggable={false}
        style={sheetStyle}
      />
    </div>
  );
}
