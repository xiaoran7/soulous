/**
 * 【宠物精灵图动画组件】
 * 使用 8×9 的 spritesheet atlas（1536×1872 像素，每格 192×208），
 * 覆盖 idle / running-right / running-left / waving / jumping / failed /
 * waiting / running / review 共 9 个动画状态。
 *
 * 动画播放原理（纯 CSS，合成器驱动）：
 * 1. 将整个 spritesheet 作为 <img> 的 src
 * 2. 每个动画状态对应一组静态 @keyframes（styles.css「宠物精灵帧动画」段），
 *    用 step-end 在各帧的累计时间点硬切 transform，百分比位移与渲染尺寸无关
 * 3. 完全没有 JS 定时器与 React 重渲染——此前逐帧 setState 会让邻近的
 *    backdrop-filter 玻璃元素每 100~300ms 重采样一次，在照片背景上表现为
 *    全站毛玻璃"一闪一闪"；改为合成器动画后绘制零失效，闪烁根除
 */
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
 * 【宠物精灵图组件】
 * 渲染一个基于 spritesheet 的逐帧动画宠物形象。
 * 帧推进全部由 styles.css 里的 `pet-anim-*` 类完成，本组件是纯静态渲染。
 *
 * @param state - 动画状态，决定挂哪个 pet-anim-* 动画类（即播放哪一行）
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
  /** 【按宽高比计算实际渲染高度】 */
  const height = Math.round(size * CELL_HEIGHT / CELL_WIDTH);
  /** 【sheet 整图尺寸】transform 位移用百分比（相对 img 自身），与 size 无关 */
  const sheetStyle = {
    width: size * COLUMNS,
    height: height * ROWS
  } satisfies CSSProperties;

  return (
    <div
      className="pet-sprite"
      data-pet-state={state}
      role="img"
      aria-label={`${label} ${state}`}
      style={{ width: size, height }}
    >
      <img
        aria-hidden="true"
        className={`pet-sprite__sheet pet-anim-${state}`}
        src={sheet || FEIXUE_SPRITESHEET}
        alt=""
        draggable={false}
        style={sheetStyle}
      />
    </div>
  );
}
