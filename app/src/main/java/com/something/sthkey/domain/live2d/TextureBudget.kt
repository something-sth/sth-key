package com.something.sthkey.domain.live2d

/**
 * 贴图**降采样倍数**的计算（纯函数，可单测）。
 *
 * ============================================================
 * ⚠️ 为什么需要降采样
 * ============================================================
 * 实测样本 `鸣潮卡提希娅最牛逼键盘猫模型` 的贴图是:
 *
 * | 文件 | 尺寸 | 解码后（RGBA） |
 * |---|---|---|
 * | `texture_00.png` | **8192 × 8192** | 256 MB |
 * | `texture_01.png` | 8192 × 4096 | 128 MB |
 *
 * 单套 **96 M 像素 ≈ 384 MB**。两个问题同时存在:
 *
 * 1. **超过 GL 的纹理边长上限** —— 很多手机 `GL_MAX_TEXTURE_SIZE = 4096`，
 *    8192 的纹理**上传直接失败**，表现为白屏或只有部分部件；
 * 2. **显存/内存直接爆** —— 384 MB 在任何手机上都不该给一个悬浮窗。
 *
 * ============================================================
 * ⚠️ 为什么是"统一按 2 的幂"而不是"随便缩到合适大小"
 * ============================================================
 * Live2D 的 UV 坐标是**归一化**的（0~1），所以只要**整张图等比缩放**，
 * UV 就不用改、model3 也不用改。
 *
 * ⚠️ 但 `Bitmap.inSampleSize` 只接受 2 的幂（内部会向下取整到 2 的幂），
 * 所以倍数必须是 2、4、8… —— 这也正好让"等比"严格成立。
 */
object TextureBudget {

    /**
     * 普通画质下的总像素预算。
     *
     * ⚠️ 取值照 Axon 的 `MAX_TOTAL_TEXTURE_PIXELS`（48 M 像素 ≈ 192 MB RGBA）:
     * 它在这个生态里跑了很久，比我们自己拍一个数字靠谱。
     */
    const val NORMAL_PIXELS = 48_000_000L

    /**
     * 单张纹理的最大边长。
     *
     * ⚠️ 4096 是绝大多数手机 `GL_MAX_TEXTURE_SIZE` 的下限值:
     * 超过它的纹理在那些机器上**上传不上**（不是"糊"，是"没有"）。
     */
    const val MAX_EDGE = 4096

    /**
     * 算出该用多大的降采样倍数。
     *
     * @param sizes 每张贴图的**原始**宽高（同一套模型的全部贴图）
     * @param budgetPixels 总像素预算
     * @return 1 = 不需要降采样；否则是 2 的幂
     */
    fun sampleFor(sizes: List<Pair<Int, Int>>, budgetPixels: Long = NORMAL_PIXELS): Int {
        if (sizes.isEmpty()) return 1

        val totalPixels = sizes.sumOf { (width, height) ->
            width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
        }
        val largestEdge = sizes.maxOf { (width, height) -> maxOf(width, height) }

        /* ① 总像素超标 → 整组一起降 */
        var budgetSample = 1
        while (budgetPixels > 0 && totalPixels / (budgetSample.toLong() * budgetSample) > budgetPixels) {
            budgetSample *= 2
        }

        /* ② 边长超标 → 至少降到单张能上传 */
        var edgeSample = 1
        while (largestEdge / edgeSample > MAX_EDGE) {
            edgeSample *= 2
        }

        /* ⚠️ 取两者较大值:只满足一个条件会出问题（边长降了但总量还爆，或反之） */
        return maxOf(budgetSample, edgeSample)
    }
}
