# 第三方组件声明（THIRD PARTY NOTICES）

本应用基于以下第三方组件构建，感谢原作者。各组件版权归其作者所有。

## 引擎库：yt-dlp-android（本项目 fork）

- License: MIT（Copyright (c) 2026 LucQuebec；上游 yausername/youtubedl-android 同为 MIT 系）
- 项目内改动：per-run 取消注册表、CLI 旗标白名单扩展（--flat-playlist / --dump-single-json 等）、
  native lib 目录注入、stdout→日志桥接。改动随 fork 仓库以同协议开源。
- 上游: https://github.com/yausername/youtubedl-android

## yt-dlp

- License: The Unlicense（公有领域）
- 说明: 由上述引擎库内嵌运行（Chaquopy + Python 运行时），本仓库不分发其副本。
- 项目: https://github.com/yt-dlp/yt-dlp

## FFmpeg

- License: LGPL-2.1+（FFmpeg 核心）、LGPL-2.0（libmp3lame）；静态自建 8.1.2，
  组件=核心 + libmp3lame；未启用 GPL 组件（configure 无 --enable-gpl）。
- 说明: 以独立可执行文件（命令行进程）方式调用，不链接进应用进程；
  构建配方见本仓库 `third_party/ffmpeg/build.sh`（完整可复现）。
- 项目: https://ffmpeg.org — 源码获取途径即上句配方对应上游 https://git.ffmpeg.org/ffmpeg.git

## 字体：fusion-pixel（缝合像素字体）

- License: SIL Open Font License 1.1
- 作者: TakWolf — https://github.com/TakWolf/fusion-pixel-font

## Android / Kotlin 生态库

- Jetpack Compose、AndroidX DataStore、kotlinx-serialization-json、OkHttp、
  AndroidX Lifecycle / Test：Apache License 2.0（AndroidX / JetBrains 各自项目）。

---

以上各组件之许可证全文，随各组件上游项目分发；如需索取随附副本，请提 Issue。
