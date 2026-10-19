pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// jitpack.io：引擎 yt-dlp-android 本 fork 的 AAR 只经 JitPack 分发（tag 2.0.4 构建）；
// mavenLocal 最前为本地开发快路径（无对应构件时自动落到 jitpack，公开用户不受影响）
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { mavenLocal(); google(); maven("https://jitpack.io"); mavenCentral() }
}
rootProject.name = "linkdown-android"
include(":app")
