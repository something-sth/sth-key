package com.something.sthkey.data.incoming

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * 外部「打开方式 / 分享」进来的一次导入请求。
 *
 * ============================================================
 * ⚠️ 与旧 `IncomingConfig` 的区别:**多文件**
 * ============================================================
 * 旧实现只取第一个（配置包一次也就一个）。但字体不一样:
 * 用户很可能一次选 3 个字体一起分享。
 *
 * ⚠️ 多文件**不做整批回滚**:字体之间没有依赖关系，
 * 一个失败不该把已成功的撤掉（那对用户来说是"白折腾一遍"）。
 */
object IncomingFile {

    /** 一次请求；`uris` 至少一个 */
    data class Request(
        val uris: List<Uri>,
        val fromShare: Boolean,
    ) {
        val isMultiple: Boolean get() = uris.size > 1
        val first: Uri get() = uris.first()
    }

    /** [pickUris] 的结果：只是"挑出了哪些位置"，还不是 Android 的 `Uri` */
    data class Picked(val locations: List<String>, val fromShare: Boolean)

    /**
     * 纯逻辑：从几个普通值里挑出要导入的位置。
     *
     * ⚠️ 参数与返回值**刻意用 `String`**（见旧 `IncomingConfig` 的说明）:
     * 用 `Uri` 的话这个函数就没法在本地单测里跑了。
     *
     * @param data `Intent.data`（`ACTION_VIEW` 走这里）
     * @param stream `EXTRA_STREAM` 的**单值**形态
     * @param streamList `EXTRA_STREAM` 的**列表**形态
     */
    fun pickUris(
        action: String?,
        data: String?,
        stream: String?,
        streamList: List<String>?,
    ): Picked? = when (action) {
        /*
         * ⚠️ 这两个字面量**不引用 `Intent.ACTION_*`**:
         * 那个类在本地单测里是空壳，读它的静态字段会抛
         * `Method ... not mocked`，于是这些测试根本跑不起来。
         * 值就是它们在 Android 里的真实取值，从 API 1 起没变过。
         */
        ACTION_SEND -> {
            /*
             * ⚠️ `ACTION_SEND` 用 `EXTRA_STREAM`，**不看 `data`**（两个 action 约定不同）。
             * 单值优先，其次列表 —— 列表形态在"分享多个文件"里出现。
             */
            val locations = buildList {
                stream?.let { add(it) }
                streamList?.let { addAll(it) }
            }.distinct()
            locations.takeIf { it.isNotEmpty() }?.let { Picked(it, fromShare = true) }
        }

        ACTION_VIEW -> {
            /*
             * ⚠️ `ACTION_VIEW` 用 `data`；但**也**退一步看 extra
             * （确实有应用在 VIEW 时把 Uri 塞进 extra，不规范但存在）。
             *
             * ⚠️ VIEW 只有**一个**文件:系统不会用 VIEW 打开多个。
             */
            val location = data ?: stream ?: streamList?.firstOrNull()
            location?.let { Picked(listOf(it), fromShare = false) }
        }

        /* 其余 action（MAIN/LAUNCHER 等）不处理 */
        else -> null
    }

    /**
     * 从 Intent 里取出待导入的文件；没有则返回 null。
     *
     * ⚠️ **不校验 MIME**。看起来应该校验（"只收 zip"），但那会让一部分来源被静默丢掉 ——
     * 各家应用报的 MIME 五花八门，收下之后我们会**按内容嗅探**，
     * 不认识的文件会明确报错。宁可多收一个再报错，也不要静默不响应。
     */
    fun extract(intent: Intent?): Request? {
        if (intent == null) return null

        val picked = pickUris(
            action = intent.action,
            data = intent.data?.toString(),
            stream = streamOf(intent)?.toString(),
            streamList = streamListOf(intent)?.map { it.toString() },
        ) ?: return null

        /* 唯一的 Uri.parse：拿到的一定是 Android 给的真实 Uri 字符串 */
        return Request(
            uris = picked.locations.map { Uri.parse(it) },
            fromShare = picked.fromShare,
        )
    }

    /** 真实的 action 取值；见 [pickUris] 里关于"不引用 Intent 常量"的说明 */
    private const val ACTION_SEND = "android.intent.action.SEND"
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    /** `EXTRA_STREAM` 的单值形态 */
    private fun streamOf(intent: Intent): Uri? = if (ANDROID_13_OR_NEWER) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    }

    /**
     * `EXTRA_STREAM` 的列表形态。
     *
     * ⚠️ 官方约定 `EXTRA_STREAM` 是单个 Uri，但**确实有应用**塞 `ArrayList<Uri>`
     * （尤其国产 ROM 的相册/文件管理）。两条都取一遍，谁也不碍谁。
     */
    private fun streamListOf(intent: Intent): List<Uri>? = if (ANDROID_13_OR_NEWER) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    }

    private val ANDROID_13_OR_NEWER: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
