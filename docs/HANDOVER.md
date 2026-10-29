# PawAM 交接文档

最后更新：2026-10-30，HEAD = `c839cda` + 本轮验证

本文记录**当前真实状态**、已验证的证据，以及下一步该从哪里查起。计划不算证据，只有构建结果和真机日志算。

---

## 1. 工程现状

| | |
|---|---|
| 路径 | `C:\codes\projects\PawAM`，主工程在 `app-src/` |
| 基线 | `LineageOS/android_packages_apps_Twelve`，分支 `lineage-24.0`，Apache-2.0 |
| 包名 | `dev.pawam.twelve.dev`（debug），与系统版 `org.lineageos.twelve` 共存 |
| 构建 | `cd app-src; ./gradlew assembleDebug`（JDK 见 `gradle.properties`） |
| 测试 | `./gradlew testDebugUnitTest`，25 个用例通过 |
| 设备 | 小米 13 `c412785e`，LineageOS 24 / Android 17，Magisk root |

### 与上游的刻意差异

- 移除了 `org.lineageos.generatebp` 插件（只服务于 AOSP 平台构建，独立 Gradle 下无法解析）。
- `applicationId` 改为 `dev.pawam.twelve`。
- 新增 `ProviderType.APPLE_MUSIC` 与 `ProviderType.NETEASE`。
- 导航栏第 4 项固定为「切换提供商」，点击弹出提供商选择器。

---

## 2. 已验证可用的功能（有证据）

### Apple Music

| 环节 | 证据 |
|---|---|
| 登录 | `music_user_token` 246 字符，`storefront=cn` |
| 开发者令牌 | 自动抓取 `music.apple.com` 的 web player bundle，得到 268 字符 JWT |
| 资料库专辑 | `GET /me/library/albums?limit=12&include=catalog -> 200`，界面渲染出方大同、deca joins 等 |
| 资料库歌曲/歌单 | `GET /me/library/songs`、`/me/library/playlists -> 200` |
| 专辑详情 | `GET /catalog/cn/albums/220365864?include=tracks -> 200`，13 首带时长 |
| 试听播放 | 媒体会话建立，`buffered=30024`（30 秒试听） |

### 网易云

| 环节 | 证据 |
|---|---|
| 登录 | 昵称「Henry唔系豹」显示在提供商列表 |
| 歌单列表 | 界面显示 6 个歌单（我喜欢的音乐、paw、Lonely 等） |
| 歌单详情 | 「Henry唔系豹喜欢的音乐」显示 38 音轨 / 111 分钟及曲目 |
| 每日推荐 | 活动页显示「每日推荐」 |
| 播放地址 | `songUrl id=... code=200 fee=8 trial=false hasUrl=true` |

---

## 3. 当前未解决的问题

### 3.1 网易云播放不启动 —— **已解决**

**根因**：ExoPlayer 会为队列里**每一个**条目创建 MediaSource。歌单 38 首中有 1 首解析不到
播放地址，`playbackUri` 为 null，创建时抛 `NullPointerException`，导致整个队列一起报废，
表现为点任何一首都停在 STOPPED。

**修复**：歌单详情只把真正解析到地址的曲目放进列表，并记录「N 首中 M 首可播」。

**验证**：`playlist 12623225665: 38 tracks, 37 playable` →
`onSetMediaItems in=37 out=37` → `state=PLAYING position=12196 buffered=78602`；
暂停/恢复：`PAUSED 19000` → `PLAYING 22121` → `PLAYING 28163`（位置持续前进）。

### 3.1b 原始记录（保留以便回溯排查过程）

**现象**：点歌后播放器停在 `STOPPED(1)`、`position=0`、`error=null`，没有声音。

**已排除**：

- 队列数据正确：`onSetMediaItems in=38 out=38`，首项 `mediaId=netease://catalog/2/audio/...`。
- 播放地址完整：`localConfiguration.uri = http://m804.music.126.net/...mp3?authSecret=...`。
- 明文 HTTP 未被拦截：`network_security_config.xml` 已是 `cleartextTrafficPermitted="true"`。
- `playPlaylist` 只由点击触发，不是界面 collector 重复调用（已读 `PlaylistFragment` / `PlaylistViewModel`）。

**新线索**：

- `onSetMediaItems` 在 20 秒内被触发约 5 次，内容相同。
- `MediaSessionService` 日志里 `active item id` 在 `0` 与 `5` 之间跳变，说明队列被反复替换且起始位置在变。
- `state` 始终 `STOPPED`，`error=null`。

**下一步建议的排查顺序**：

1. 在 `TwelveViewModel.playAudio` 里加日志，打印 `mediaController.value == null` 与否、`setMediaItems` / `prepare` / `play` 各自是否被执行。
2. 检查 `PlaybackService.onCreate` 里新加的 `setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory()...))` 是否影响了 `prepare` 行为——这一改动未验证，可先回退对比。
3. 检查是否存在多个 MediaSession 实例（`dumpsys media_session` 曾显示 3 个 session），确认控制器连的是我们那一个。
4. 对比本地曲库播放是否同样停在 STOPPED：若本地正常，问题在在线数据源；若本地也异常，问题在播放服务装配。

### 3.2 Apple Music 全曲播放 —— **链路已打通到 DRM 会话，卡在 Widevine provisioning**

**已查明（真机实测，不再是猜测）**：`webPlayback` 响应里直接带有许可相关字段：

```
songList[0].hls-key-server-url = https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense
songList[0].widevine-cert-url  = https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/widevineCert
songList[0].hls-key-cert-url   = https://s.mzstatic.com/skdtool_2021_certbundle.bin
```

**请求格式已探明**（`scripts/probe_license.py` 实测）：

```json
POST acquireWebPlaybackLicense
Authorization: Bearer <dev token>
Media-User-Token: <user token>
Origin: https://music.apple.com

{ "challenge": "<base64 widevine challenge>",
  "key-system": "com.widevine.alpha",
  "uri": "<manifest 中 #EXT-X-KEY 的 data: URI 原样回传>",
  "adamId": "<catalog song id>",
  "isLibrary": false,
  "user-initiated": true }
```

服务器返回 `HTTP 200 {"errorCode":-42585,"status":-1021}` —— 格式被接受，只是 challenge 是伪造的。
缺少 `key-system` 时返回 500，说明该字段必需。

**剩余实现工作**：
1. 用 Android `MediaDrm` 生成真实的 Widevine challenge，实现 `MediaDrmCallback`，把上述 JSON
   发给 `hls-key-server-url`，取回 license。
2. 重写 HLS 清单：`METHOD=ISO-23001-7` → `SAMPLE-AES-CENC`，
   KEY 的 data URI 换成 `pssh{ systemId=Widevine, data=WidevineCencHeader{ AESCTR + KID } }`
   （裸 16 字节会被 -42585 拒绝）。
3. 通过 `ResolvingDataSource` 在取清单时完成重写，并把原始 KEY URI 传给回调。
4. 在 `PlaybackService` 装配该 DRM 会话管理器。

### 3.2b 原始记录（保留以便回溯）

**现象**：只能放 30 秒试听。

**根因**：全曲是 cbcs/ISO-23001-7 加密 HLS，必须换取 Widevine 许可。

**已实测**（真机，本账号）：`webPlayback` 返回 200 与 6 个资源
`[28:ctrp256, 30:cbcp256, 37:ibhp256, 34:cbcp64, 32:ctrp64, 38:ibhp64]`，
清单 `#EXT-X-KEY:METHOD=ISO-23001-7,URI="data:;base64,..."`。

**缺口**：Apple 的 Widevine 许可端点。Yuno 仓库只剩清单重写（`crates/yuno-drm-rewrite`），
许可请求客户端不在其中。需要浏览器抓包拿到端点与请求体。

**注意**：不要把它接进 `audio()` 的播放路径——试过，每次点歌发一次网络请求，表现为「再次播放没声音」，已回退。

### 3.3 TTML 逐字歌词

解析层与模型层完成（`TtmlParser`、`Lyrics.Line.words`），25 个单测覆盖。**未接入播放页渲染**。
若要接：`LyricsFragment` 需要改成按 `words` 做逐字高亮，`NowPlayingViewModel` 的行状态计算也需要按逐字推进。

### 3.4 其他

- `AppleMusicDataSource` 的 genre / playlist 详情 / 写操作返回 `NOT_IMPLEMENTED`。
- `NetEaseDataSource` 的 artists / genres / 专辑详情未实现。
- 资料库「播放列表」页曾为空，已修 `ids()` 的 `p.` 前缀识别，但**未在真机上复验**。

---

## 4. 关键设计约束（改代码前必读）

1. **三种凭据不可互换**：developer token 证明应用身份，music user token 证明用户身份，
   storefront 决定目录地区。代码里刻意分开存放。
2. **三种 ID 不可混用**：`l.` 专辑、`i.` 歌曲、`p.` 歌单都是库内 ID，不能当目录 ID 用
   （见 `String.isLibraryScoped()`）。
3. **在线数据源不要在 `stateIn` 里缓存 provider→instance 映射**：登录会改变数据库行，
   快照会变空，导致所有请求返回 `AUTHENTICATION_REQUIRED`。两个数据源都已改为按需解析。
4. **在线 URL 会过期**：`applemusic://` 条目在播放时重新解析；`netease://` 走批量解析，
   避免队列逐首发请求。
5. **不要静默降级**：试听片段、权益受限必须在语义上区分，不能当成整首歌。

---

## 5. 文件索引

```
app-src/app/src/main/java/org/lineageos/twelve/
├── datasources/
│   ├── AppleMusicDataSource.kt        Apple Music 数据源（按需解析实例）
│   ├── applemusic/
│   │   ├── AppleMusicClient.kt        REST 客户端（搜索/专辑/艺人/资料库/账户）
│   │   ├── AppleMusicModels.kt        模型、ID 解析、URI 方案
│   │   ├── AppleMusicTokenProvider.kt 开发者令牌抓取与缓存
│   │   ├── AppleMusicPlayback.kt      webPlayback 授权与加密探测
│   │   ├── AppleMusicHttp.kt          共享 OkHttp（Origin 头必需）
│   │   └── TtmlParser.kt             TTML 逐字歌词解析
│   ├── NetEaseDataSource.kt           网易云数据源
│   └── netease/
│       ├── NetEaseClient.kt           eapi 客户端、账号、歌单、URL 解析
│       ├── NetEaseCrypto.kt           eapi 加密
│       └── NetEaseModels.kt           歌单/专辑/曲目模型
├── WebLoginActivity.kt                内置浏览器登录（两家共用）
├── services/MediaRepositoryTree.kt    在线条目播放时重新解析
└── services/PlaybackService.kt        媒体会话（含诊断日志）
```

---

## 6. 已知的工程陷阱

- **PowerShell 的 `Get-Content` / `Set-Content` 默认按 GBK 解码 UTF-8**，往返一次就会把
  `…`、`×` 等字符毁掉，甚至吃掉 `<` 让 XML 不合法，还会写入 BOM 导致 TOML 解析失败。
  一律加 `-Encoding utf8`，或直接用编辑器工具。详见 `docs/WINDOWS_ENCODING.md`。
- `android.net.Uri` 在纯 JVM 单测里是桩实现，涉及它的测试要移除或用 Robolectric。
- Kotlin 块注释可嵌套：注释里写 `/v1/me/*` 会开启一个嵌套注释，导致「Unclosed comment」。

#### 当前进度（真机）

已完成并验证：
- 清单重写生效：`AppleMusicManifest: Rewrote manifest in place: https://aod-ssl.itunes.apple.com/...m3u8`
- DRM 会话建立：Widevine CDM 被调用，日志出现 `cdm_engine.cpp ... level = L1`
- 许可端点与请求格式确定（见上）

**当前阻塞**：
```
DefaultDrmSession: DRM session error
  java.lang.IllegalArgumentException: Failed to handle provision response: BAD_VALUE
```
Widevine 该设备需要 **provisioning**（`MediaDrm.getProvisionRequest`），而 Apple 不提供
provisioning 服务，只提供 `widevine-cert-url`。当前实现返回空响应，CDM 判为 BAD_VALUE。

**下一步可选方案**：
1. 用标准 Widevine provisioning 服务完成一次 provision（设备 L1，通常可成功），
   再走 Apple 的许可端点。
2. 检查是否可通过 `MediaDrm` 属性跳过 provisioning（不同 OEM 行为不一致）。
3. 参考浏览器行为：Chrome 会先用内置 provisioning，再请求 Apple 许可。

注意：**不要**再把清单重定向到 `data:` URI —— 分片是相对路径，改 base 会导致
`Source error`。必须用 `ManifestRewritingDataSource` 原地改写、保留原始 URL（已实现）。