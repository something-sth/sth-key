package com.something.sthkey.domain.capture

/**
 * 当前环境具备哪些采集能力。
 *
 * 这是"能力快照"，不是服务状态：它只回答"这台设备上是否具备某种采集手段"，
 * 与采集服务是否正在运行无关。
 *
 * 目前只探测 root：Shizuku 的可用性要等接入 SDK 之后才有意义
 * （没有 SDK 时任何"已安装/正在运行"的判断都不可靠）。
 * 结构上仍然把两种能力分开，是为了接入 Shizuku 时只填字段、不改调用方。
 */
data class CaptureCapabilities(
    val rootAvailable: Boolean = false,
    /** 探测过程中的补充信息，直接展示在引导页 / 调试页 */
    val rootDetail: String = "未检测",
    /**
     * Shizuku 侧是否就绪。
     *
     * 恒为 false（未接入 SDK），保留字段是为了以后填值。
     * 注意它不表示"用户没授权"，而是"我们还没能力判断"。
     */
    val shizukuReady: Boolean = false,
    /**
     * **无障碍服务**是否已启用并连着。
     *
     * ============================================================
     * ⚠️ 它与前两项**性质不同**
     * ============================================================
     * root / Shizuku 是"特权"，拿到之后**什么都能读**；
     * 而无障碍是"降级通道"——**只能读键盘**（见 [KeyMode.keyboardOnly]）。
     *
     * ⚠️ 所以 [anyAvailable] 把它算作"可用"，但界面上**必须**另外说明
     * 它的能力边界，不能让用户以为"有障碍 = 能读全"。
     *
     * ⚠️ 判据是"服务真的连上了"，不是"设置里那串已启用列表里有我们"
     * —— 后者只说明用户点过开关，服务可能还没起来（或被系统杀了）。
     */
    val accessibilityReady: Boolean = false,
) {
    val anyAvailable: Boolean
        get() = rootAvailable || shizukuReady || accessibilityReady

    companion object {
        /** 尚未探测时的初始值 */
        val UNKNOWN = CaptureCapabilities()
    }
}

/**
 * 采集模式解析结果。
 *
 * @param mode    实际要使用的模式（一定是 ROOT 或 SHIZUKU，不会是 AUTO）
 * @param fellBack 是否由自动模式的回退逻辑得出
 * @param reason  解析依据，调试页展示 + 日志用
 */
data class ResolvedCaptureMode(
    val mode: KeyMode,
    val fellBack: Boolean,
    val reason: String,
)

/**
 * 把用户选择的模式解析为实际采集模式。
 *
 * 规则：
 * - 显式选择 root / shizuku / 无障碍时**不做**能力回退：用户既然手动指定，
 *   就要看到真实失败原因，悄悄换路径会让调试变得非常困难。
 * - 自动模式按 **root → shizuku → 无障碍** 逐级挑第一个可用的。
 * - 都不可用时仍然返回一个模式，但 [ResolvedCaptureMode.reason] 会说明情况，
 *   由采集层在真正启动时报错。
 *
 * ============================================================
 * ⚠️⚠️ 无障碍为什么排在**最后**
 * ============================================================
 * 它的能力**弱于**另外两条（只能读键盘，读不到鼠标/手柄/触摸）——
 * 见 [KeyMode.keyboardOnly]。
 *
 * ⚠️ 所以自动模式里它是一个**降级项**:只有在完全没有特权时才用。
 * 有 root 的机器不该因为"无障碍也开着"就退化成只能读键盘 ——
 * 那会让用户觉得"升级之后手柄不亮了"。
 *
 * ⚠️ 注意：这里的判断只基于**授权/可用性**。root 可用但读不到设备这类
 * "运行时失败"由采集层在启动失败后重新解析（见 [ResolvedCaptureMode.fellBack]）。
 */
fun resolveCaptureMode(
    requested: KeyMode,
    capabilities: CaptureCapabilities,
): ResolvedCaptureMode = when (requested) {
    KeyMode.ROOT -> ResolvedCaptureMode(
        mode = KeyMode.ROOT,
        fellBack = false,
        reason = if (capabilities.rootAvailable) "手动指定 root" else "手动指定 root，但当前未检测到 root",
    )

    KeyMode.SHIZUKU -> ResolvedCaptureMode(
        mode = KeyMode.SHIZUKU,
        fellBack = false,
        reason = if (capabilities.shizukuReady) {
            "手动指定 Shizuku"
        } else {
            "手动指定 Shizuku（尚未接入，将在采集层接入后生效）"
        },
    )

    KeyMode.ACCESSIBILITY -> ResolvedCaptureMode(
        mode = KeyMode.ACCESSIBILITY,
        fellBack = false,
        reason = if (capabilities.accessibilityReady) {
            "手动指定无障碍（只支持键盘按键）"
        } else {
            "手动指定无障碍，但无障碍服务尚未开启"
        },
    )

    KeyMode.AUTO -> when {
        capabilities.rootAvailable ->
            ResolvedCaptureMode(KeyMode.ROOT, fellBack = false, reason = "自动：检测到 root，优先使用 root")

        capabilities.shizukuReady ->
            ResolvedCaptureMode(
                KeyMode.SHIZUKU,
                fellBack = true,
                reason = "自动：root 不可用，回退到 Shizuku",
            )

        capabilities.accessibilityReady ->
            ResolvedCaptureMode(
                KeyMode.ACCESSIBILITY,
                fellBack = true,
                /* ⚠️ 把"只能读键盘"写进原因里 —— 用户会在这里看到能力变弱了 */
                reason = "自动：没有 root 与 Shizuku，回退到无障碍（只能读键盘）",
            )

        else -> ResolvedCaptureMode(
            KeyMode.ROOT,
            fellBack = false,
            reason = "自动：未检测到 root，纯 Shizuku 采集将在采集层接入后可用",
        )
    }
}
