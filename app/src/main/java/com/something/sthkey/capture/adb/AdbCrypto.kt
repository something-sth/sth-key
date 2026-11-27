package com.something.sthkey.capture.adb

import android.content.Context
import android.os.Build
import android.util.Base64
import com.something.sthkey.core.log.AppLog
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB 客户端密钥。
 *
 * ADB 的认证方式：adbd 发来 20 字节 token，客户端用 RSA 私钥做 SHA1 签名回过去；
 * 公钥还要按 adbd 自己的 524 字节结构发过去（见 [androidPublicKey]），
 * 设备弹「允许 USB 调试」时，系统存的就是这份公钥。
 *
 * ⚠️ 私钥只存应用私有目录，不进日志、不导出（只导出公钥给用户加到 adb_keys）。
 */
internal class AdbCrypto private constructor(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
) {
    private val modulus: BigInteger = (publicKey as java.security.interfaces.RSAPublicKey).modulus
    private val exponent: BigInteger = (publicKey as java.security.interfaces.RSAPublicKey).publicExponent

    /**
     * adbd 的 RSAPublicKey 结构（524 字节，全部小端）：
     * ```
     * uint32 len;      // 524
     * uint32 n0inv;    // -1/n mod 2^32
     * uint8  n[256];   // modulus
     * uint8  rr[256];  // 2^2048 mod n
     * uint32 exponent;
     * ```
     */
    fun androidPublicKey(): ByteArray {
        val size = 256
        val n = littleEndian(modulus, size)
        val rr = littleEndian(BigInteger.ONE.shiftLeft(2048).mod(modulus), size)
        val n0inv = modulus.mod(BigInteger.ONE.shiftLeft(32))
            .modInverse(BigInteger.ONE.shiftLeft(32))
            .negate()
            .mod(BigInteger.ONE.shiftLeft(32))
        val e = littleEndian(exponent, 4)

        val out = ByteArray(4 + 4 + 256 + 256 + 4)
        writeIntLe(out, 0, 524)
        writeIntLe(out, 4, n0inv.toLong())
        n.copyInto(out, 8)
        rr.copyInto(out, 8 + 256)
        e.copyInto(out, 8 + 512)
        return out
    }

    /** 给用户看的 authorized_keys 行（不含注释也行，adbd 只读前面 base64）。 */
    fun authorizedKeyLine(): String =
        Base64.encodeToString(androidPublicKey(), Base64.NO_WRAP) +
            " sthkey@${Build.MODEL}"

    /** adbd 的 AUTH 签名：RSA PKCS#1 v1.5 + SHA1。 */
    fun signToken(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(token)
        return signature.sign()
    }

    companion object {
        private const val TAG = "AdbCrypto"
        private const val PRIVATE_FILE = "adbkey.pk8"
        private const val PUBLIC_FILE = "adbkey.pub"

        fun load(context: Context): AdbCrypto {
            val dir = File(context.filesDir, "adb").apply { mkdirs() }
            val privateFile = File(dir, PRIVATE_FILE)
            val publicFile = File(dir, PUBLIC_FILE)
            if (privateFile.isFile && publicFile.isFile) {
                runCatching {
                    val keyFactory = KeyFactory.getInstance("RSA")
                    val private = keyFactory.generatePrivate(
                        PKCS8EncodedKeySpec(privateFile.readBytes()),
                    )
                    val public = keyFactory.generatePublic(
                        X509EncodedKeySpec(publicFile.readBytes()),
                    )
                    return AdbCrypto(private, public)
                }.onFailure {
                    AppLog.w(TAG, "ADB 密钥读取失败，重新生成：${it.javaClass.simpleName}")
                }
            }

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            privateFile.writeBytes(pair.private.encoded)
            publicFile.writeBytes(pair.public.encoded)
            AppLog.i(TAG, "已生成 ADB 客户端密钥（RSA 2048）")
            return AdbCrypto(pair.private, pair.public)
        }

        private fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val raw = value.toByteArray()
            /* BigInteger 是**大端**，还可能多一个符号 0 字节 */
            val bigEndian = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
            val out = ByteArray(size)
            for (i in bigEndian.indices) {
                out[bigEndian.size - 1 - i] = bigEndian[i]
            }
            return out
        }

        private fun writeIntLe(out: ByteArray, offset: Int, value: Long) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
    }
}
