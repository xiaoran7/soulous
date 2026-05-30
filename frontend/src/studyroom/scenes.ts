/**
 * 【自习室场景目录】StudyRoom Scenes
 *
 * 灵感来自抖音爆款原型 "StudyWithMe AI"（选场景 → 调音 → 沉浸专注）。
 * 评论区第一诉求是"场景多样且带情绪/地点感"，因此这里把场景设计成
 * 可无限扩展的目录：视频原始 4 个 + 评论高频 4 个。
 *
 * 设计要点：
 * - 每个场景自带一套 **CSS 渐变兜底**（gradient），即使没有任何图片/音频素材，
 *   界面也能立刻呈现出有氛围的暗色背景，零素材可用。
 * - image / ambientFile 为可选的"真实素材路径"，后续把免费可商用素材放进
 *   frontend/public/studyroom/ 后自动启用，无需改组件逻辑。
 * - ambient 指定一种内置的 Web Audio 合成环境音（见 useAudioMixer），
 *   保证即便没有音频文件也有"白噪音/雨声"陪伴。
 */

/** 【内置环境音类型】对应 useAudioMixer 里的程序化合成器，无需音频文件 */
export type AmbientKind = 'rain' | 'waves' | 'whitenoise' | 'wind' | 'forest' | 'silent';

/**
 * 【自习室场景】
 * @property id          场景唯一标识（也用作素材文件名前缀）
 * @property name        场景中文名
 * @property mood        氛围描述（一行），呼应视频里每张卡片的副标题
 * @property tags        情绪/地点标签，用于将来筛选
 * @property gradient    CSS 背景（渐变兜底），无图片时直接使用
 * @property accent      场景主色（用于按钮/滑块高亮，营造场景一致性）
 * @property image       可选：真实场景大图路径（/studyroom/xxx.jpg）
 * @property ambient     内置合成环境音类型
 * @property ambientFile 可选：真实环境音循环文件路径（优先级高于合成）
 */
export interface StudyScene {
  id: string;
  name: string;
  mood: string;
  tags: string[];
  gradient: string;
  accent: string;
  image?: string;
  ambient: AmbientKind;
  ambientFile?: string;
}

/**
 * 【场景目录】
 * 前 4 个来自视频原型，后 4 个来自评论区高频许愿（雨天森林小屋 137 赞、
 * 都市夜景 67 赞、北欧雪夜、古庭院听雨）。
 */
export const SCENES: StudyScene[] = [
  {
    id: 'morning-window',
    name: '清晨窗边',
    mood: '晨光、绿植、安静书桌',
    tags: ['清晨', '明亮', '治愈'],
    gradient:
      'radial-gradient(120% 90% at 80% 0%, #f6d8a6 0%, #e6b98a 28%, #9a8f7c 60%, #4b4a44 100%)',
    accent: '#e0a866',
    image: '/studyroom/morning-window.jpg',
    ambient: 'forest',
    ambientFile: '/studyroom/audio/forest-birds.mp3',
  },
  {
    id: 'rainy-cafe',
    name: '雨天咖啡店',
    mood: '暖灯、咖啡、低声环境',
    tags: ['雨天', '温暖', '人声'],
    gradient:
      'radial-gradient(120% 100% at 70% 10%, #6b4a32 0%, #4a3526 40%, #2a2018 75%, #140f0b 100%)',
    accent: '#c98a4b',
    image: '/studyroom/rainy-cafe.jpg',
    ambient: 'rain',
    ambientFile: '/studyroom/audio/cafe.mp3',
  },
  {
    id: 'night-library',
    name: '深夜图书馆',
    mood: '书架、台灯、低干扰',
    tags: ['深夜', '安静', '专注'],
    gradient:
      'radial-gradient(90% 80% at 30% 30%, #5a4326 0%, #2e2418 45%, #161310 80%, #0b0a08 100%)',
    accent: '#d8b15a',
    image: '/studyroom/night-library.jpg',
    ambient: 'whitenoise',
  },
  {
    id: 'seaside-study',
    name: '海边书房',
    mood: '海浪、蓝光、开阔视野',
    tags: ['海边', '清爽', '开阔'],
    gradient:
      'radial-gradient(120% 100% at 50% 0%, #aee0e6 0%, #6fb6c4 32%, #3d7a8f 65%, #1c3a4a 100%)',
    accent: '#4ba3bf',
    image: '/studyroom/seaside-study.jpg',
    ambient: 'waves',
    ambientFile: '/studyroom/audio/ocean-waves.mp3',
  },
  {
    id: 'rainy-forest-cabin',
    name: '雨天森林小屋',
    mood: '绿森、薄雾、淅沥雨声',
    tags: ['雨天', '森林', '幽静'],
    gradient:
      'radial-gradient(110% 100% at 40% 15%, #5e7a52 0%, #3c5238 42%, #20301f 75%, #0e160d 100%)',
    accent: '#7faa63',
    image: '/studyroom/rainy-forest-cabin.jpg',
    ambient: 'rain',
    ambientFile: '/studyroom/audio/rain.mp3',
  },
  {
    id: 'city-night',
    name: '都市夜景',
    mood: '霓虹、落地窗、城市微光',
    tags: ['夜晚', '都市', '霓虹'],
    gradient:
      'radial-gradient(120% 100% at 75% 20%, #4b3b8f 0%, #322a63 38%, #1c1838 72%, #0c0a1a 100%)',
    accent: '#8a7fe0',
    image: '/studyroom/city-night.jpg',
    ambient: 'whitenoise',
  },
  {
    id: 'nordic-snow',
    name: '北欧雪夜',
    mood: '落雪、暖室、窗外风声',
    tags: ['雪夜', '寒冷', '暖意'],
    gradient:
      'radial-gradient(110% 100% at 60% 10%, #d6e2ec 0%, #9fb2c6 34%, #5b6a82 68%, #232a3a 100%)',
    accent: '#7e9bc4',
    image: '/studyroom/nordic-snow.jpg',
    ambient: 'wind',
    ambientFile: '/studyroom/audio/wind.mp3',
  },
  {
    id: 'courtyard-rain',
    name: '古庭院听雨',
    mood: '木檐、瓦上雨、苔绿',
    tags: ['雨天', '古风', '禅意'],
    gradient:
      'radial-gradient(110% 100% at 45% 12%, #7c8a76 0%, #4f5c4c 40%, #2c352b 74%, #131712 100%)',
    accent: '#8fa07d',
    image: '/studyroom/courtyard-rain.jpg',
    ambient: 'rain',
    ambientFile: '/studyroom/audio/rain.mp3',
  },
];

/** 【默认场景 ID】首次进入自习室时选中 */
export const DEFAULT_SCENE_ID = 'morning-window';

/** 【按 ID 取场景】找不到时回退到默认场景，保证组件永远拿得到一个场景 */
export function getScene(id: string | null | undefined): StudyScene {
  return SCENES.find(s => s.id === id) ?? SCENES.find(s => s.id === DEFAULT_SCENE_ID) ?? SCENES[0];
}

/**
 * 【番茄钟时长预设】对齐视频里的 25/45/50/90，并保留 15 分钟轻量档。
 */
export const DURATION_PRESETS = [15, 25, 45, 50, 90];

/**
 * 【背景音乐曲目】Lo-fi / 轻音乐，独立于场景环境音的一条音轨。
 * 素材来自 Pixabay（Pixabay Content License，免费可商用、无需署名），
 * 放在 /studyroom/music/ 下。可继续往这里加曲目。
 */
export interface MusicTrack {
  id: string;
  name: string;
  src: string;
}

export const MUSIC_TRACKS: MusicTrack[] = [
  { id: 'lofi-1', name: 'Lo-fi · 慢拍', src: '/studyroom/music/lofi-1.mp3' },
  { id: 'lofi-2', name: 'Lo-fi · 暖意', src: '/studyroom/music/lofi-2.mp3' },
];

/** 【默认曲目 ID】 */
export const DEFAULT_MUSIC_ID = MUSIC_TRACKS[0].id;

/** 【按 ID 取曲目】找不到回退到第一首 */
export function getMusicTrack(id: string | null | undefined): MusicTrack {
  return MUSIC_TRACKS.find(t => t.id === id) ?? MUSIC_TRACKS[0];
}
