pluginManagement {
    repositories {
        /*
         * ============================================================
         * ⚠️ 阿里云镜像放在最前面
         * ============================================================
         * 这台机器上 **JVM 解析不了 `repo.maven.apache.org`**
         * （PowerShell 里能访问，但 Gradle 报"不知道这样的主机"），
         * 于是任何没缓存过的新依赖都拉不下来。
         *
         * ⚠️ 为什么不是给 Gradle 配代理:本机没有代理，是 DNS 层面的问题；
         * 用镜像最省事，而且国内访问本来就更快。
         *
         * ⚠️ 顺序很重要:镜像在前、官方在后 —— 官方仓库在这里只会**超时**，
         * 放前面会让每次构建都先卡一遍。
         */
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        /* ⚠️ 同上:镜像在前，理由见 `pluginManagement` 里的说明 */
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "sth key"
include(":app")
