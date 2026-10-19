import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.linkdown.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "top.linkdown.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
            // -Pemulator=true：模拟器验证变体（x86_64 上 Berberis 起不来翻译版 CPython，需原生 x86_64）；
            // 须配合未入库的 app/src/main/jniLibs/x86_64/（arm64 ffmpeg/ffprobe 副本，exec 走翻译）
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }

    // release 签名：**不入库**（开源合规——签名私钥公开=任何人可冒充发更新）。
    // 凭据从 local.properties 读（release.keystore.path/password/alias，本地文件已 gitignore）；
    // 未配置则 release 构建不签名（unsigned APK，使用者自行签名）——CI/第三方构建开箱可用。
    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
            val ksPath = props.getProperty("release.keystore.path")
            if (ksPath != null) {
                storeFile = rootProject.file(ksPath)
                storePassword = props.getProperty("release.keystore.password")
                keyAlias = props.getProperty("release.keystore.alias") ?: "linkdown"
                keyPassword = props.getProperty("release.keystore.password")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // 保持关：个人工具无混淆/压缩诉求，保持可调试栈
            signingConfig = signingConfigs.getByName("release").storeFile
                ?.let { signingConfigs.getByName("release") }
        }
    }
}

dependencies {
    implementation("com.github.liuyouwu1999:linkdown-ytdlp-android:2.0.4") // 本 fork 经 JitPack 分发（坐标=com.github.<user>，与上游 2.0.2 不同名无遮蔽）；per-run 取消 + --flat-playlist 白名单第 5 项
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // probe：JsonParser 树遍历（无 schema/无编译插件）
    implementation("androidx.datastore:datastore-preferences:1.1.7") // 队列持久化（Preferences，JSON 列表）
    implementation(libs.okhttp) // 抖音/汽水引擎 HTTP
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.mockwebserver) // DouyinEngine/QishuiEngine fixture 单测
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // 调度状态机 runTest 驱动
}
