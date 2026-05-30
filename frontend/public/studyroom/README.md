# 自习室素材目录 / StudyRoom assets

把**免费可商用**（CC0 / Pixabay / Unsplash / Pexels / Freesound 等）素材放到这里即可自动生效，
无需改代码——场景的 `image` / `ambientFile` 路径在 [scenes.ts](../../src/studyroom/scenes.ts) 里已经预留。
文件缺失时会自动回退到 CSS 渐变 + Web Audio 合成环境音，所以零素材也能跑。

## 场景背景图（建议 1600×1000 左右，jpg/webp，单张 < 400KB）

放在本目录下，文件名与场景 id 对应：

| 文件名 | 场景 |
| --- | --- |
| `morning-window.jpg`     | 清晨窗边 |
| `rainy-cafe.jpg`         | 雨天咖啡店 |
| `night-library.jpg`      | 深夜图书馆 |
| `seaside-study.jpg`      | 海边书房 |
| `rainy-forest-cabin.jpg` | 雨天森林小屋 |
| `city-night.jpg`         | 都市夜景 |
| `nordic-snow.jpg`        | 北欧雪夜 |
| `courtyard-rain.jpg`     | 古庭院听雨 |

> 想换扩展名（如 .webp），同步改 scenes.ts 里对应场景的 `image` 字段即可。

## 环境音（可循环的 ogg/mp3，建议 30–60s 无缝循环）

默认用 Web Audio 程序化合成（雨声/海浪/白噪音/风声/林间）。若想用真实录音，
在场景对象里补一个 `ambientFile: '/studyroom/audio/xxx.ogg'`，它会优先于合成音。
建议放在 `audio/` 子目录。

## 音乐（Lo-fi / 轻音乐）

`music/` 子目录，曲目目录在 [scenes.ts](../../src/studyroom/scenes.ts) 的 `MUSIC_TRACKS`，
设置态可切换曲目。已接入两首（Pixabay Content License）：`music/lofi-1.mp3`、`music/lofi-2.mp3`。
往 `MUSIC_TRACKS` 里加一条 `{ id, name, src }` 即可扩充。

## 已落盘素材与许可（2026-05-30）

**场景图（8 张）**：均来自 [Unsplash](https://unsplash.com)，Unsplash License（免费、可商用、无需署名）。
文件名见上表，对应 scenes.ts 的 `image`。

**环境音（5 条，`audio/` 下）**：均来自 [Pixabay](https://pixabay.com)，Pixabay Content License（免费、可商用、无需署名）。已在 scenes.ts 用 `ambientFile` 接入：

| 文件 | 用于场景 |
| --- | --- |
| `audio/forest-birds.mp3` | 清晨窗边 |
| `audio/cafe.mp3`         | 雨天咖啡店 |
| `audio/ocean-waves.mp3`  | 海边书房 |
| `audio/rain.mp3`         | 雨天森林小屋、古庭院听雨 |
| `audio/wind.mp3`         | 北欧雪夜 |

> 深夜图书馆 / 都市夜景未配文件，继续用 Web Audio 合成白噪音。

## 授权提醒

下载素材属于"下载文件"操作，需逐个确认来源与授权（CC0 / 商用许可），不要用版权不明的素材。
