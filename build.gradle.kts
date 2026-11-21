// Top-level build file where you can add configuration options common to all sub-projects/modules.

/*
 * ============================================================
 * ⚠️⚠️ Kotlin 编译器版本由**这里**决定（AGP 内置 Kotlin）
 * ============================================================
 * 本项目没有 `org.jetbrains.kotlin.android` 插件，Kotlin 编译任务由 AGP 创建
 * （AGP 9 的 built-in Kotlin）。编译器版本 = AGP 依赖的 kotlin-gradle-plugin 版本：
 *
 *   AGP 9.2.1 自带 2.2.10 → 这里放 2.4.21 把它**覆盖**上去
 *
 * ⚠️ 只改 `gradle/libs.versions.toml` 的 `kotlin` 是没用的 —— 那只喂给
 *    `org.jetbrains.kotlin.plugin.compose`（Compose 编译器插件），不改编译器本体。
 *
 * ⚠️⚠️ **两处必须一起改、且版本必须一致**：
 *      ① 这里：buildscript classpath 的 kotlin-gradle-plugin
 *      ② gradle/libs.versions.toml：`kotlin = "..."`
 *    内建编译器 2.4 + Compose 插件 2.2（或反过来）会直接报版本不匹配。
 *
 * ⚠️ 不要改成 `plugins { id("org.jetbrains.kotlin.android") }`：会和内置 Kotlin 撞车，
 *    报 "plugin is already on the classpath with an unknown version"（2026-11-15 踩过）。
 *
 * 完整说明、边界与「怎么验证真的生效」：`docs/kotlin-version.md`
 * （验证方法：反编译任一 class 看 `@Metadata(mv=[2,4,0])`，别只看 toml）。
 */
buildscript {
    repositories {
        // 与 settings.gradle.kts 同样的理由：本机解析不了 repo.maven.apache.org，镜像放前面
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
