# Kotlin 版本与升级方式（构建基础设施）

> **2026-11-21 首次落地：Kotlin `2.2.10` → `2.4.21`，全量单测 + release 构建通过。**
>
> 这份文档回答一个问题：**这个项目的 Kotlin 编译器版本由谁决定、怎么改、怎么验证改没改成功。**
> 改构建脚本之前先读这里，尤其是 §2 —— 只改 `libs.versions.toml` 是**没有用**的。

---

## 1. 现状

| 项 | 版本 | 决定方式 |
|---|---|---|
| Kotlin 编译器 | **2.4.21** | 根 `build.gradle.kts` 的 buildscript classpath（见 §3） |
| Kotlin Compose 插件 | **2.4.21** | `gradle/libs.versions.toml` 的 `kotlin`（见 §2 末） |
| AGP | 9.2.1 | `gradle/libs.versions.toml` 的 `agp` |
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties` |
| JDK | 25.0.4（Android Studio JBR） | `JAVA_HOME` + `gradle/gradle-daemon-jvm.properties` 的 `toolchainVersion=25` |
| jvmTarget / 字节码 | **11** | `app/build.gradle.kts` 的 `kotlin { compilerOptions { jvmTarget } }`（**不要动**，见 §5） |

---

## 2. ⚠️ 为什么只改 `libs.versions.toml` 没用

本项目用的是 **AGP 9 的「内置 Kotlin」**：模块里**没有** `org.jetbrains.kotlin.android` 插件，
Kotlin 编译任务由 AGP 自己创建。

```
app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)   // ← AGP，内置 Kotlin 支持
    alias(libs.plugins.kotlin.compose)        // ← 只是 Compose 编译器插件
}
```

- **编译器本体**来自 AGP 依赖的 `org.jetbrains.kotlin:kotlin-gradle-plugin`，
  AGP 9.2.1 的 POM 里钉的是 **2.2.10**（9.5.x 是 2.3.20）。
- `libs.versions.toml` 里的 `kotlin = "..."` 只喂给 `kotlin-compose` 这个插件条目，
  **不影响编译器本体** —— 这就是 2026-11-15 那次「只改了 Compose 插件、编译器还是 2.2」的原因。

⚠️ **两者必须是同一个版本**：内置 Kotlin 2.2 + Compose 插件 2.4 会直接报版本不匹配；
反过来（编译器 2.4 + Compose 插件 2.2）同样不行。

---

## 3. 正确做法（已实测）

两处一起改：

**① `gradle/libs.versions.toml`**

```toml
kotlin = "2.4.21"
```

**② 根 `build.gradle.kts`：把 KGP 放到 buildscript classpath（覆盖 AGP 自带的版本）**

```kotlin
buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
```

原理：AGP 的内置 Kotlin 会从 **buildscript classpath** 取 Kotlin 编译器；
那里放一个更高版本的 KGP，Gradle 的冲突解析会取高版本，内置 Kotlin 就跟着用新版本。

**边界：**

| 想做的事 | 做法 |
|---|---|
| 升到**更高**版本 | 上面两处一起改（本次做法）✅ |
| 降到**低于 AGP 自带**的版本 | 必须先 `android.builtInKotlin=false`（还要 `android.newDsl=false`），再声明严格版本 —— 不建议，那是 AGP 10 要移除的路径 |
| 用 `plugins { id("org.jetbrains.kotlin.android") }` | ❌ 会撞车：`plugin is already on the classpath with an unknown version`（2026-11-15 踩过） |
| 只升 AGP 指望编译器跟着升 | ❌ 不成立：AGP 9.2.1 / 9.3.x 都是 2.2.10，9.5.x 才到 2.3.20 —— 仍然到不了 2.4 |

---

## 4. 怎么验证「真的生效了」（别只看 toml）

```powershell
$env:JAVA_HOME="D:\Program\Android\Android Studio\jbr"
cd D:\Project\sthkey

# ① 依赖解析：应为 2.4.21
.\gradlew.bat :app:dependencies --configuration debugCompileClasspath |
    Select-String "kotlin-stdlib"

# ② 字节码元数据：每个 Kotlin 类上都有 @Metadata(mv=[2,4,0])
.\gradlew.bat :app:compileDebugKotlin
& "$env:JAVA_HOME\bin\javap.exe" -v -p `
  "app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes\com\something\sthkey\MainActivity.class" |
  Select-String "mv=\["
```

- `mv=[2,4,0]` = 编译器是 **2.4.x**；`mv=[2,2,0]` = 还在旧版本。
- ⚠️ 编译产物在 `app/build/intermediates/built_in_kotlinc/<variant>/...`，
  **不是** `build/tmp/kotlin-classes`（那是独立 kotlin-android 插件的位置）。

---

## 5. 升级时必须一起检查的东西

| 检查项 | 为什么 |
|---|---|
| Compose 编译器版本 = Kotlin 版本 | 见 §2 末；当前 BOM 2026.08.00 + Kotlin 2.4.21 实测可用 |
| `kotlin { compilerOptions { jvmTarget = JVM_11 } }` | **不能删、不能跟着升**：Android 的 `ArrayList` 没有 `List.removeLast()`（Java 21 才有），升上去会在真机闪退。`AndroidApiSafetyTest` 会扫源码兜底 |
| `AndroidApiSafetyTest` 全绿 | 同上，它是唯一能在打包前拦住「只能在 JDK 上跑」的字节码的检查 |
| 全量单测 `testDebugUnitTest` | 本次 **787 个用例**全过（85 个测试类） |
| `assembleRelease` | R8 + 资源压缩 + 签名；本次通过（见 §6） |
| 有没有引入 Kotlin 生态插件（KSP / kapt / serialization） | 它们的版本也必须跟 Kotlin 对齐；本项目**没有**这类插件 |

---

## 6. 2026-11-21 这次升级的实测结果

| 步骤 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | ✅ `BUILD SUCCESSFUL in 3m 9s`，25 条警告（均为既有代码的 deprecation / 冗余写法，无新错误） |
| 字节码元数据 | ✅ `@Metadata(mv=[2,4,0])`，`kotlin-stdlib` 解析到 2.4.21 |
| `:app:testDebugUnitTest` | ✅ 85 个测试类 / **787 个用例**，0 失败 |
| `:app:assembleRelease` | ✅ `BUILD SUCCESSFUL in 5m 33s`（含 `minifyReleaseWithR8` / `lintVitalRelease`） |
| APK | `app/build/outputs/apk/release/app-release.apk`，10,212,809 B，v1+v2 签名，证书 SHA-256 `8415FB31…6949A1`（与 `newkey999888` 一致） |

> ⚠️ **产物位置别搞混**：
> - **命令行** `assembleRelease` → `app/build/outputs/apk/release/app-release.apk`（AGP 默认位置）
> - **Android Studio 的「Generate Signed Bundle / APK」向导** → `app/release/app-release.apk`
>
> 两者都存在过，看到旧的 `app/release/` 时间戳没变**不代表构建失败**（本次就差点误判）。

---

## 7. 下次再升级

1. 改 §3 的两处（toml + buildscript），**两边版本必须一致**；
2. `./gradlew.bat clean && ./gradlew.bat :app:testDebugUnitTest :app:assembleRelease`；
3. 按 §4 验证 `mv=[x,y,0]`；
4. 若在**配置阶段**就崩（`NoSuchMethodError` / `ClassNotFoundException` 指向 AGP 内部），
   说明新 KGP 与 AGP 内部 API 不兼容 —— 那时才需要考虑升 AGP：
   AGP **9.5.x 要求 Gradle ≥ 9.6.0**（当前 wrapper 是 9.4.1），属于另一轮迁移。

---

## 8. 历史

| 日期 | 事件 |
|---|---|
| 2026-11-15 | 为接 miuix 0.9.4 尝试升 Kotlin，三次都失败（只改 toml / 关内置 Kotlin / 升 AGP 9.4.1）—— 详见 `docs/ui-plan.md` §4.2 |
| 2026-11-21 | 换新电脑后迁移项目；用 buildscript classpath 覆盖法升到 **2.4.21**，单测 + release 全过 |
