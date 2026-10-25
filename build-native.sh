#!/data/data/com.termux/files/usr/bin/bash
#
# ============================================================
# SthKey 原生 helper 编译脚本
# ============================================================
# 在 Termux（手机端）里跑:
#
#     bash build-native.sh
#
# 产物落到 `app/src/main/jniLibs/arm64-v8a/`，然后正常 gradle 打包即可。
#
# ============================================================
# ⚠️ 为什么要单独一个脚本，而不是交给 NDK / CMake
# ============================================================
# 这些 helper 是**独立可执行文件**，不是 JNI 库:
# 它们被复制到 /data/local/tmp 后直接 `exec`，由 root 或 Shizuku 的
# shell 身份运行（见 GamepadNativeMonitor 的部署流程）。
#
# 所以它们:
#   - **不需要 JNI**（没有 jni.h、没有 extern "C" 导出）；
#   - **不需要 libc++**（`-nostdlib++`，只链 libc）——
#     少了这个依赖，就不用把 C++ 运行库也塞进 ARM 的 /data/local/tmp；
#   - 是 `-fPIE -pie`（可执行文件），不是 `-shared`。
#
# ⚠️ 这套配方**照抄 Axon 的 build-native.sh**（它已在真机验证），
# 只改了文件名与输出目录。不要自作主张换 flag。
#
# ============================================================
# ⚠️ 加新 helper 的步骤
# ============================================================
# 1. 把 `.cpp` 放到本仓库根的 `native/` 下；
# 2. 在下面加三行:源文件、输出名、以及一条 clang++ 调用；
# 3. `bash build-native.sh`，确认产物出现在 jniLibs 里；
# 4. **把生成的 .so 一起提交** —— 它是打包的一部分，不提交就等于功能缺失。
#
# 注意产物是**架构相关**的:目前只编 arm64-v8a。
# 32 位设备（armeabi-v7a）需要另外编一份，本脚本会明确拒绝而不是
# 悄悄产出不匹配的东西。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$ROOT/native"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"

# ---- 源文件 ----
TOUCH_MONITOR_SRC="$NATIVE_DIR/touchmonitor.cpp"

# ---- 产物 ----
TOUCH_MONITOR_BIN="$OUT_DIR/libtouchmonitor.so"

# 只支持在 arm64 设备/环境上编（Termux 就是 arm64）
case "$(uname -m)" in
    aarch64|arm64) ;;
    *)
        echo "[SthKey] 本脚本只在 arm64 上构建（当前: $(uname -m)）" >&2
        exit 1
        ;;
esac

if ! command -v clang++ >/dev/null 2>&1; then
    echo "[SthKey] 未找到 clang++。Termux 里先装:" >&2
    echo "    pkg install clang -y" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

# ============================================================
# 触屏 helper
# ============================================================
# ⚠️ 参数逐个说明（改之前先看这里）:
#
#   -std=c++20    源码用了 C++17/20 的语法（structured binding、内联初始化）
#   -fPIE -pie    编成**位置无关的可执行文件**（Android 10+ 强制要求）
#   -O2           体积与速度的平衡点；helper 很轻，-O3 收益不明显
#   -fno-exceptions -fno-rtti
#                 不链 C++ 运行库的前提（异常与 RTTI 都要运行库支持）
#   -nostdlib++   **不链 libc++** —— 少一个运行时依赖，见文件头说明
#   -Wl,--no-undefined
#                 有未定义符号就直接报错，而不是留到运行时才崩
#                 （少了它，漏写的函数会在手机上 exec 失败，很难查）
#
echo "[SthKey] 编译触屏监控 -> $(basename "$TOUCH_MONITOR_BIN")"
clang++ \
    -std=c++20 -fPIE -pie -O2 \
    -fno-exceptions -fno-rtti -nostdlib++ \
    -Wl,--no-undefined \
    "$TOUCH_MONITOR_SRC" -o "$TOUCH_MONITOR_BIN"
chmod 755 "$TOUCH_MONITOR_BIN"

# ---- 顺便报一下依赖，确认没有意外链上 libc++ ----
#
# ⚠️ 结尾的 `|| true` 不能省:设备本来就只依赖 libc 时，
# `grep NEEDED` 一行都匹配不到、返回 1 —— 而脚本开头有 `set -e`，
# 于是"编译成功"会被这一句判定成失败、整个脚本报错退出。
#
# ⚠️ 但它仍然值得打:多出一个 `libc++_shared.so` 就意味着
# 到了真机上 exec 会失败（那个库不在 /data/local/tmp 里）。
if command -v readelf >/dev/null 2>&1; then
    echo "[SthKey] 触屏监控的动态依赖（应该只有 libc / libdl 之类）:"
    readelf -d "$TOUCH_MONITOR_BIN" | grep NEEDED || true
fi

echo "[SthKey] 完成。产物:"
ls -l "$TOUCH_MONITOR_BIN"
