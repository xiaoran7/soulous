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
| `cherry-path.jpg`        | 樱花小径（视频 poster，Mixkit 缩略图） |
| `starry-lake.jpg`        | 星空湖畔（视频 poster，Mixkit 缩略图） |

> 想换扩展名（如 .webp），同步改 scenes.ts 里对应场景的 `image` 字段即可。

## 动态背景视频（可选，mp4/webm，建议 < 12MB、720p、可无缝循环）

放在 `video/` 子目录，在场景对象里补 `video: '/studyroom/video/xxx.mp4'`（优先级高于 `image`，
`image` 自动降级为视频 poster 兜底）。`SceneBackdrop` 用 `<video autoplay loop muted playsInline>` 渲染。

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

**动态背景视频（8 条，`video/` 下，2026-06-13；画面已逐一对齐对应场景图）**：均来自 [Mixkit](https://mixkit.co)，Mixkit License（免费、可商用、无需署名）。
> 分辨率（2026-06-13 终版，体积不敏感档，取各场景 Mixkit 可得最高分辨率）：
> - **2160p / 4K**：city-night / rainy-forest-cabin / nordic-snow / starry-lake（有 4K 源）。
> - **1080p**：courtyard-rain / seaside-study（素材 Mixkit 上限 1080）。
> - **720p**：rainy-cafe / cherry-path（素材 Mixkit 上限 720，若要更清晰需换素材并牺牲贴合）。
> - 处理：`ffmpeg -c:v copy -an -movflags +faststart` **无损 remux**（保留原码流画质，仅去音轨 + faststart 边下边播）。只用横屏素材。
> - 卡顿验证：8 个场景在本机 preview 实测 `getVideoPlaybackQuality()`，均满帧（24–30fps）、**0 丢帧**，含 4 个 4K 场景——不卡顿，故全部采用最高清版本。
> - 静→动过渡：SceneBackdrop 静态底图先铺，视频 `preload=auto` 静默缓冲，`onPlaying` 后 `.is-ready` 淡入覆盖——消除首帧跳变/解码卡顿（`isReady` 已验证）。
> - 体积代价：4K 单文件 46–124MB，本地秒开；**部署到 VPS 时远端首帧需等下载**，过渡期由静态底图兜住不白屏。
> - 注：部分氛围片本身是浅景深/雾感/夜景（雨打窗虚化、晨雾松林等），观感偏柔属素材风格，非分辨率问题。
已在 scenes.ts 用 `video` 接入：

| 文件 | 场景 | 画面 |
| --- | --- | --- |
| `video/rainy-cafe.mp4`          | 雨天咖啡店     | 雨滴打在窗上、窗外绿树虚化 |
| `video/courtyard-rain.mp4`      | 古庭院听雨     | 雨点落入绿水、倒影涟漪（禅意） |
| `video/seaside-study.mp4`       | 海边书房       | 开阔海岸、海浪与海平线（清晰对焦，替换原失焦素材） |
| `video/city-night.mp4`          | 都市夜景       | 夜晚天际线 + 楼宇灯火 + 车流 |
| `video/nordic-snow.mp4`         | 北欧雪夜       | 雪松林 + 暮色明月 |
| `video/rainy-forest-cabin.mp4`  | 雨天森林小屋   | 薄雾松林 |
| `video/cherry-path.mp4`         | 樱花小径（新） | 暖阳樱花小径、蒲公英绿地 |
| `video/starry-lake.mp4`         | 星空湖畔（新） | 银河繁星倒映在静湖（延时） |

> 清晨窗边 / 深夜图书馆保留静态图。樱花小径 / 星空湖畔为新增场景，poster 用 Mixkit 缩略图。

**音乐共 7 首（`music/` 下）**：`lofi-1/2`（Pixabay）；以下 5 首来自 [Mixkit](https://mixkit.co)，Mixkit License，命名沿用情绪标签：
`chill-serene`（氛围·静谧）、`light-calm`（轻音乐·舒缓）、`ambient-meditation`（环境·冥想）、`light-night`（轻音乐·夜阑）、`ambient-evening`（氛围·晚风）。

## 授权提醒

下载素材属于"下载文件"操作，需逐个确认来源与授权（CC0 / 商用许可），不要用版权不明的素材。
