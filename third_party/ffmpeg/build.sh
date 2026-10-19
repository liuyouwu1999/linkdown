#!/usr/bin/env bash
#
# third_party/ffmpeg/build.sh — 可复现记录：静态自建 arm64 ffmpeg/ffprobe（自建, 构建）
#
# 上游工具链: https://github.com/Javernaut/ffmpeg-android-maker
# 上游 commit: dd72b16（master，2026-10-15 时点，"Merge pull request #125 from Javernaut/dependabot/github_actions/actions/checkout-7"）
# FFmpeg 源码: 上游默认 TAR 8.1.2（https://www.ffmpeg.org/releases/ffmpeg-8.1.2.tar.bz2，脚本自动下载）
# libmp3lame: 3.100（http://downloads.videolan.org/pub/contrib/lame/lame-3.100.tar.gz，脚本自动下载）
#
# 为什么先 sed：上游默认产物是动态链接（--enable-shared --disable-static 硬编码），
# 真机无法 exec 带 libav*.so 依赖的 CLI；改为 --disable-shared --enable-static 后
# ffmpeg/ffprobe 的 NEEDED 仅剩系统库（libc/libm/libdl/libz 一类），可直接放进 jniLibs。
#
# 与 设计说明 中 sed 的偏差（上游脚本结构实况，语义相同）：
#   设计说明 假设两个标志在同一行（单空格分隔）；实际 scripts/ffmpeg/build.sh L48-49
#   是两行续行（--enable-shared \ / --disable-static \），
#   故拆成两个 -e 表达式逐个替换。
#
# 已知无害现象：静态构建后 build/ffmpeg/<abi>/lib/ 只有 .a 没有 .so，
#   主脚本结尾 prepareOutput 的 `cp lib/*.so` 会报错并使脚本退出码非零——
#   产物此时已完整落在 build/ffmpeg/arm64-v8a/bin/（bin/ 在 build/ 下，不在 output/lib/）。
#
# 前置要求（宿主 macOS；sed 为 BSD sed，Linux 下需把 `sed -i ''` 改成 `sed -i`）：
#   - Android NDK r23+（本机: brew install --cask android-ndk → /opt/homebrew/share/android-ndk）
#   - ANDROID_SDK_HOME、ANDROID_NDK_HOME 两个环境变量（check-host-machine.sh 强制检查）
#
# 用法:
#   export ANDROID_SDK_HOME=/opt/homebrew/share/android-commandlinetools
#   export ANDROID_NDK_HOME=/opt/homebrew/share/android-ndk
#   ./third_party/ffmpeg/build.sh [ffmpeg-android-maker 目录，默认 /tmp/ffmpeg-android-maker]
#
set -euo pipefail

FAM_DIR="${1:-/tmp/ffmpeg-android-maker}"
REPO_URL="https://github.com/Javernaut/ffmpeg-android-maker.git"
FAM_COMMIT="dd72b16"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# 1. 克隆并固定上游 commit
if [ ! -d "$FAM_DIR/.git" ]; then
  git clone "$REPO_URL" "$FAM_DIR"
fi
cd "$FAM_DIR"
git checkout "$FAM_COMMIT"

# 2. 静态化（保留脚本其余不动）
sed -i '' -e 's/--enable-shared/--disable-shared/' -e 's/--disable-static/--enable-static/' scripts/ffmpeg/build.sh

# 3. 构建（下载 ffmpeg 8.1.2 + lame 3.100 源码，耗时较长）
./ffmpeg-android-maker.sh --target-abis=arm64-v8a --enable-libmp3lame

# 4. 落位 jniLibs（重命名即派生命：yt-dlp 对文件形式 --ffmpeg-location 按
#    filename.replace(basename, p) 派生 ffprobe，libffmpegexec.so → libffprobeexec.so）
mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
cp build/ffmpeg/arm64-v8a/bin/ffmpeg  "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libffmpegexec.so"
cp build/ffmpeg/arm64-v8a/bin/ffprobe "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libffprobeexec.so"

echo "done: jniLibs updated"
