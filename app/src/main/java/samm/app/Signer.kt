package samm.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * Подпись пропатченной игры.
 * Ключ создаётся один раз в Android Keystore этого телефона и никуда не уходит.
 * Важно: если удалить менеджер, ключ пропадёт, и следующий патч потребует снова удалить игру.
 */
object Signer {
    private const val ALIAS = "samm-game-signing"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(): Pair<PrivateKey, X509Certificate> {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 50) }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(java.security.spec.RSAKeyGenParameterSpec(2048, java.security.spec.RSAKeyGenParameterSpec.F4))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=SA Mod Manager (device key)"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start.time)
                .setCertificateNotAfter(end.time)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate as X509Certificate)
    }

    fun sign(unsigned: File, signed: File, minSdk: Int) {
        val (key, cert) = ensureKey()
        val config = ApkSigner.SignerConfig.Builder("SAMM", key, listOf(cert)).build()
        val builder = ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(minSdk < 24)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
        // Выравнивание .so под 16 КБ страницы, если версия apksig это умеет.
        try {
            builder.javaClass.getMethod("setLibraryPageAlignmentBytes", Int::class.javaPrimitiveType).invoke(builder, 16384)
        } catch (_: Throwable) {
        }
        builder.build().sign()
    }

    /** SHA-256 сертификата, которым подписан сам менеджер (для проверки в мосту игры). */
    @Suppress("DEPRECATION")
    fun managerCertSha256(ctx: Context): String {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures!![0]
        }
        return MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
