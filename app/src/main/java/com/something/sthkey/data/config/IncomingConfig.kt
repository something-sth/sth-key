package com.something.sthkey.data.config

import android.content.Intent
import android.net.Uri

/**
 * 从外部传进来的 Intent 里取出"待导入的配置包"。
 *
 * ============================================================
 * 为什么要抽成独立的纯函数
 * ============================================================
 * 这段逻辑的输入是**别的应用**发来的 Intent，而各家实现差异很大：
 * 有的用 `ACTION_VIEW`、有的用 `ACTION_SEND`；`EXTRA_STREAM` 有的塞
 * `Uri`、有的塞 `ArrayList<Uri>`；`ACTION_VIEW` 有的把 Uri 放 `data`、
 * 有的也塞进 extra。
 *
 * 取不到 Uri 的表现是"点了没反应" —— 在真机上只能靠一个个应用去试，
 * 而且失败时连日志都没有。所以要能穷举测试。
 *
 * ============================================================
 * ⚠️ 为什么把"提取"与"判断"分开、而且只吃 String
 * ============================================================
 * 本项目的本地单测**没有 Robolectric**（也不能加依赖：构建是离线的），
 * 而 `android.net.Uri` 与 `android.content.Intent` 在那里都是空壳 ——
 * 连 `Uri.parse` 都会抛 `Method ... not mocked`。
 *
 * 所以判断层 [pickUri] **完全不碰 Android 类型**，只吃
 * "action / data / stream / streamList" 四个 `String?`。
 * `Uri` 只在最外层 [extract] 出现，且只做一次 `toString()`。
 *
 * 于是"哪条分支取哪个字段"这个真正容易写错的部分可以完整穷举测试，
 * 而 Android 那层只剩下几行无分支的读取。
 */
object IncomingConfig {

    /**
     * 一次外部导入请求。
     *
     * @param uri 配置包的位置
     * @param fromShare `true` 表示来自"分享"（`ACTION_SEND`），
     *   `false` 表示来自"用本应用打开"（`ACTION_VIEW`）。
     *   两者都会导入，只是确认框上的措辞不同。
     */
    data class Request(
        val uri: Uri,
        val fromShare: Boolean,
    )

    /** [pickUri] 的结果：只是"挑出了哪个位置"，还不是 Android 的 `Uri` */
    data class Picked(val location: String, val fromShare: Boolean)

    /**
     * 纯逻辑：从几个普通值里挑出要导入的位置。
     *
     * ⚠️ 参数与返回值**刻意用 `String`**，见文件头的说明 ——
     * 用 `Uri` 的话这个函数就没法在本地单测里跑了。
     *
     * @param action Intent 的 action
     * @param data `Intent.data`（`ACTION_VIEW` 走这里）
     * @param stream `EXTRA_STREAM` 的**单值**形态
     * @param streamList `EXTRA_STREAM` 的**列表**形态（只取第一个）
     */
    fun pickUri(
        action: String?,
        data: String?,
        stream: String?,
        streamList: List<String>?,
    ): Picked? = when (action) {
        /*
         * ⚠️ 这两个字面量**不引用 `Intent.ACTION_*`**：
         * 那个类在本地单测里是空壳，读它的静态字段会抛
         * `Method ... not mocked`，于是这些测试根本跑不起来。
         *
         * 值就是它们在 Android 里的真实取值（"android.intent.action.SEND"
         * / "...VIEW"），写死是安全的 —— 这两个常量从 API 1 起就没变过。
         */
        ACTION_SEND -> {
            /*
             * ⚠️ `ACTION_SEND` 用 `EXTRA_STREAM`，**不看 `data`**。
             * 两个 action 的约定完全不同，混用会取不到。
             */
            val location = stream ?: streamList?.firstOrNull()
            location?.let { Picked(it, fromShare = true) }
        }

        ACTION_VIEW -> {
            /*
             * ⚠️ `ACTION_VIEW` 用 `data`。
             *
             * 但**也**退一步看 extra：确实有应用在 VIEW 时把 Uri
             * 同时塞进 extra（不规范但存在）。多看一眼没有代价。
             */
            val location = data ?: stream ?: streamList?.firstOrNull()
            location?.let { Picked(it, fromShare = false) }
        }

        /*
         * 其余 action（`MAIN`/`LAUNCHER` 等）一律不处理。
         * 返回 null 而不是抛异常：正常的应用启动天天在走这条路。
         */
        else -> null
    }

    /**
     * 从 Intent 里取出待导入的配置包；没有则返回 null。
     *
     * ⚠️ **不校验 MIME**。看起来应该校验（"只收 zip"），但那会导致
     * 一部分来源被静默丢掉 —— 各家应用报的 MIME 五花八门，
     * 而收下之后我们会验 manifest，不是我们的包会明确报错。
     * **宁可多收一个再报错，也不要静默不响应**：
     * 后者在用户看来就是"这个功能时好时坏"。
     */
    fun extract(intent: Intent?): Request? {
        if (intent == null) return null
        val picked = pickUri(
            action = intent.action,
            data = intent.data?.toString(),
            stream = streamOf(intent)?.toString(),
            streamList = streamListOf(intent)?.map { it.toString() },
        ) ?: return null

        /*
         * 唯一的 `Uri.parse`：拿到的一定是 Android 给的真实 Uri 字符串
         * （content:// 或 file://），解析不会失败。
         */
        return Request(
            uri = Uri.parse(picked.location),
            fromShare = picked.fromShare,
        )
    }

    /** 真实的 action 取值；见 [pickUri] 里关于"不引用 Intent 常量"的说明 */
    private const val ACTION_SEND = "android.intent.action.SEND"
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    /**
     * 取 `EXTRA_STREAM` 的单值形态。
     *
     * ⚠️ `getParcelableExtra` 在 API 33 起被废弃（要传 `Class`），
     * 但新重载在旧系统上不存在，所以两个分支都得留。这不是"懒得改"，
     * 而是为了兼容 API 30（本项目 minSdk）。
     */
    private fun streamOf(intent: Intent): Uri? = if (ANDROID_13_OR_NEWER) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    }

    /**
     * 取 `EXTRA_STREAM` 的列表形态。
     *
     * ⚠️ 官方约定 `EXTRA_STREAM` 是单个 Uri，但**确实有应用**
     * （哪怕只分享一个文件）塞的是 `ArrayList`。不认这种形态的话，
     * 表现就是"某些应用分享了没反应"。
     *
     * ⚠️ 显式写出类型参数 `ArrayList<Uri>`：旧版那个重载
     * `getParcelableArrayListExtra(String)` 返回裸 `ArrayList`，
     * 靠赋值目标推不出元素类型（编译期会报 "Not enough information to
     * infer type argument"）。
     */
    private fun streamListOf(intent: Intent): List<Uri>? = if (ANDROID_13_OR_NEWER) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    }

    /** API 33 = Android 13 = Tiramisu */
    private val ANDROID_13_OR_NEWER =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
}
