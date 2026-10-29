# PawAM 架构分析与实施计划

> 本文是 PawAM 的架构决策记录，同时如实标注每一部分的**实际完成状态**。
> 计划 ≠ 已实现；"已实现"一栏只写在代码里存在且能被构建/运行验证的东西。

最后更新：2026-10-30

---

## 1. 仓库与许可证审查

### 1.1 主项目：LineageOS Twelve

| 项目 | 结论 |
|---|---|
| 仓库 | `LineageOS/android_packages_apps_Twelve`（**不是** `android_packages_apps_Music`） |
| 分支 | `lineage-24.0` |
| 语言 | Kotlin |
| 许可证 | **Apache-2.0**（REUSE 合规，附带 `LICENSES/Apache-2.0.txt`） |
| 可派生性 | ✅ 可自由派生、修改、闭源分发（需保留 NOTICE/版权声明） |

> ⚠️ 用户最初提供的链接 `LineageOS/android_packages_apps_Music` 指向的是 2009 年的
> AOSP 遗留实现（`com.android.music`，`minSdk 8`，纯 Java，无 M3E、无 Media3 会话）。
> 真正的 Twelve 是 `android_packages_apps_Twelve`。本项目基于后者。

**技术栈（实测自 `gradle/libs.versions.toml`）**

| | |
|---|---|
| AGP | 9.0.0 |
| Kotlin | 2.2.10 |
| Gradle | 9.3.1 |
| compileSdk / targetSdk | 36 |
| minSdk | 30 |
| UI | Material 3 Expressive（`Theme.Material3Expressive.DayNight.NoActionBar`）+ 动态配色 |
| 播放 | Media3 1.11.1（ExoPlayer + `MediaLibraryService`） |
| 存储 | Room 2.8.0 |
| 其他 | Coil 3、OkHttp 4.12、Navigation、Preference |

### 1.2 参考项目一：Yuno（Apple Music）

| 项目 | 结论 |
|---|---|
| 仓库 | `wsxlma/yuno-music` |
| 许可证 | **无任何许可证文件**；GitHub API 返回 `license: None`；Cargo workspace 写 `license = "UNLICENSED"` |
| 可复制性 | ❌ **不能复制代码**。默认版权 = 保留所有权利 |
| 可用方式 | ✅ 仅作**协议与机制的知识参考**（端点含义、接口形态） |

### 1.3 参考项目二：Ncrust（网易云）

| 项目 | 结论 |
|---|---|
| 仓库 | `GuitaristRin/Ncrust` |
| 许可证 | **MIT** |
| 可复制性 | ✅ 可复用（需保留版权声明） |
| 价值 | eapi 协议说明、7 级音质阶梯、权益（VIP/试听）判定规则 |

### 1.4 Apple 官方接入方式（**关键结论**）

Apple 给第三方的官方方案是 **MusicKit JS**，不是抓 Cookie。

```js
MusicKit.configure({ developerToken: app_token, app: { name, build } })
const token = await music.authorize()   // 官方授权 → musicUserToken
music.isAuthorized / music.unauthorize()
```

依据：[User Authentication for MusicKit](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit)、
参考实现 [music-assistant `musickit_wrapper.html`](https://github.com/music-assistant/server/blob/dev/music_assistant/providers/apple_music/musickit_auth/musickit_wrapper.html)。

| | Yuno 做法 | MusicKit（PawAM 采用） |
|---|---|---|
| 拿用户令牌 | `CookieManager.getCookie()` 偷 `media-user-token` | `music.authorize()` |
| 合规性 | 绕过授权流程，用户无法撤销 | Apple 文档化流程 |
| 稳定性 | 网页改版即失效 | 版本化 CDN `musickit/v3` |
| 结论 | 不采用 | ✅ 采用 |

---

## 2. 架构分析：Twelve 的扩展点

Twelve 的架构对新增在线曲库非常友好，**几乎不需要改动 UI 层**。

### 2.1 现成的接缝

```
MediaDataSource (interface)  ← 所有数据源统一契约
       ↑
ProvidersManager<T>          ← 按 provider 实例分发
       ↑
MediaRepository              ← 按 URI 路由到能处理它的数据源
       ↑
ViewModels / Fragments       ← 完全不动
```

**关键发现：`Audio` 模型已经有 `playbackUri: Uri?` 字段。**

```kotlin
data class Audio(
    val uri: Uri,              // 逻辑标识（本项目用自定义 scheme）
    val playbackUri: Uri?,     // ← 在线播放的钩子，现成的
    val mimeType: String?,
    ...
)
```

这意味着**在线曲库不需要新模型**，只要 `ProviderType` 多一个枚举值 + `MediaDataSource` 多一个实现。

### 2.2 URI 路由机制

`MediaRepository` 通过 `mediaTypeOf(uri) != null` 判断某数据源能否处理某个 URI。
因此 Apple Music 用独立 scheme `applemusic://catalog/<providerId>/<kind>/<storefront>/<id>`，
天然与 MediaStore（`content://media/...`）互不冲突，**本地与在线曲库可以共存**。

`providerId` 放在路径里是刻意的：用户可以配置多个 Apple Music 账号（不同 storefront），
URI 里不带 providerId 就只能"猜"归属。

### 2.3 需要扩展的部分

| 部分 | 现状 | 需要做什么 |
|---|---|---|
| `Lyrics` 模型 | **仅行级**：`Line(text, durationMs: LongRange?)` | 逐字歌词需扩展（`word` 级时间轴） |
| 播放授权 | `playbackUri` 直接交给 ExoPlayer | Apple 全曲需 Widevine DRM 会话 |

---

## 3. 数据模型设计：元数据 / 授权 / 音频流分离

这是 Apple Music 接入最容易搞错的地方，PawAM 显式建模。

### 3.1 三种凭据，互不替代

| 凭据 | 证明什么 | 获取方式 | 能做什么 |
|---|---|---|---|
| **Developer Token**（JWT） | *应用*有权调 API | 用户自己的 Apple Developer 账号 | 读目录（搜索/专辑/艺人） |
| **Music User Token** | *哪个用户*在请求 | MusicKit `authorize()` | 读该用户资料库 |
| **Storefront** | 哪个地区目录 | 账号地区 | 拼进 URL 路径（不是 header） |

> 开发者令牌**不能**访问用户资料库；用户令牌脱离开发者令牌**毫无用处**。
> 代码中 `AppleMusicCredentials` 把三者分开存放，不合并成"一个 token"。

### 3.2 三种 ID，不能混用

Apple 的资料库资源返回 `l.*` / `i.*` 前缀的**库内 ID**，它**不能**用来查目录元数据或换取播放授权。
必须先经 `include=catalog` relationship 换成**目录 ID**（Adam ID）。

```kotlin
data class AppleMusicIds(
    val catalogId: String?,   // 可播放、可查目录
    val libraryId: String?,   // 仅库内标识
) { val isPlayable get() = !catalogId.isNullOrBlank() }
```

### 3.3 音频流的现实

| 层级 | 内容 | PawAM 状态 |
|---|---|---|
| 元数据 | JSON（歌曲/专辑/艺人） | ✅ 已实现 |
| 播放授权 | Widevine license（全曲） | ❌ **未实现** |
| 音频流 | HLS（全曲，cbcs/CENC 加密） | ❌ 未实现 |
| 音频流 | **未加密 m4a 试听片段** | ✅ 已实现 |

全曲 HLS 是 `SAMPLE-AES`/`cbcs` 加密的，必须接 DRM 会话换取 license 才能解。
**因此当前 `playbackUri` 指向 30 秒试听**，界面必须如实标注，不得假装全曲。

---

## 4. 界面设计：模式切换

按需求实现"双生态"而非"合并列表"——两者的账号、ID、授权、服务条款都不同。

```
默认 Apple Music 模式
   导航栏: [活动] [搜索] [库] [网易云]   ← 点它切到网易云
        ↓ 点击
   网易云模式
   导航栏: [活动] [搜索] [库] [Apple Music]  ← 点它切回
```

标签显示的是**点击后会切换到的模式**（动作语义），不是当前状态。
界面骨架、M3E 风格、动态配色、播放页、队列、媒体会话**全部沿用 Twelve 原样**。

---

## 5. 分阶段任务与状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 仓库/许可证审查、基线可构建验证 | ✅ **完成** |
| P1 | 工程独立化（去 AOSP 插件、改包名、可装真机） | ✅ **完成** |
| P2 | 统一数据模型 + Apple Music 数据源骨架 | ✅ 已构建并安装验证 |
| P3 | MusicKit 登录（WebView + JS 桥） | ✅ 已构建；未做真实账号登录验证 |
| P4 | 搜索 / 专辑详情 / 资料库接入 UI | 🟡 数据源已接，未配置账号故未端到端验证 |
| P5 | 播放（试听）+ 队列 + 媒体会话 | ✅ 试听 URI 已接；未实际播放验证 |
| P6 | TTML 逐字歌词 | ⬜ 未开始 |
| P7 | 全曲 DRM 播放 | ⬜ 未开始（高风险） |
| P8 | 网易云数据源 | 🟡 仅 eapi 协议层，未接数据源与 UI |

### 已验证的事实（有构建/设备证据）

1. 上游 Twelve 剔除 `generatebp` 后 `assembleDebug` **成功**（32.5 MB APK）。
2. PawAM `compileDebugKotlin` 与 `assembleDebug` **通过**。
3. PawAM APK（33.2 MB）安装到小米 13（`dev.pawam.twelve.dev`），与系统版 Twelve **共存**。
4. 冷启动**无崩溃**，M3E 界面、动态配色、底部导航渲染正常。
5. 导航栏第 4 项为模式切换；实测 `content_mode` 在 `APPLE_MUSIC` / `NETEASE` 间往返，
   标签同步在「网易云」/「Apple Music」间切换。

### 未验证的部分

- **未配置 Apple Music 账号**，故搜索/专辑/资料库的真实请求链路未端到端跑通。
- 试听播放未实际点播验证。
- MusicKit 登录页未用真实 Apple 账号验证。

---

## 6. 风险清单

| 风险 | 等级 | 说明与对策 |
|---|---|---|
| **全曲播放需 DRM** | 🔴 高 | Widevine license 获取属于 Apple 私有协议；不实现就只能试听。对策：先做试听并如实标注 |
| **Developer Token 门槛** | 🔴 高 | 普通用户没有 Apple Developer 账号就拿不到 JWT，App 直接不可用 |
| **Apple 服务条款** | 🟠 中 | 非官方客户端访问自有订阅内容，存在账号风险；须在 UI 明示 |
| **Yuno 无许可证** | 🟠 中 | 不得复制其代码；只能参考机制 |
| **API 无版本保证** | 🟠 中 | Apple 可随时改私有端点；需防御式解析（字段缺失不崩溃） |
| **逐字歌词需改模型** | 🟡 低 | Twelve 的 `Lyrics` 只有行级，扩展会触及渲染层 |
| **Room 迁移** | 🟡 低 | v10→v11 只是新增表，自动迁移可行 |

---

## 7. 与原始需求的偏差（如实记录）

| 原始要求 | 实际 |
|---|---|
| "以 LineageOS Music 为主项目" | 是，但用的是正确的 `Twelve` 仓库 |
| "参考 Yuno 的鉴权" | **未采用**其 Cookie 抓取；改用官方 MusicKit |
| "参考 Yuno 的 TTML 逐字歌词" | 未开始 |
| "网易云接入" | 协议层半成品，未接 UI |
| "每阶段构建并提交" | P0/P1 已提交；P2–P5 待编译后提交 |
