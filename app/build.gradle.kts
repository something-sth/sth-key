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
        versionCode = 261
        versionName = "2.6.1"

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

    /*
     * ============================================================
     * ⚠️⚠️ useLegacyPackaging 是**手柄功能必需**的，不要删
     * ============================================================
     * 手柄的 native helper（`jniLibs/arm64-v8a/libgamepadmonitor.so`）
     * 需要被 **shell 身份**的进程 `exec`：
     *
     *     /system/bin/sh -c "cat <helper> > /data/local/tmp/xxx && chmod 700 … && exec …"
     *
     * 所以系统必须在**安装时把它解包成真实文件**放进
     * `ApplicationInfo.nativeLibraryDir`。AGP 9 默认
     * （`extractNativeLibs=false`）是**不压缩、从 APK 内存映射** ——
     * 那样文件在文件系统里**根本不存在**，上面的命令必然失败。
     *
     * ⚠️ 这个开关**只能在 build script 里配**。在 Manifest 里写
     * `android:extractNativeLibs="true"` 会让 AGP 9 **直接构建失败**
     * 并指名要改用这里（实测过）。
     *
     * ============================================================
     * ⚠️ v2.5.1 那句"配了也没用"是错的
     * ============================================================
     * 当时的注释写着:
     *
     * > `useLegacyPackaging = true` 配了也没用，`.so` 照样被压缩；
     * > 在 Manifest 里写 `android:extractNativeLibs="true"` 能编过，
     * > 但反而把原本未压缩的第三方库也压了
     *
     * 实测（`clean` 之后重新打包）**两个结论都不成立**:
     *
     * | 配置 | APK 里 `lib/` 的压缩状态 |
     * |---|---|
     * | 什么都不配 | 未压缩（100%，但**不解包**） |
     * | **配了 `useLegacyPackaging = true`** | 未压缩（100%，**且解包**）✅ |
     *
     * 也就是说这个开关只改"解不解包"，**不碰压缩率** ——
     * 第三方库（`libandroidx.graphics.path.so`）依然是 10,096 字节未压缩。
     *
     * 当年看不出差别，是因为**只测了 root**:root 能无视 SELinux
     * 从应用私有目录读文件，所以"assets 解包到 filesDir"那条路
     * 看起来能用 —— 而 Shizuku 通道从来读不到（见
     * `GamepadNativeMonitor.prepareHelper` 的长注释）。
     */
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    /*
     * ============================================================
     * ⚠️⚠️ Kotlin 的 jvmTarget 必须显式写，而且要与 Java 那边一致
     * ============================================================
     * 这里**曾经是空的**，于是 Kotlin 默认跟随**编译用的 JDK** ——
     * 本项目用 JDK 25 构建，所以 jvmTarget 实际是 25。
     *
     * 后果是一次**线上闪退**（用户日志）:
     *
     * ```
     * java.lang.NoSuchMethodError: No virtual method removeLast()Ljava/lang/Object;
     *   in class Ljava/util/ArrayList;
     * ```
     *
     * `List.removeLast()` 是 **Java 21** 才加进 `java.util.List` 的默认方法，
     * 而 Android 的 `ArrayList` **从来没有**它。JDK 25 下编译时，
     * Kotlin 看见 `List` 上有这个方法，就把 `boxes.removeLast()`
     * 编成了一次**成员调用** —— 编译过、单测过（JVM 上确实有）、
     * 打包正常，**只有在 Android 上跑到那一行才崩**。
     *
     * ⚠️ 把 jvmTarget 钉到 11 之后，Kotlin 编译时看到的 `List` 就是
     * Java 11 那个（没有 `removeLast`），于是它会改用 stdlib 的扩展函数，
     * **或者直接编译失败** —— 无论哪种，都不会再产出只能在 JDK 上跑的字节目。
     *
     * ⚠️ 不要把它调高:Android 不是 OpenJDK，`java.util` 的新默认方法
     * （`removeLast` / `removeFirst` / `getFirst` / `getLast`，Java 21 的
     * SequencedCollection）在 libcore 里都不存在。
     */
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    /*
     * ============================================================
     * ⚠️ 这里**故意什么都不加** —— 编译器堵不住那个闪退（实测过）
     * ============================================================
     * 用户报的闪退:
     *
     * ```
     * java.lang.NoSuchMethodError: No virtual method removeLast()Ljava/lang/Object;
     *   in class Ljava/util/ArrayList;
     * ```
     *
     * `List.removeLast()` 是 **Java 21** 才加进 `java.util.List` 的默认方法，
     * 而 Android 的 `ArrayList` 从来没有它。麻烦在于 **`removeLast()` 写出来
     * 完全合法**，只有打包后跑到那一行才崩。
     *
     * 试过两种编译期开关，**都没用**（实测字节码里依然是
     * `InterfaceMethod java/util/List.removeLast`）:
     *
     * | 开关 | 为什么没用 |
     * |---|---|
     * | `jvmTarget = 11` | 只改**字节码版本**，不改 Kotlin 解析 `java.util.List` 时用的类库 |
     * | `-Xjdk-release=11` | 只管**源码**看到哪个 API；而 `removeLast` 走的是 **Kotlin stdlib 的扩展函数**，那个扩展自己的字节码里就调了 `java.util.List.removeLast()` |
     *
     * ⚠️ 所以防线放在**源码级**:`AndroidApiSafetyTest` 会扫描
     * `app/src/main/java` 下所有 .kt，出现这些调用**直接测试失败**并说明原因。
     * 那才是唯一能在"上真机之前"拦住它的地方。
     *
     * 需要 `removeLast` 语义时，用 `removeAt(lastIndex)` ——
     * 它是 `java.util.List` 从 Java 1.2 起就有的方法。
     */
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
