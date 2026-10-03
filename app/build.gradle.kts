plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ⚠️ 必须写在文件顶部：Gradle Kotlin DSL 里 `plugins {}` 之后不能再写 import / 声明语句。
import java.text.SimpleDateFormat
import java.util.Date

/*
 * 构建标记（构建时刻，格式 `MMdd-HHmmss`）。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 * `versionName` 只在**发版时**才动，所以开发期间它不代表"装的是哪一次构建"——
 * 排查问题时无法排除"手机上还是旧包"这个可能性，而这恰恰是最常见的原因之一。
 * 界面上显示成「构建 0926-1903-42」，它不是版本号，
 * **不要拿它做任何判断**（公告、配置包都不该看它）。
 *
 * 精确到**秒**而不是分钟：改完一行代码重建只花十几秒，
 * 只到分钟的话前后两次构建会显示同一个标记，
 * "换了包没有"这个判断反而做不了。
 *
 * ============================================================
 * ⚠️ 它只在**第一次**配置这个项目时确定，配置缓存命中时不会更新
 * ============================================================
 * `buildConfigField` 的值是在**配置阶段**求值的。Gradle 的配置缓存
 * 一旦命中就不再重新配置项目，于是这个字段会一直停在第一次配置的时刻。
 * 这不是 bug，而是"编译期常量"的固有性质：**只有源码或配置变了它才会变**。
 *
 * 约定：**打包发布前 `./gradlew clean` 一次**，标记就会刷新到那一刻。
 * 没有搞"每次构建自动刷新"——那需要额外的任务 + 输出文件 +
 * 让 `GenerateBuildConfig` 依赖它，为诊断用的标记动构建流水线不划算。
 */
val buildTag: String = SimpleDateFormat("MMdd-HHmmss").format(Date())

android {
    namespace = "com.something.sthkey"
    // Compose 1.12.0（BOM 2026.08.00）的 AAR metadata 要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.something.sthkey"
        minSdk = 30
        // targetSdk 保持 36：它决定运行时行为（是否受新版系统限制），
        // 与"用什么 SDK 编译"是两件事，没必要跟着一起升
        targetSdk = 36
        versionCode = 250
        versionName = "2.5.0"

        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        // 调试页需要读取 BuildConfig.DEBUG
        buildConfig = true
        /*
         * 不再需要 aidl：UserService 通道（那两个 .aidl）已经删掉了，
         * 现在走的是"裸 Binder 事务 + 进程输出"，没有任何跨进程接口要生成。
         * 留着这个开关只会让构建多跑一个空任务。
         */
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
