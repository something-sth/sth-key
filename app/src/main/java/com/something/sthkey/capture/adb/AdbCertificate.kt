package com.something.sthkey.capture.adb

import android.content.Context
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import com.something.sthkey.core.log.AppLog
import java.io.ByteArrayInputStream
import java.io.File
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.random.Random

/**
 * 给 ADB TLS 用的自签客户端证书。
 *
 * ADB 的「无线调试配对」走 TLS 1.3，客户端必须带一张证书；
 * adbd 只关心证书里的公钥，不校验证书链，所以自签即可。
 *
 * ⚠️ 不用 BouncyCastle：它会让 APK 涨好几 MB。这里用
 * `sun-security-android`（很小，GPL-2.0 + classpath 例外）生成 X.509。
 * 证书按公钥缓存，密钥变了才重新生成。
 */
internal object AdbCertificate {

    private const val TAG = "AdbCert"
    private const val CERT_FILE = "adb.crt"

    fun load(context: Context, privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val file = File(dir, CERT_FILE)
        if (file.isFile) {
            runCatching {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(file.readBytes())) as X509Certificate
                if (cert.publicKey == publicKey) return cert
            }.onFailure {
                AppLog.w(TAG, "证书读取失败，重新生成：${it.javaClass.simpleName}")
            }
        }

        val cert = generate(privateKey, publicKey)
        file.writeBytes(cert.encoded)
        AppLog.i(TAG, "已生成 ADB 客户端证书（CN=sthkey）")
        return cert
    }

    private fun generate(privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
        val name = X500Name("CN=sthkey")

        val extensions = CertificateExtensions()
        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
        )
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(now, notAfter))

        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random.nextInt(1, Int.MAX_VALUE)))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get("SHA256withRSA")))
        info.set("subject", CertificateSubjectName(name))
        info.set("key", CertificateX509Key(publicKey))
        info.set("validity", CertificateValidity(now, notAfter))
        info.set("issuer", CertificateIssuerName(name))
        info.set("extensions", extensions)

        val cert = X509CertImpl(info)
        cert.sign(privateKey, "SHA256withRSA")
        return cert
    }

    fun asCertificate(value: X509Certificate): Certificate = value
}
