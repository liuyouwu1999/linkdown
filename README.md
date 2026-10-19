中文 | [English](README.en.md)

# 链接小卖部 linkdown

Android 本地视频/音频下载工具。粘贴分享链接，勾选要什么（视频 / 仅音频 / 封面），队列自动下载到系统媒体库。像素复古界面，本地使用，数据全部保存在设备本地。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![Min API](https://img.shields.io/badge/Android-10%2B%20(API%2029)-green)

[下载安装](#下载安装) · [功能](#功能) · [界面预览](#界面预览) · [从源码构建](#从源码构建) · [参与贡献](#参与贡献) · [许可证](#许可证)

## 下载安装

前往 [Releases](https://github.com/liuyouwu1999/linkdown/releases) 页面下载最新 APK（arm64-v8a，近年的手机和平板都适用）。系统要求 Android 10（API 29）及以上。

每个 Release 附有 SHA-256 校验和，下载后可以比对。

## 功能

下单：

- 系统分享卡片直达：在任何 App 里点「分享」选「链接小卖部」，自动识别链接
- 弹窗勾选要什么：视频 mp4 / 仅音频 mp3·m4a / 封面 jpg，任意组合，选完才开始
- 主界面也能粘贴链接下单，B 站短链（b23.tv）等常见分享形态自动展开

队列：

- 前台服务下载队列：并发数可调（2/3/4）、断网自动转「等待网络」并在恢复后续跑
- 杀进程不丢任务：重启应用自动恢复队列继续下载
- 已下载过的内容自动判重跳过，不会重复占空间
- 失败一键重试；已完成的部分不重下

媒体库：

- 产物按平台归档到系统下载目录 `Download/链接下载/<平台>/`，相册和文件管理器直接可见
- 应用内「货架」页：播放、删除、按平台浏览
- 音频可选转码 mp3 / m4a，自动写入标签和封面

批量：

- 支持合集类链接（B 站 UP 主合集、系列、番剧等）：先枚举出「标题 · N 条」，确认后整单下载
- 父子任务聚合展示：一个合集一张卡，进度一目了然

引擎：

- 双引擎架构：[yt-dlp-android](https://github.com/liuyouwu1999/linkdown-ytdlp-android)（yt-dlp 支持的站点列表见[上游文档](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md)）+ 纯 Kotlin 自研引擎（抖音、汽水音乐）
- 精确取消：单条任务可独立取消，不互相干扰

本应用不接入统计、广告或上报 SDK，只将你粘贴的链接提交到对应平台获取内容，其余数据全部保存在设备本地。「检查更新」功能仅在手动触发或启动定期检查（24 小时一次，可在设置关闭）时连接 GitHub 或其镜像，且只获取版本信息与更新包。应用内更新通过系统安装器完成，首次安装更新时需按系统提示授予「安装未知应用」权限。

## 界面预览

| 主界面 | 下单勾选 | 用户协议 |
|:---:|:---:|:---:|
| ![主界面](screenshots/main.jpg) | ![下单勾选](screenshots/dialog.jpg) | ![用户协议](screenshots/eula.jpg) |

| 下载队列 | 货架页 | 电池引导 |
|:---:|:---:|:---:|
| ![下载队列](screenshots/queue.jpg) | ![货架页](screenshots/shelf.jpg) | ![电池引导](screenshots/battery.jpg) |

货架页支持两种展示模式：列表和「书架」（木质搁板上按封面陈列，点击播放 / 长按下架）：

| 书架模式 |
|:---:|
| ![书架模式](screenshots/shelf_board.jpg) |

## 从源码构建

需要 JDK 21、Android SDK（compileSdk 37），最低支持 Android 10。

```bash
# 1. 先构建引擎库并发布到本地 Maven
git clone https://github.com/liuyouwu1999/linkdown-ytdlp-android.git
cd linkdown-ytdlp-android && ./gradlew publishToMavenLocal   # 仓库根执行（gradlew 在 fork 根，不在 library/ 下）
cd ../..

# 2. 构建主应用
git clone https://github.com/liuyouwu1999/linkdown.git
cd linkdown
# Android Studio 打开自动生成 local.properties，或者命令行：
./gradlew :app:assembleDebug
```

（可选）release 签名：在 `local.properties`（已 gitignore）配置 `release.keystore.path` /
`release.keystore.password` / `release.keystore.alias`；未配置时产出 unsigned APK。

## 参与贡献

欢迎提 Issue 反馈 bug 和功能建议。PR 请先开 Issue 讨论方案，提交信息建议遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)。

## 完全免费，禁止倒卖

链接小卖部完全免费，官方发布渠道只有本仓库和仓库的 [Releases](https://github.com/liuyouwu1999/linkdown/releases) 页面。

任何应用商店、网站或个人以付费形式销售、捆绑或变相收费分发本软件，都是冒用开发者名义的诈骗，与开发者无关。请不要付费下载；遇到了欢迎向发布渠道举报，或者提 [Issue](https://github.com/liuyouwu1999/linkdown/issues) 告知。

## 免责声明

本软件按「现状」提供，不附带任何明示或默示的担保。在法律允许的最大范围内，开发者不对您因使用或无法使用本软件造成的任何直接或间接损失承担责任。

本软件只提供链接解析与文件下载能力，不提供、不存储、不推荐任何内容。下载内容的来源和合法性由您自行负责：下载前请确保您拥有相应内容的合法访问与使用权利，并遵守内容来源平台的服务条款和所在地区的法律法规。首次启动应用时同样会显示本协议。

## 第三方组件

| 组件 | 用途 | 许可证 |
|---|---|---|
| [yt-dlp-android](https://github.com/liuyouwu1999/linkdown-ytdlp-android)（本项目 fork，上游 [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android)） | yt-dlp 引擎封装 | MIT |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | 多平台解析（引擎库内嵌运行） | The Unlicense |
| [FFmpeg](https://ffmpeg.org)（静态自建 8.1.2 + libmp3lame，命令行进程调用） | 音频抽取/转码 | LGPL-2.1+ |
| [OkHttp](https://github.com/square/okhttp) | 抖音/汽水引擎 HTTP | Apache-2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 平台接口 JSON 解析 | Apache-2.0 |
| Jetpack Compose、DataStore、Lifecycle 及 AndroidX 组件 | UI 与存储基础 | Apache-2.0 |
| [fusion-pixel-font](https://github.com/TakWolf/fusion-pixel-font) | 界面像素字体 | SIL OFL 1.1 |

以上组件的版权归各自作者所有，感谢开源社区。

## 许可证

本项目以 [GNU GPL v3](LICENSE) 协议开源。衍生作品需要以同协议开源并保留版权声明。
