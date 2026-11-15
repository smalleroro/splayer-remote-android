# SPlayer 遥控

用手机控制电脑上的 [SPlayer-Next](https://github.com/SPlayer-Dev/SPlayer-Next) 的安卓应用。

Kotlin + Jetpack Compose 编写，通过 SPlayer 内置的外部 HTTP API 通信。

> **非官方项目。** 本项目与 SPlayer-Next 团队无关，仅调用其官方公开的外部 API。
> 应用图标取自 SPlayer-Next 项目，用于标识所控制的播放器，版权归原作者所有。

## 功能

**播放控制**
- 播放 / 暂停 / 上一首 / 下一首 / 停止
- 进度条拖动跳转
- 音量滑杆

**显示**
- 专辑封面、歌名、歌手
- 音质标签（FLAC · 96kHz · 24bit）
- 播放队列**按需分页**浏览，上千首也能滚到任意位置，当前曲目正确高亮

**歌词**
- 支持 TTML / QRC / KRC / YRC 的**逐字高亮**（依赖歌词源提供字级时间）
- LRC / ASS / SRT 做整行扫过（歌词源只有整行时间，按字符均分并留出滞后缓冲）
- 双语对照（原文 + 翻译）
- 自动滚动跟随，手动滑动时暂停跟随、4 秒后恢复

**通知栏**
- 常驻通知，锁屏可见，进度条随播放推进
- 上一首 / 暂停 / 下一首三个按钮，应用在后台也能控制
- 可在设置页随时停掉常驻通知

**设置**（12 项，全部生效）
- 连接：服务器地址、自动发现、启动自动连接、失败自动扫描、刷新间隔（500–5000ms）
- 外观：主题（跟随系统/深色/浅色）、封面尺寸（小/中/大）
- 歌词：显示翻译、字号、自动滚动
- 通知：常驻通知开关、通知封面开关
- 行为：退出时停止服务

**其他**
- Material You 动态取色（Android 12+ 跟随壁纸）
- 图标全部手绘矢量，不依赖图标库
- 局域网自动发现服务器

## 前置条件

在 SPlayer-Next 中开启外部 API：

1. **设置 → 外部 API**：打开「启用外部 API」
2. 同一页打开「允许局域网访问」
3. 记下端口，默认 `14558`

> 外部 API 官方文档明确说明「不含任何鉴权」，所以只应在可信局域网中使用。
> 开启局域网访问后，同网段的任何人都能控制你的播放器。

## 下载

[最新发行版](https://github.com/smalleroro/splayer-remote-android/releases/latest) / [v1.1.0 APK](https://github.com/smalleroro/splayer-remote-android/releases/download/v1.1.0/splayer-remote-1.1.0.apk)

## 使用

1. 安装 APK，手机与电脑连同一个 Wi-Fi
2. 打开应用，**会自动搜索同网段的 SPlayer**（默认开启，约 30 秒内连上）
3. 连不上时点右上角齿轮进设置，直接填 `http://<电脑内网IP>:14558`，或点「自动发现」

地址会记住，下次直接进。地址写错也不要紧，下次连不上会自动重新扫描并改回来。

## 构建

环境要求：

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK Platform | 35 |
| Build Tools | 35.0.0 |
| Gradle | 8.10.2（wrapper 自带）|
| Kotlin | 2.0.21 |

```bash
# 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 构建
./gradlew assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

## 技术说明

**只有一个会话，只有一个轮询**

`SessionRepository` 是全应用唯一的 API 客户端与轮询循环。界面与前台服务都只订阅它的状态，自己不发请求。

上一版是 ViewModel 和 Service 各建一个客户端、各自轮询：前台时约 10 请求/秒，同一个断网场景 App 说「连接中」而通知说「无法连接」，改完服务器地址通知栏还连着旧机器。现在这些问题在结构上不可能发生。

生命周期用引用计数：Activity 与 Service 各 `acquire` / `release`，双方都退出才真正停轮询。

**轮询节奏随场景变化**

播放中、前台、用户设置的间隔决定节奏；暂停或退到后台自动放宽（最多 ×4），上限 5 秒。失败时先走 300–1000ms 的短重试，判定断开后改 3 秒。

**为什么不直接用轮询位置**

服务器状态每秒才回来一次，直接把 `status.position` 交给界面会有两个问题：进度条比实际慢一拍，逐字歌词每秒才跳一次。

所以做了一个本地插值时钟（`ui/SmoothPosition.kt` + `domain/PlaybackInterpolation.kt`）：把每次拿到的 `(position, 本地时刻)` 当锚点，两帧之间按帧时间乘以倍速自己往前推。轮询间隔只影响「多久校准一次」，不影响流畅度。

还补偿了网络往返：记录请求发起时刻，播放中把这段延迟加回去。外推有 6 秒上限——拿不到新采样时会停在原地而不是越跑越远。

**歌词高亮的两种路径**

| 歌词格式 | `wordTimed` | 处理 |
|---|---|---|
| TTML / QRC / KRC / YRC | `true` | 每个字/词有真实起止时间，按其进度点亮 |
| LRC / ASS / SRT | `false` | 只有整行时间，按字符均分并乘 0.85 系数留滞后缓冲 |

第二类刻意做得「略慢」而不是「略快」——演唱节奏不均匀时，提前亮起比慢半拍更刺眼。两套规则都在 `domain/Lyrics.kt` 里，是纯函数，有单测覆盖。

**失败分层**

单次请求失败不会立刻标「未连接」，连续失败 2 次才判定断开。WiFi 抖动和服务端偶发卡顿会被吞掉，界面不会每秒闪一次。

指令失败分两类：HTTP 200 但 `{"ok":false}` 视为业务拒绝，只提示不改变连接状态；传输失败交给轮询状态机判定，避免一次切歌超时就把界面刷成离线。

**音量与进度不打架**

拖动音量时先用本地值，150ms 内合并成一次请求，并在 800ms 内拒绝服务端回读覆盖——否则轮询会把滑块拽回旧位置。

## 测试

```bash
./gradlew testDebugUnitTest
```

36 项 JVM 单测，不需要模拟器：

| 组 | 覆盖 |
|---|---|
| `PollPolicyTest` | 轮询节奏、退避、断开阈值、发现节流 |
| `LyricsTest` | 行定位边界、逐字进度、扫过系数不抢拍 |
| `PlaybackInterpolationTest` | 外推上限、倍速、时长夹取、往返补偿 |
| `TimeFormatTest` | 时间格式化边界 |
| `HttpSPlayerApiTest` | MockWebServer 打桩：解析、未知字段、`ok:false`、HTTP 错误、请求体 |

CI（`.github/workflows/android.yml`）在每次 push 与 PR 上跑单测 + 构建。

## 已知限制

- **不能点队列切歌**。SPlayer 的外部 HTTP API 没有「跳到队列第 N 首」这个接口，只有 `next` / `prev`。
- **不能改循环 / 随机模式**。这两个能力只存在于插件 API 和 MCP，未在外部 API 暴露。
- **音质标签是「音源标称」而非「当前播放音质」**。SPlayer 的 `track.quality` 来自元数据，切换播放音质后它不会变化；真实解码参数只在 Electron 内部 IPC（`player:getStreamInfo`）里，外部 API 未暴露。
- **歌词取决于歌词源**。格式为 LRC 的歌没有字级时间，做不了真逐字；网易云偶发 `incorrect header check` 也会导致没歌词。

## 相关

- SPlayer-Next：[SPlayer-Dev/SPlayer-Next](https://github.com/SPlayer-Dev/SPlayer-Next)（AGPL-3.0）

## 许可

[MIT](LICENSE)

应用图标来自 SPlayer-Next 项目，不在本项目的 MIT 许可范围内，版权归原作者所有。
