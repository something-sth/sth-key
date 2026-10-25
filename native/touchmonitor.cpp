// SthKey touchscreen monitor.
//
// ============================================================
// 这是什么
// ============================================================
// 在 root 或 Shizuku 的 shell 身份下**只读**地读触屏设备的 evdev 事件，
// 把当前所有触点归一化成文本协议写到 stdout，由 Kotlin 侧解析。
//
// **从不 EVIOCGRAB、从不注入任何输入** —— 与手柄 helper 同一个原则。
//
// ============================================================
// ⚠️ 为什么必须原生（这段历史与手柄那条一模一样，别删）
// ============================================================
// 一开始想过直接解析 `getevent` 的文本输出（键盘/鼠标那条路）。
// 走不通，因为 **`getevent` 不打印轴的取值范围**（min/max），
// 而没有 min/max 就**无法把触点归一化**——
// 不同机器的触屏 ADC 范围从 0..1023 到 0..32767 都有，
// 拿裸值去和屏幕比例比，算出来的位置全错。
//
// 手柄当年也是卡在这里（见 GamepadNativeProtocol 的注释），
// 所以这条路的结论是共用的:**要轴能力就得上原生**。
//
// ============================================================
// ⚠️ 协议（与 GamepadNativeProtocol 同一套习惯）
// ============================================================
// ```
// STATUS starting
// STATUS touch-ready /dev/input/eventX | <设备名> | MT+SLOT+DIRECT
// STATUS waiting-touch
// STATUS touch-disconnected
// STATUS no-touchscreen-candidate
// STATUS input-permission-denied
// STATUS no-input-devices
// STATUS helper-copy-failed <原因>
// STATUS stopped
// TOUCH <数量> <id> <x> <y> <id> <x> <y> ...
// ```
//
// | 字段 | 范围 | 说明 |
// |---|---|---|
// | 数量 | `0 .. 20` | 当前**同时**按下的触点数；`0` 表示手指都抬起来了 |
// | `id` | `>= 0` | 触点标识（来自 `ABS_MT_TRACKING_ID`，没有就用槽位号） |
// | `x` `y` | `0 .. 100000` | **已归一化**的屏幕坐标，左上角是 `(0,0)` |
//
// ⚠️ `waiting-touch` 与 `touch-disconnected` 是**两种不同的状态**，
// 不能合成一条（手柄那边踩过这个坑，见 GamepadNativeProtocol 的说明）:
//
// | 状态 | 什么时候出现 | 该不该记日志 |
// |---|---|---|
// | `waiting-touch` | 启动后**还没认出触屏** —— 常态 | 不该（会刷屏） |
// | `touch-disconnected` | 认出来过、又断了 | 该记 |
//
// ⚠️ `helper-copy-failed` 不是本程序打印的 —— 它来自 Kotlin 侧部署脚本里
// 那道 `test -s`（复制失败时不能静默 exec 一个空文件）。列在这里是为了
// 让"协议全貌"在一处看得全。
//
// ⚠️ 坐标是"屏幕自然方向（竖屏）"的 —— **屏幕旋转由 Kotlin 侧换算**。
// 原生这边拿不到可靠的旋转信息，而 Java 侧一个 `Display.getRotation()`
// 就有，所以分工是刻意的:原生只管"设备坐标 → 0..100000"。
//
// ============================================================
// ⚠️ 「有变化才输出」
// ============================================================
// 手指不动时**一帧都不输出**。这不只是省电:
// Kotlin 侧每收到一帧就要做一次命中测试与组件状态更新，
// 静止时每秒 100 次无变化的刷新会把主线程刷满。
// 所以 emitFrame 里有一次 `frameSame` 去重（与 Axon 的做法一致）。
//
// ============================================================
// ⚠️ 设备挑选是**打分猜**的，这是本文件最脆弱的一环
// ============================================================
// Android 没有"给我触屏设备"的 API，只能扫 /dev/input/event*，
// 按能力位与设备名打分选一个最优的（见 touchDeviceScore）。
// 不同 ROM 的设备名千奇百怪，所以:
//   - 打分规则要能解释（每一档为什么加分写在注释里）；
//   - `STATUS touch-ready` 里**带上设备名与能力**，挑错了能一眼看出来。
//
// 编译见仓库根的 `build-native.sh`。

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

namespace {

/** 一次 read 最多处理多少事件（内核缓冲区就这么大） */
constexpr int kMaxEvents = 256;

/** 多点触控的槽位数上限。真实触屏一般 <= 10，留一倍余量 */
constexpr int kMaxSlots = 20;

/** A 协议（无 SLOT）时，一次 SYN 周期内最多攒多少个触点 */
constexpr int kMaxTypeAContacts = 20;

/** 找不到设备时的重扫间隔（毫秒） */
constexpr int kScanIntervalMs = 900;

/** 归一化后的坐标范围 —— 与 Kotlin 侧的 NATIVE_COORD_SCALE 必须一致 */
constexpr int kCoordScale = 100000;

/**
 * 设备名里含这个前缀的直接排除。
 *
 * ⚠️ 是给"虚拟设备"留的:某些 ROM / 注入类应用会造一个假的触屏节点，
 * 抢在真触屏前面（它往往还带 DIRECT 属性，打分很高）。
 * SthKey 自己不做注入，但系统里可能装着别家的。
 */
constexpr const char* kVirtualPrefix = "SthKey Virtual";

volatile sig_atomic_t gStop = 0;

void onSignal(int) { gStop = 1; }

/**
 * 单调时钟（毫秒）。
 *
 * ⚠️ 必须用 `CLOCK_MONOTONIC` 而不是墙上时钟:重扫间隔靠它算，
 * 而墙上时钟会被 NTP 校准/时区调整往前往后拨 —— 拨回去就会
 * "刚扫过又要扫"，拨快了则一直不扫（设备热插拔后永远连不上）。
 */
long long nowMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

bool bitTest(const unsigned long* bits, int bit) {
    constexpr int kBitsPerLong = static_cast<int>(sizeof(unsigned long) * 8);
    return (bits[bit / kBitsPerLong] >> (bit % kBitsPerLong)) & 1UL;
}

template <size_t N>
bool getBits(int fd, int type, unsigned long (&bits)[N]) {
    memset(bits, 0, sizeof(bits));
    return ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits) >= 0;
}

bool getDeviceName(int fd, char* out, size_t size) {
    if (!out || size == 0) return false;
    memset(out, 0, size);
    return ioctl(fd, EVIOCGNAME(static_cast<int>(size - 1)), out) >= 0;
}

bool getAbsInfo(int fd, int code, input_absinfo* out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));
    return ioctl(fd, EVIOCGABS(code), out) >= 0 && out->maximum > out->minimum;
}

/** 大小写不敏感的子串判断（设备名对比用，不能依赖 libc++） */
bool containsInsensitive(const char* text, const char* needle) {
    if (!text || !needle || !*needle) return false;
    char lowerText[160]{};
    char lowerNeedle[64]{};
    size_t tn = strlen(text);
    size_t nn = strlen(needle);
    if (tn >= sizeof(lowerText)) tn = sizeof(lowerText) - 1;
    if (nn >= sizeof(lowerNeedle)) nn = sizeof(lowerNeedle) - 1;
    for (size_t i = 0; i < tn; ++i) {
        char c = text[i];
        lowerText[i] = (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
    }
    for (size_t i = 0; i < nn; ++i) {
        char c = needle[i];
        lowerNeedle[i] = (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
    }
    return strstr(lowerText, lowerNeedle) != nullptr;
}

/** 一个触点 */
struct Contact {
    bool active = false;
    int id = -1;
    int x = 0;
    int y = 0;
    bool hasX = false;
    bool hasY = false;
};

struct Device {
    int fd = -1;
    char path[64]{};
    char name[128]{};
    input_absinfo xInfo{};
    input_absinfo yInfo{};

    /** 有 ABS_MT_POSITION_X/Y = 多点触控设备 */
    bool useMt = false;
    /** 有 ABS_MT_SLOT = MT 协议 B（按槽位存），否则是协议 A（按 SYN 攒） */
    bool hasSlots = false;
    /** 有 ABS_MT_TRACKING_ID —— 有它才知道"这根手指是新的还是原来那根" */
    bool hasTracking = false;
    /** 有 BTN_TOUCH —— 没有它时只能靠"有没有坐标"判断手指在不在 */
    bool hasBtnTouch = false;
    bool direct = false;

    int currentSlot = 0;
    bool buttonDown = false;
    Contact slots[kMaxSlots]{};
    Contact typeACurrent{};
    Contact typeAContacts[kMaxTypeAContacts]{};
    int typeACount = 0;

    /* 上一次输出的帧，用来去重 */
    bool emittedOnce = false;
    int lastCount = -1;
    int lastIds[kMaxSlots]{};
    int lastXs[kMaxSlots]{};
    int lastYs[kMaxSlots]{};
};

/**
 * 设备原始坐标 → `0 .. kCoordScale`。
 *
 * ⚠️ 用 `long long` 中间量:某些设备的 maximum 能到 32767，
 * 而 `value * 100000` 在 32 位里会溢出（32767 × 100000 ≈ 3.3e9 > 2^31）。
 * 那会让坐标在屏幕边缘**翻转成负数**，表现是"手指滑到边上就跳到另一边"。
 */
int normCoord(int value, const input_absinfo& info) {
    long long den = static_cast<long long>(info.maximum) - info.minimum;
    if (den <= 0) return 0;
    long long num = static_cast<long long>(value) - info.minimum;
    long long out = num * kCoordScale / den;
    if (out < 0) out = 0;
    if (out > kCoordScale) out = kCoordScale;
    return static_cast<int>(out);
}

/**
 * 给一个输入设备打分，`<= 0` 表示"不是触屏"。
 *
 * ⚠️ 每一档都写清为什么 —— 以后要调权重时才知道动了什么。
 *
 * ⚠️ 目前是**抄 Axon 的那套权重**。它已经在真机上验证过，
 * 所以这里不自己发明；要改就先把 `STATUS touch-ready` 里的设备名
 * 和打分结果对照着看。
 */
int touchDeviceScore(int fd, char* nameOut, size_t nameSize, Device* capsOut) {
    unsigned long evBits[8]{};
    unsigned long absBits[8]{};
    unsigned long keyBits[16]{};
    unsigned long propBits[2]{};
    if (!getBits(fd, 0, evBits) || !bitTest(evBits, EV_ABS)) return 0;
    if (!getBits(fd, EV_ABS, absBits)) return 0;

    char name[128]{};
    getDeviceName(fd, name, sizeof(name));
    if (name[0] && strstr(name, kVirtualPrefix)) return 0;

    const bool mtX = bitTest(absBits, ABS_MT_POSITION_X);
    const bool mtY = bitTest(absBits, ABS_MT_POSITION_Y);
    const bool absX = bitTest(absBits, ABS_X);
    const bool absY = bitTest(absBits, ABS_Y);
    const bool useMt = mtX && mtY;
    /* 既没有多点也没有单点坐标 → 不是触屏（可能只是个按键节点） */
    if (!useMt && !(absX && absY)) return 0;

    input_absinfo xInfo{}, yInfo{};
    if (!getAbsInfo(fd, useMt ? ABS_MT_POSITION_X : ABS_X, &xInfo)
            || !getAbsInfo(fd, useMt ? ABS_MT_POSITION_Y : ABS_Y, &yInfo)) return 0;

    const bool hasSlots = useMt && bitTest(absBits, ABS_MT_SLOT);
    const bool hasTracking = useMt && bitTest(absBits, ABS_MT_TRACKING_ID);
    bool hasBtnTouch = false;
    if (bitTest(evBits, EV_KEY) && getBits(fd, EV_KEY, keyBits)) {
        hasBtnTouch = bitTest(keyBits, BTN_TOUCH);
    }
    memset(propBits, 0, sizeof(propBits));
    const bool direct = ioctl(fd, EVIOCGPROP(sizeof(propBits)), propBits) >= 0
            && bitTest(propBits, INPUT_PROP_DIRECT);

    /* 多点比单点强（单点几乎都是老设备或轨迹球） */
    int score = useMt ? 1200 : 300;
    /* DIRECT = 直接输入设备（手指直接指哪打哪）。触屏一定有，触摸板一定没有 */
    if (direct) score += 1800;
    /* 有 TRACKING_ID 才能可靠地分辨多根手指 */
    if (hasTracking) score += 240;
    if (hasSlots) score += 180;
    if (hasBtnTouch) score += 120;
    /* 名字里明说是触屏 —— 最强的正面信号 */
    if (containsInsensitive(name, "touchscreen") || containsInsensitive(name, "touch screen")
            || containsInsensitive(name, "touchpanel") || containsInsensitive(name, "touch panel")) {
        score += 1200;
    }
    /* ⚠️ 触摸板/鼠标/手写笔一定不是我们要的，重罚 */
    if (containsInsensitive(name, "touchpad") || containsInsensitive(name, "trackpad")) score -= 1800;
    if (containsInsensitive(name, "mouse")) score -= 2200;
    if (containsInsensitive(name, "stylus") || containsInsensitive(name, "pen")) score -= 1400;

    /* 真触屏的坐标范围是一整块二维面板；太小的多半是传感器/控制轴 */
    long long area = static_cast<long long>(xInfo.maximum - xInfo.minimum)
            * static_cast<long long>(yInfo.maximum - yInfo.minimum);
    if (area > 500000) score += 300;
    if (area < 10000) score -= 1000;

    if (score <= 0) return 0;

    if (nameOut && nameSize) snprintf(nameOut, nameSize, "%s", name[0] ? name : "touchscreen");
    if (capsOut) {
        capsOut->xInfo = xInfo;
        capsOut->yInfo = yInfo;
        capsOut->useMt = useMt;
        capsOut->hasSlots = hasSlots;
        capsOut->hasTracking = hasTracking;
        capsOut->hasBtnTouch = hasBtnTouch;
        capsOut->direct = direct;
    }
    return score;
}

void resetContacts(Device* d) {
    if (!d) return;
    d->currentSlot = 0;
    d->buttonDown = false;
    for (auto& c : d->slots) c = Contact{};
    d->typeACurrent = Contact{};
    for (auto& c : d->typeAContacts) c = Contact{};
    d->typeACount = 0;
    d->emittedOnce = false;
    d->lastCount = -1;
}

void closeDevice(Device* d) {
    if (!d) return;
    if (d->fd >= 0) close(d->fd);
    d->fd = -1;
    resetContacts(d);
}

bool attachDevice(const char* path, Device* d) {
    if (!path || !d) return false;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    Device caps{};
    char name[128]{};
    if (touchDeviceScore(fd, name, sizeof(name), &caps) <= 0) {
        close(fd);
        return false;
    }
    caps.fd = fd;
    snprintf(caps.path, sizeof(caps.path), "%s", path);
    snprintf(caps.name, sizeof(caps.name), "%s", name);
    *d = caps;
    resetContacts(d);
    d->fd = fd;
    /*
     * ⚠️ 这一行是**排查设备挑错**的唯一依据:
     * 把设备路径、名字、以及"用了 MT 还是 ABS / 有没有 SLOT / 是不是 DIRECT"
     * 都打出来。用户报"触点不对"时，先看这一行。
     */
    printf("STATUS touch-ready %s | %s | %s%s%s\n",
           d->path, d->name, d->useMt ? "MT" : "ABS",
           d->hasSlots ? "+SLOT" : "", d->direct ? "+DIRECT" : "");
    fflush(stdout);
    return true;
}

bool scan(Device* d) {
    if (!d || d->fd >= 0) return true;
    int bestScore = 0;
    char bestPath[64]{};
    int existing = 0;
    int readable = 0;
    for (int i = 0; i < kMaxEvents; ++i) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        if (access(path, F_OK) != 0) continue;
        ++existing;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        ++readable;
        int score = touchDeviceScore(fd, nullptr, 0, nullptr);
        close(fd);
        if (score > bestScore) {
            bestScore = score;
            snprintf(bestPath, sizeof(bestPath), "%s", path);
        }
    }
    if (bestScore > 0 && attachDevice(bestPath, d)) return true;

    /*
     * ⚠️ 四种失败要**分开报**，不能笼统说"找不到触屏":
     * 它们对应完全不同的处理（权限 / 设备识别 / 环境 / 还没插上）。
     *
     * ⚠️ `waiting-touch` 是**常态**（开机后触屏节点可能晚于我们启动），
     * Kotlin 侧对它**不记日志**，只用于标记"未就绪"。
     */
    if (existing > 0 && readable == 0) printf("STATUS input-permission-denied\n");
    else if (readable > 0) printf("STATUS no-touchscreen-candidate\n");
    else printf("STATUS no-input-devices\n");
    fflush(stdout);
    return false;
}

/** A 协议:一个 SYN 周期结束时把攒下的那根手指收进列表 */
void finishTypeAContact(Device* d) {
    if (!d || d->hasSlots || d->typeACount >= kMaxTypeAContacts) return;
    Contact c = d->typeACurrent;
    if (!c.hasX || !c.hasY) {
        d->typeACurrent = Contact{};
        return;
    }
    /*
     * ⚠️ 三条判据缺一不可:
     * - `buttonDown` —— 有 BTN_TOUCH 的设备，它才是"手指在屏幕上"的权威；
     * - `hasTracking` —— 有 TRACKING_ID 时那个 id 已经说明手指在；
     * - `!hasBtnTouch` —— 既没有 BTN_TOUCH 又没有 TRACKING_ID 的老设备，
     *   只能认为"报上坐标就是按着"。
     */
    c.active = d->buttonDown || d->hasTracking || !d->hasBtnTouch;
    if (c.id < 0) c.id = d->typeACount;
    if (c.active) d->typeAContacts[d->typeACount++] = c;
    d->typeACurrent = Contact{};
}

/** 把当前所有活跃触点收进 out，返回个数 */
int collectContacts(Device* d, Contact* out, int capacity) {
    if (!d || !out || capacity <= 0) return 0;
    int count = 0;
    if (d->useMt && d->hasSlots) {
        /* 协议 B:每个槽位一根手指 */
        for (int i = 0; i < kMaxSlots && count < capacity; ++i) {
            Contact c = d->slots[i];
            if (!c.active || !c.hasX || !c.hasY) continue;
            if (c.id < 0) c.id = i;
            out[count++] = c;
        }
    } else if (d->useMt) {
        /* 协议 A + MT:靠 SYN_MT_REPORT 攒出来的列表 */
        for (int i = 0; i < d->typeACount && count < capacity; ++i) {
            if (d->typeAContacts[i].active && d->typeAContacts[i].hasX
                    && d->typeAContacts[i].hasY) {
                out[count++] = d->typeAContacts[i];
            }
        }
        /* 还没到 SYN 就来了 SYN_REPORT 的情况，兜一下 */
        if (count == 0 && d->typeACurrent.hasX && d->typeACurrent.hasY
                && (d->buttonDown || !d->hasBtnTouch)) {
            Contact c = d->typeACurrent;
            c.active = true;
            if (c.id < 0) c.id = 0;
            out[count++] = c;
        }
    } else {
        /* 单点:只有一根手指 */
        Contact c = d->slots[0];
        if (d->buttonDown && c.hasX && c.hasY) {
            c.active = true;
            c.id = 0;
            out[count++] = c;
        }
    }
    return count;
}

bool frameSame(Device* d, Contact* contacts, int count) {
    if (!d->emittedOnce || d->lastCount != count) return false;
    for (int i = 0; i < count; ++i) {
        int x = normCoord(contacts[i].x, d->xInfo);
        int y = normCoord(contacts[i].y, d->yInfo);
        if (d->lastIds[i] != contacts[i].id || d->lastXs[i] != x || d->lastYs[i] != y) {
            return false;
        }
    }
    return true;
}

/**
 * 输出一帧。
 *
 * @param force `true` = 不去重（用于"抬起/断开时把 0 报出去"，那一帧必须发）
 */
void emitFrame(Device* d, bool force = false) {
    if (!d) return;
    if (d->useMt && !d->hasSlots) finishTypeAContact(d);
    Contact contacts[kMaxSlots]{};
    int count = collectContacts(d, contacts, kMaxSlots);
    if (!force && frameSame(d, contacts, count)) {
        if (d->useMt && !d->hasSlots) d->typeACount = 0;
        return;
    }
    printf("TOUCH %d", count);
    for (int i = 0; i < count; ++i) {
        int x = normCoord(contacts[i].x, d->xInfo);
        int y = normCoord(contacts[i].y, d->yInfo);
        printf(" %d %d %d", contacts[i].id, x, y);
        d->lastIds[i] = contacts[i].id;
        d->lastXs[i] = x;
        d->lastYs[i] = y;
    }
    printf("\n");
    fflush(stdout);
    d->lastCount = count;
    d->emittedOnce = true;
    if (d->useMt && !d->hasSlots) d->typeACount = 0;
}

void processEvent(Device* d, const input_event& ev) {
    if (!d) return;
    if (ev.type == EV_KEY && ev.code == BTN_TOUCH) {
        d->buttonDown = ev.value != 0;
        /* 抬起:协议 A 的攒列表立刻清掉，否则会留一根"幽灵手指" */
        if (!d->buttonDown && d->useMt && !d->hasSlots) {
            d->typeACurrent = Contact{};
            d->typeACount = 0;
        }
        return;
    }
    if (ev.type == EV_ABS) {
        if (d->useMt) {
            if (d->hasSlots && ev.code == ABS_MT_SLOT) {
                int slot = ev.value;
                if (slot < 0) slot = 0;
                if (slot >= kMaxSlots) slot = kMaxSlots - 1;
                d->currentSlot = slot;
                return;
            }
            Contact* c = d->hasSlots ? &d->slots[d->currentSlot] : &d->typeACurrent;
            if (ev.code == ABS_MT_TRACKING_ID) {
                if (ev.value < 0) {
                    /* 负值 = 这根手指抬起了 */
                    if (d->hasSlots) *c = Contact{};
                    else finishTypeAContact(d);
                } else {
                    if (d->hasSlots) {
                        *c = Contact{};
                        c->active = true;
                        c->id = ev.value;
                    } else {
                        if (c->hasX || c->hasY) finishTypeAContact(d);
                        c->active = true;
                        c->id = ev.value;
                    }
                }
                return;
            }
            if (ev.code == ABS_MT_POSITION_X) {
                c->x = ev.value;
                c->hasX = true;
                /* 没有 TRACKING_ID 的设备只能靠 BTN_TOUCH 判断按没按 */
                if (!d->hasTracking) c->active = d->buttonDown || !d->hasBtnTouch;
                return;
            }
            if (ev.code == ABS_MT_POSITION_Y) {
                c->y = ev.value;
                c->hasY = true;
                if (!d->hasTracking) c->active = d->buttonDown || !d->hasBtnTouch;
                return;
            }
            return;
        }
        /* 单点设备 */
        Contact& c = d->slots[0];
        if (ev.code == ABS_X) {
            c.x = ev.value;
            c.hasX = true;
        } else if (ev.code == ABS_Y) {
            c.y = ev.value;
            c.hasY = true;
        }
        return;
    }
    if (ev.type == EV_SYN) {
        if (ev.code == SYN_MT_REPORT && d->useMt && !d->hasSlots) {
            finishTypeAContact(d);
            return;
        }
        /*
         * ⚠️ 只在 SYN_REPORT 输出，不在每个事件输出。
         * 一次物理动作会来一串轴事件，逐条输出会让 Kotlin 侧
         * 按"半个触点"做命中测试，摇杆会抖。
         */
        if (ev.code == SYN_REPORT) emitFrame(d);
    }
}

/** @return `false` = 设备断了（调用方要重扫） */
bool readEvents(Device* d) {
    input_event events[48];
    for (;;) {
        ssize_t n = read(d->fd, events, sizeof(events));
        if (n > 0) {
            size_t count = static_cast<size_t>(n) / sizeof(input_event);
            for (size_t i = 0; i < count; ++i) processEvent(d, events[i]);
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return true;
        return n != 0;
    }
}

}  // namespace

int main() {
    signal(SIGTERM, onSignal);
    signal(SIGINT, onSignal);
    signal(SIGHUP, onSignal);
    setvbuf(stdout, nullptr, _IOLBF, 0);

    Device device{};
    device.fd = -1;
    long long lastScan = 0;
    /*
     * ⚠️ 只打**一次** `waiting-touch`。
     *
     * 外层的扫描循环每 900ms 跑一次，如果每次都打，Kotlin 侧
     * （哪怕它自己会去重）也会多出一串无意义的行。
     * 这里用标志位保证"进入未就绪状态时说一下"，之后靠
     * `touch-ready` / 具体失败原因来表达变化。
     */
    bool announcedWaiting = false;

    printf("STATUS starting\n");
    fflush(stdout);

    while (!gStop) {
        long long now = nowMs();
        if (device.fd < 0 && now - lastScan >= kScanIntervalMs) {
            scan(&device);
            /* 刚连上时先报一次当前状态（可能是 0 个触点） */
            if (device.fd >= 0) {
                emitFrame(&device, true);
                announcedWaiting = false;
            } else if (!announcedWaiting) {
                /*
                 * ⚠️ 首次没认出触屏时补一条 —— 否则用户在这段时间里
                 * 只看到 `starting`，界面会一直停在"正在启动"，
                 * 分不清"还在找设备"和"helper 卡住了"。
                 *
                 * ⚠️ 放在 `scan` 的具体失败原因**之后**打印是有意的:
                 * 那三条原因更具体（权限/识别/环境），先看到它们更有用；
                 * 这一条只是"常态、别慌"的兜底。
                 */
                printf("STATUS waiting-touch\n");
                fflush(stdout);
                announcedWaiting = true;
            }
            lastScan = now;
        }
        if (device.fd < 0) {
            /* 没有设备:睡一下再重扫，不要空转烧 CPU */
            poll(nullptr, 0, 120);
            continue;
        }
        pollfd pfd{device.fd, POLLIN | POLLERR | POLLHUP, 0};
        int result = poll(&pfd, 1, 120);
        if (result < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (result == 0) continue;
        if (pfd.revents & (POLLERR | POLLHUP)) {
            emitFrame(&device, true);
            printf("STATUS touch-disconnected\n");
            fflush(stdout);
            closeDevice(&device);
            continue;
        }
        if ((pfd.revents & POLLIN) && !readEvents(&device)) {
            emitFrame(&device, true);
            printf("STATUS touch-disconnected\n");
            fflush(stdout);
            closeDevice(&device);
        }
    }

    /* 退出前把"手指都抬起"报出去，否则 Kotlin 侧会留着一个按下的状态 */
    if (device.fd >= 0) {
        for (auto& c : device.slots) c = Contact{};
        device.buttonDown = false;
        emitFrame(&device, true);
    }
    closeDevice(&device);
    printf("STATUS stopped\n");
    fflush(stdout);
    return 0;
}
