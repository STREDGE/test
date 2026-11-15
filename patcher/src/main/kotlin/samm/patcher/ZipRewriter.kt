package samm.patcher

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/** Новое или заменяющее содержимое записи zip. */
class ZipPayload(val bytes: ByteArray, val stored: Boolean)

/**
 * Пересобирает zip (APK):
 *  - нетронутые записи копируются как есть, без перепаковки (сохраняется метод сжатия);
 *  - записи из [replace] заменяются, из [add] — добавляются;
 *  - старая подпись (META-INF/*.SF, *.RSA, …) и [drop] выкидываются.
 * Выравнивание и подпись делает потом apksig.
 */
object ZipRewriter {

    fun isSignatureFile(name: String): Boolean {
        val u = name.uppercase()
        if (!u.startsWith("META-INF/")) return false
        val rest = u.removePrefix("META-INF/")
        if (rest.contains('/')) return false
        return rest == "MANIFEST.MF" || rest.endsWith(".SF") || rest.endsWith(".RSA") ||
            rest.endsWith(".DSA") || rest.endsWith(".EC") || rest.startsWith("SIG-")
    }

    fun rewrite(
        input: File,
        output: File,
        replace: Map<String, ByteArray> = emptyMap(),
        add: Map<String, ZipPayload> = emptyMap(),
        drop: Set<String> = emptySet(),
    ) {
        ZipFile.builder().setFile(input).get().use { zip ->
            val existing = zip.entries.asSequence().map { it.name }.toSet()
            val clash = add.keys.filter { it in existing && it !in replace }
            require(clash.isEmpty()) { "Записи уже есть в ${input.name}: $clash" }

            ZipArchiveOutputStream(output).use { out ->
                for (e in zip.entries) {
                    val name = e.name
                    if (name in drop || isSignatureFile(name) || name == "stamp-cert-sha256") continue
                    val newData = replace[name]
                    if (newData == null) {
                        out.addRawArchiveEntry(ZipArchiveEntry(e).also { it.extra = ByteArray(0) }, zip.getRawInputStream(e))
                    } else {
                        writeEntry(out, name, newData, stored = e.method == ZipEntry.STORED)
                    }
                }
                for ((name, p) in add) writeEntry(out, name, p.bytes, p.stored)
            }
        }
    }

    private fun writeEntry(out: ZipArchiveOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val entry = ZipArchiveEntry(name)
        entry.time = DOS_EPOCH
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = CRC32().also { it.update(data) }.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        out.putArchiveEntry(entry)
        out.write(data)
        out.closeArchiveEntry()
    }

    /** 1981-01-01, как пишут Android-инструменты: стабильные байты при повторной сборке. */
    private const val DOS_EPOCH = 347155200000L
}
