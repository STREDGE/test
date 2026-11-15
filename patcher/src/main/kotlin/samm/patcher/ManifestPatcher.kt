package samm.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayOutputStream

/** Что нам нужно знать о приложении из AndroidManifest.xml. */
data class ManifestInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Int?,
    val minSdk: Int?,
    val mainActivity: String?,
    val extractNativeLibs: Boolean?,
    val splitName: String?,
)

object ManifestPatcher {
    // android.R.attr.*
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_EXPORTED = 0x01010010
    private const val ATTR_AUTHORITIES = 0x01010018

    const val BRIDGE_PROVIDER = "samm.bridge.BridgeProvider"

    fun bridgeAuthority(packageName: String) = "$packageName.samm.bridge"

    fun read(manifestBytes: ByteArray): ManifestInfo {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        val split = m.manifestElement?.let { el ->
            el.attributes.asSequence().firstOrNull { it.name == "split" }?.valueAsString
        }
        return ManifestInfo(
            packageName = m.packageName,
            versionName = m.versionName,
            versionCode = m.versionCode,
            minSdk = m.minSdkVersion,
            mainActivity = m.mainActivityClassName?.let { qualify(m.packageName, it) },
            extractNativeLibs = m.isExtractNativeLibs,
            splitName = split,
        )
    }

    /**
     * Регистрирует наш ContentProvider-мост в манифесте игры.
     * Повторный вызов ничего не дублирует.
     */
    fun addBridgeProvider(manifestBytes: ByteArray): ByteArray {
        val m = AndroidManifestBlock.load(manifestBytes.inputStream())
        val app: ResXmlElement = m.applicationElement ?: error("В манифесте нет <application>")
        @Suppress("UNCHECKED_CAST")
        val providers = (app.getElements("provider") as Iterator<ResXmlElement>).asSequence()
        val already = providers.any { p ->
            p.attributes.asSequence().any { it.nameId == ATTR_NAME && it.valueAsString == BRIDGE_PROVIDER }
        }
        if (!already) {
            val provider = app.newElement("provider")
            provider.getOrCreateAndroidAttribute("name", ATTR_NAME).setValueAsString(BRIDGE_PROVIDER)
            provider.getOrCreateAndroidAttribute("exported", ATTR_EXPORTED).setValueAsBoolean(true)
            provider.getOrCreateAndroidAttribute("authorities", ATTR_AUTHORITIES).setValueAsString(bridgeAuthority(m.packageName))
        }
        m.refreshFull()
        val out = ByteArrayOutputStream()
        m.writeBytes(out)
        return out.toByteArray()
    }

    private fun qualify(pkg: String, cls: String): String = when {
        cls.startsWith(".") -> pkg + cls
        !cls.contains('.') -> "$pkg.$cls"
        else -> cls
    }
}
