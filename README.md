<!--
     SPDX-FileCopyrightText: 2026 The PawAM Project
     SPDX-License-Identifier: Apache-2.0
-->
# PawAM

一个基于 [LineageOS Twelve](https://github.com/LineageOS/android_packages_apps_Twelve) 的
第三方音乐播放器，在保留原始 Material 3 Expressive 界面与系统媒体会话的前提下，
接入 Apple Music 与网易云音乐的在线曲库。

> **当前版本：0.1.0-alpha**
> 这是早期的 alpha 预发布版本，功能仍在完善中，Apple Music 的播放链路存在已知问题，
> 详见下方「已知问题」。

## 功能

| 功能 | 状态 |
|---|---|
| 本地音乐播放 / 暂停重播 | ✅ |
| 保留 Twelve 的 M3E 界面与系统媒体会话 | ✅ |
| 导航栏切换提供商（本地 / Apple Music / 网易云） | ✅ |
| 内置浏览器登录 | ✅ |
| **Apple Music** | |
| 登录与开发者令牌自动获取 | ✅ |
| 资料库、专辑详情、播放列表详情 | ✅ |
| 库内搜索（专辑 / 歌曲 / 艺术家） | ✅ |
| **全曲播放（Widevine）** | ✅ |
| 切歌、暂停恢复 | ✅ |
| 点击指定曲目起播 | ⚠️ 见已知问题 |
| 创建播放列表 | 🟡 已实现，未充分验证 |
| **网易云音乐** | |
| 登录、歌单、专辑、每日推荐 | ✅ |
| 播放、暂停重播 | ✅ |
| **缓存** | |
| 音频缓存（LRU，上限 5 GB） | ✅ |
| 库元数据缓存 | ✅ |

## 安装

1. 下载 Release 中的 `app-debug.apk`
2. 允许「安装未知来源应用」后安装
3. 打开应用，在导航栏点击「切换提供商」选择 Apple Music 或网易云，按提示登录

与系统自带的 LineageOS 音乐可以共存（包名不同：`dev.pawam.twelve`）。

**最低要求**：Android 11（API 30）

## 已知问题（0.1.0-alpha）

- **Apple Music 播放列表点击指定曲目起播可能失效**：起始索引已确认能正确传给播放服务，
  但部分情况下队列安装后会被重置回第一首。这是当前最优先修复的问题。
- 播放列表切歌在跨越大量曲目时可能表现不稳定。
- 部分区域受限曲目无法播放（Apple Music 返回 404）。
- 播放列表重命名、删除、增删曲目尚未实现。
- 首次播放 Apple Music 全曲时需要网络能访问 Google 的 Widevine 证书服务
  （部分网络环境下不可达）。

## 工作原理

PawAM 复用 Twelve 的数据源抽象（`MediaDataSource`），新增两个实现：

- `AppleMusicDataSource`：通过 Apple Music API 读取曲库；播放走 `webPlayback` 取得
  HLS 流地址，再用 Widevine 解密。开发者令牌从 Apple 网页播放器自动抓取。
- `NetEaseDataSource`：使用 eapi 协议访问网易云。

Apple Music 的播放链路会额外处理 DRM：

1. 用 `webPlayback` 换取带有 `hls-key-server-url` 的授权
2. 把清单里的 `ISO-23001-7` 改写为 `SAMPLE-AES-CENC` + Widevine `pssh`
3. 用 `MediaDrm` 完成 provisioning，再向 Apple 换取许可

更多实现细节见 [`docs/HANDOVER.md`](docs/HANDOVER.md)。

## 构建

```bash
cd app-src
./gradlew assembleDebug
```

需要 JDK 17+ 与 Android SDK 36。产物位于 `app-src/app/build/outputs/apk/debug/`。

## 许可证

本项目遵循 Apache License 2.0，详见 [`LICENSES/Apache-2.0.txt`](app-src/LICENSES/Apache-2.0.txt)。

基于 LineageOS Twelve，其版权归 The LineageOS Project 所有。

## 免责声明

本项目为第三方客户端，与 Apple Inc.、网易公司均无关联。
不提供任何媒体内容，所有内容来自用户自己订阅的账号。
请自行确认使用方式符合相应服务的条款。

**Widevine 与 DRM 说明**：本项目未实现任何 DRM 绕过，使用的是 Android 系统提供的
标准 Widevine 接口，与官方客户端一致。
