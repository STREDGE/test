package samm.modcore.mods

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Чтение архивов модов: zip, 7z, rar (rar5 junrar не умеет — вернёт понятную ошибку).
 */
object ModArchive {

    enum class Kind { ZIP, SEVEN_Z, RAR, UNKNOWN }

    fun detect(file: File): Kind {
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Kind.ZIP
        if (n >= 6 && head.copyOf(6).contentEquals(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))) return Kind.SEVEN_Z
        if (n >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "Rar!") return Kind.RAR
        return Kind.UNKNOWN
    }

    /** Список путей файлов в архиве. */
    fun list(file: File): List<String> {
        val out = ArrayList<String>()
        forEach(file) { path, _ -> out.add(path) }
        return out
    }

    /** Обойти все файлы архива. Поток действителен только внутри [action]. */
    fun forEach(file: File, action: (path: String, data: InputStream) -> Unit) {
        when (detect(file)) {
            Kind.ZIP -> openZip(file).use { zip ->
                for (e in zip.entries) {
                    if (e.isDirectory) continue
                    zip.getInputStream(e).use { action(e.name, it) }
                }
            }
            Kind.SEVEN_Z -> SevenZFile.builder().setFile(file).get().use { sz ->
                for (e in sz.entries) {
                    if (e.isDirectory || !e.hasStream()) continue
                    sz.getInputStream(e).use { action(e.name, it) }
                }
            }
            Kind.RAR -> try {
                Archive(file).use { rar ->
                    for (h in rar.fileHeaders) {
                        if (h.isDirectory) continue
                        rar.getInputStream(h).use { action(h.fileName, it) }
                    }
                }
            } catch (e: com.github.junrar.exception.UnsupportedRarV5Exception) {
                throw IOException("Архив RAR5 не поддерживается. Пересохраните его в ZIP или 7z.", e)
            }
            Kind.UNKNOWN -> throw IOException("Неизвестный формат архива: ${file.name}")
        }
    }

    /**
     * Имена в zip-архивах с русских сайтов часто в CP866/CP1251 без флага UTF-8.
     * Пробуем UTF-8, а если имена битые — CP866.
     */
    private fun openZip(file: File): ZipFile {
        val utf = ZipFile.builder().setFile(file).setCharset(Charsets.UTF_8).get()
        val broken = utf.entries.asSequence().any { it.name.contains('�') }
        if (!broken) return utf
        utf.close()
        return ZipFile.builder().setFile(file).setCharset(charset("CP866")).get()
    }
}
