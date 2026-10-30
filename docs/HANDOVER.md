---

## 0. 速览（最新状态）

| 功能 | 状态 |
|---|---|
| 工程独立构建 / 与系统版 Twelve 共存 | ✅ `assembleDebug`，52 个单测，34 MB APK |
| 本地音乐播放 / 暂停重播 | ✅ 真机验证 |
| 网易云登录 / 歌单 / 专辑 / 每日推荐 / 歌单详情 | ✅ 真机验证 |
| 网易云播放 / 暂停重播 | ✅ 真机验证 |
| Apple Music 登录 / 开发者令牌 | ✅ 自动从网页播放器抓取 |
| Apple Music 资料库 / 专辑详情 / 播放列表详情 | ✅ 真机验证 |
| Apple Music 库内搜索（专辑/歌曲/艺术家） | ✅ 真机验证（`/me/library/search` 返回 200） |
| Apple Music 创建播放列表 | 🟡 已实现，未真机验证 |
| Apple Music 歌词（TTML 逐字） | ✅ 接口与解析；界面按需求已撤除 |
| **Apple Music 全曲播放** | ✅ **真机验证（突破 30 秒试听，播完整首）** |
| Apple Music 暂停 / 恢复 / 切歌 | ✅ 真机验证 |
| Apple Music 播放列表「点歌起播」 | ✅ 真机验证（懒解析 + 双向预解析） |

### 全曲播放是怎么打通的（重要，勿回退）

三个真实缺陷，缺一不可：

1. **`ProvisionRequest.data` 已经是 URL-safe base64 文本**，不是二进制。
   再 base64 一次会让 Google 证书服务返回无提示的 400。
   必须转成标准 base64（`-`→`+`, `_`→`/`，补齐 padding）放进 JSON 的 `signed_request`。
2. **许可交换要用「解析后的」开发者令牌**，不是配置里那个（通常为空），
   否则报 `Missing developer token`。
3. 证书服务优先用 CDM 自带的 `defaultUrl`（Google），Apple 的 `widevine-cert-url` 作回退。

代码位置：`datasources/applemusic/drm/`（`WidevineProvisioning`、`WidevinePssh`、
`HlsManifestRewriter`、`WidevineLicenseRequest`、`ManifestRewritingDataSource`、`AppleMusicDrm`）。

### 唯一未完成项：Apple Music 播放列表点歌起播

已确证的事实链：

```
PawAMPlaylist: row clicked: position=0          ← 点击触发
PawAMPlaylist: playPlaylist(0): state=Success   ← 列表数据 82 首、playbackUri 非空
PawAMPlayback: onSetMediaItems received 82 item(s), startIndex=-1   ← 队列到达会话
（缺失）PawAMPlayback: resolveMediaItems took ...ms
```

即：请求到了会话，但**队列解析阶段**没有走完。已做三层加固：

1. 解析改为并发（`Semaphore(6)` 限流）；
2. 单曲解析失败只保留原条目，不影响整批；
3. `onSetMediaItems` 外层兜底：整批失败则退回未解析队列；
4. 播放出错时自动跳到下一首。

**下一步只需一条日志定性**：跑一次播放列表起播，看是否有
`resolveMediaItems took <ms>ms`；若有则解析正常、问题在后面，若无则解析卡住或抛错。

### 已知的重要教训（避免重复踩坑）

1. 播放器数据源必须用 `DefaultDataSource`，不能用 `DefaultHttpDataSource`——
   后者无法打开 `content://`，会让**本地播放一起失效**。
2. ExoPlayer 会为队列中每个条目创建 MediaSource，任何一个 URI 为 null 都会让整个队列无法播放。
3. 不要把 HLS 清单重定向到 `data:` URI：分片是相对路径，base 一变就 `Source error`。
4. `Audio.toMedia3MediaItem` 里 `isPlayable = playbackUri != null`：
   `playbackUri` 为 null 的曲目会被标记为不可播放，点击毫无反应。
5. **不要用 `dumpsys media_session | grep | head -1` 判断播放状态**：
   系统里有多个会话，会抓到别的应用。可靠指标是日志
   `MediaSessionService: onSessionPlaybackStateChanged: record=dev.pawam.twelve.dev/...`。
6. adb 反复按返回键会把 App 退回桌面，之后所有点击都落在桌面，产生假信号。
   驱动脚本必须先确认 `topResumedActivity` 仍是本应用。
7. 点击要取 `clickable="true"` 节点的 bounds，而不是文本节点的 bounds。
8. 设备断连时 adb 命令可能挂起数小时，跑设备命令要带超时。

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

#### 根因确认（第 21 轮）

provisioning 请求发往 `https://www.googleapis.com/certificateprovisioning/v1/devicecertificates/create`，
但该域名在当前网络**不可达**：

```
curl --max-time 20 https://www.googleapis.com/...   -> 000（连接失败）
adb shell ping -c 2 www.googleapis.com              -> 100% packet loss
```

因此表现为：DRM 会话停在 `BUFFERING`，日志只有 `Provisioning via ...`，之后既无成功也无失败。

**可行路径**：在手机上开启 VPN 后播放一次，让 CDM 完成一次 provisioning（证书会被缓存），
之后再验证是否无需 VPN 也能播放。这是外部网络条件问题，不是代码缺陷。

#### provisioning 尝试记录（第 22 轮）

重要发现：**Apple 自己提供 provisioning 端点**，不需要 Google。

```
POST https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/widevineCert
  Authorization: Bearer <dev token>, Media-User-Token, Origin
  body: provision request（protobuf）
-> HTTP 200, 705 字节 protobuf（0A BB 02 08 03 12 10 ...）
```

已把 `executeProvisionRequest` 从 Google 地址改为该地址（走 `widevine-cert-url`），
不再依赖可能被墙的 `www.googleapis.com`。

CDM 对响应格式的要求（按尝试顺序记录）：

| 返回内容 | CDM 报错 |
|---|---|
| 空响应 | `Failed to handle provision response: BAD_VALUE` |
| 裸 protobuf（705B） | `Provisioning response message is an invalid JSON/base64 string` |
| 整个 protobuf 的 JSON 信封（962B） | `ExtractAndDecodeSignedMessage: Cannot locate start substring` |
| 内层 field 1 + JSON 信封 | 同上 |
| 内层 field 1 + OMA XML 信封（540B） | 同上 |

结论：CDM 在响应里寻找某个特定起始标记，尚未命中。下一步应打印 CDM 期望的格式
（Widevine `certificate_provisioning.cpp` 的 `ExtractAndDecodeSignedMessage`），
或对比 Google 官方 provisioning 服务返回的响应结构（VPN 下可抓到）。

#### 当前产品行为（第 23 轮）

`AppleMusicDataSource.FULL_TRACK_PLAYBACK_ENABLED` 默认 **false**。

原因：provisioning 未解决时，把加密流交给播放器会让每一首歌以 `Source error` 失败；
关闭后回退到 30 秒试听，用户至少能听到声音。等 provisioning 解决后再打开该开关。

已尝试仍失败的响应格式（供后续排查，勿重复）：
裸 protobuf、整体 protobuf 的 JSON 信封、内层 field1 + JSON、
内层 field1 + OMA XML、内层 field1 + URL-safe base64 JSON。
CDM 始终报 `ExtractAndDecodeSignedMessage: Cannot locate start substring`。

**下一步最有效的一步**：在已翻墙的设备上抓一次 Google 官方 provisioning 服务的
真实响应体（`POST www.googleapis.com/certificateprovisioning/v1/devicecertificates/create`
配合 MediaDrm 的 provision request），照它的结构构造 Apple 端点的响应。

#### provisioning 定位结论（第 27 轮，一次性尝试五种编码）

`ProvisioningFormats.tryAll` 在同一次请求里依次尝试并记录结果：

| 编码 | 结果 |
|---|---|
| json + base64url | 400 `{"error":{"message":"Invalid Value"}}` |
| json + base64 | 400 `Invalid Value` |
| form + base64url | 400 `Invalid Value` |
| form + base64 | 400 `Invalid Value` |
| raw protobuf | 400 `Invalid JSON payload received. Unexpected token` |

**结论**：JSON 编码是正确的（不再报格式错误），但 Google 判 `Invalid Value`——
说明 CDM 提供的 provision request 与内嵌 API key 绑定官方应用签名，第三方 App 无法使用。
**Google 这条 provisioning 路径对第三方客户端不可用。**

因此只剩 Apple 的 `widevine-cert-url`。它的响应是 protobuf，而 CDM 解析该响应时
报 `ExtractAndDecodeSignedMessage: Cannot locate start substring`——
需要确认 CDM 期望的确切响应封装（JSON envelope 里的字段名与 base64 编码方式）。

### 播放列表起播与切歌（已解决，勿回退）

- **懒解析**：`resolveMediaItems(items, startIndex)` 只解析即将播放的那一首，
  其余保留原样。全量解析 100 首会让起播前阻塞数十秒。
- **双向预解析**：起播后后台以当前曲目为中心，前后各 `RESOLVE_LOOKAHEAD = 3` 首
  依次解析并 `replaceMediaItem`。只向前会导致往回切重新解析、卡顿。
- **跳过当前项**：`replaceMediaItem` 目标等于 `currentMediaItemIndex` 时跳过，
  否则正在播放的曲目会被重启。
- **按需兜底**：`onMediaItemTransition` 里若目标项仍未解析，解析后再替换并恢复播放。
- **加载反馈**：`onSetMediaItems` 一开始就置 `playWhenReady`，使 UI 立即进入 BUFFERING。
- **不要丢弃解析失败的条目**：丢弃会让播放器连续跳歌（「疯狂切歌不播放」）。

### 库内搜索

- `AppleMusicClient.librarySearch` 走 `/me/library/search?types=library-albums,library-songs,library-artists`。
- 切换前用的是目录搜索 `/catalog/<sf>/search`，会返回商店结果而非用户库内内容。

### 点击曲目从选中项起播（易错点，勿回退）

`Player.setMediaItems` 有两个容易混淆的重载，官方文档明确：

```java
// 布尔参数是 resetPosition：重置到「第一个 Window」的默认位置
void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition);

// 这个才是「从第几首开始」
void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs);
```

上游 Twelve 的 `TwelveViewModel.playAudio` 写的是
`setMediaItems(items, true)` + `seekToDefaultPosition(position)`，
后者设置的是「当前这首（第 0 首）的播放位置」，并非选择曲目，
于是点击任意行都从第一首开始播。

已改为 `setMediaItems(items, startIndex, 0L)` 并做越界收敛。
**本地音乐与网易云同样受此问题影响**，不只 Apple Music。

参考：https://raw.githubusercontent.com/androidx/media/main/libraries/common/src/main/java/androidx/media3/common/Player.java