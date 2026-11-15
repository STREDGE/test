package samm.modcore.img

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запись в каталоге IMG-архива.
 * offset и size — в секторах по [ImgArchive.SECTOR] байт.
 */
data class ImgEntry(
    val name: String,
    val offsetSectors: Long,
    val sizeSectors: Int,
) {
    val offsetBytes: Long get() = offsetSectors * ImgArchive.SECTOR
    val sizeBytes: Long get() = sizeSectors.toLong() * ImgArchive.SECTOR
}

/**
 * Редактор IMG-архивов формата VER2 (gta3.img, gta_int.img, player.img в GTA SA).
 *
 * Изменения пишутся прямо в файл:
 *  - замена файла того же или меньшего размера — на место старого;
 *  - больший файл или новый — в конец архива;
 *  - если каталогу не хватает места для новой записи, файлы из начала архива переносятся в конец.
 * Освободившееся место не переиспользуется — для этого есть [rebuild].
 * Каталог записывается на диск в [flush] / [close].
 */
class ImgArchive private constructor(
    val file: File,
    private val raf: RandomAccessFile,
    entries: List<ImgEntry>,
    private val readOnly: Boolean,
) : Closeable {

    private val list: MutableList<ImgEntry> = entries.toMutableList()
    private var dirty = false

    val entries: List<ImgEntry> get() = list.toList()

    fun find(name: String): ImgEntry? = list.firstOrNull { it.name.equals(name, ignoreCase = true) }

    operator fun contains(name: String): Boolean = find(name) != null

    fun read(name: String): ByteArray {
        val e = find(name) ?: throw NoSuchElementException("В архиве нет файла $name")
        return read(e)
    }

    fun read(entry: ImgEntry): ByteArray {
        require(entry.sizeBytes <= Int.MAX_VALUE) { "Файл ${entry.name} слишком большой" }
        val buf = ByteArray(entry.sizeBytes.toInt())
        raf.seek(entry.offsetBytes)
        raf.readFully(buf)
        return buf
    }

    fun extract(entry: ImgEntry, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var left = entry.sizeBytes
        raf.seek(entry.offsetBytes)
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Архив обрезан: ${entry.name}")
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Добавить файл или заменить существующий с тем же именем. */
    fun put(name: String, data: ByteArray) = put(name, data.inputStream(), data.size.toLong())

    fun put(name: String, input: InputStream, length: Long) {
        checkWritable()
        validateName(name)
        val sectors = sectorsFor(length)
        val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx >= 0) {
            val old = list[idx]
            val offset = if (sectors <= old.sizeSectors) old.offsetSectors else endSector()
            writeData(offset, input, length, sectors)
            list[idx] = old.copy(offsetSectors = offset, sizeSectors = sectors)
        } else {
            ensureDirectoryRoom(list.size + 1)
            val offset = endSector(list.size + 1)
            writeData(offset, input, length, sectors)
            list.add(ImgEntry(name, offset, sectors))
        }
        dirty = true
    }

    fun delete(name: String): Boolean {
        checkWritable()
        val removed = list.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) dirty = true
        return removed
    }

    /** Записать каталог. */
    fun flush() {
        if (readOnly || !dirty) return
        writeDirectory(raf, list)
        // Файл не обрезаем: данные после последней записи могли остаться от удалённых файлов.
        dirty = false
    }

    /** Сколько байт занято файлами, на которые уже нет ссылок (удалённые и заменённые). */
    fun wastedBytes(): Long {
        val used = list.sumOf { it.sizeBytes } + dataStartSector(list.size) * SECTOR
        return (raf.length() - used).coerceAtLeast(0)
    }

    /** Собрать компактную копию архива в [target] без мусора. */
    fun rebuild(target: File) {
        require(target.canonicalPath != file.canonicalPath) { "Нужен другой файл для пересборки" }
        RandomAccessFile(target, "rw").use { out ->
            out.setLength(0)
            var next = dataStartSector(list.size)
            val moved = ArrayList<ImgEntry>(list.size)
            val buf = ByteArray(64 * 1024)
            for (e in list) {
                out.seek(next * SECTOR)
                raf.seek(e.offsetBytes)
                var left = e.sizeBytes
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Архив обрезан: ${e.name}")
                    out.write(buf, 0, n)
                    left -= n
                }
                moved.add(e.copy(offsetSectors = next))
                next += e.sizeSectors
            }
            out.setLength(next * SECTOR)
            writeDirectory(out, moved)
        }
    }

    override fun close() {
        try {
            flush()
        } finally {
            raf.close()
        }
    }

    // ---- внутреннее ----

    private fun checkWritable() {
        if (readOnly) throw IllegalStateException("Архив открыт только для чтения")
    }

    private fun endSector(dirCount: Int = list.size): Long {
        val byLen = (raf.length() + SECTOR - 1) / SECTOR
        val byEntries = list.maxOfOrNull { it.offsetSectors + it.sizeSectors } ?: 0
        return maxOf(byLen, byEntries, dataStartSector(dirCount))
    }

    private fun writeData(offsetSectors: Long, input: InputStream, length: Long, sectors: Int) {
        raf.seek(offsetSectors * SECTOR)
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("Входные данные короче заявленных $length байт")
            raf.write(buf, 0, n)
            left -= n
        }
        val pad = sectors.toLong() * SECTOR - length
        if (pad > 0) raf.write(ByteArray(pad.toInt()))
    }

    /** Переносит в конец файлы, которые мешают каталогу вырасти до [count] записей. */
    private fun ensureDirectoryRoom(count: Int) {
        val needEnd = dirBytes(count)
        while (true) {
            val blocker = list.filter { it.offsetBytes < needEnd }.minByOrNull { it.offsetSectors } ?: return
            val data = read(blocker)
            val offset = endSector(count)
            raf.seek(offset * SECTOR)
            raf.write(data)
            val i = list.indexOf(blocker)
            list[i] = blocker.copy(offsetSectors = offset)
            // Запишем каталог сразу, чтобы при сбое запись не указывала на затёртые данные.
            writeDirectory(raf, list)
        }
    }

    companion object {
        const val SECTOR = 2048
        const val NAME_LEN = 24
        private const val ENTRY_SIZE = 32
        private const val HEADER_SIZE = 8
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'R'.code.toByte(), '2'.code.toByte())

        fun open(file: File, readOnly: Boolean = false): ImgArchive {
            val raf = RandomAccessFile(file, if (readOnly) "r" else "rw")
            try {
                return ImgArchive(file, raf, readDirectory(raf), readOnly)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }

        /** Создать пустой архив. */
        fun create(file: File): ImgArchive {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                writeDirectory(raf, emptyList())
            }
            return open(file)
        }

        fun sectorsFor(length: Long): Int {
            val s = (length + SECTOR - 1) / SECTOR
            require(s <= 0xFFFF) { "Файл больше ${0xFFFF * SECTOR / 1024 / 1024} МБ не помещается в IMG" }
            return s.toInt()
        }

        fun validateName(name: String) {
            require(name.isNotEmpty()) { "Пустое имя файла" }
            require(name.length < NAME_LEN) { "Имя «$name» длиннее ${NAME_LEN - 1} символов" }
            require(name.all { it.code in 0x21..0x7E }) { "Имя «$name» должно быть латиницей без пробелов" }
        }

        private fun dirBytes(count: Int): Long = HEADER_SIZE + ENTRY_SIZE.toLong() * count

        private fun dataStartSector(count: Int): Long = (dirBytes(count) + SECTOR - 1) / SECTOR

        private fun readDirectory(raf: RandomAccessFile): List<ImgEntry> {
            if (raf.length() < HEADER_SIZE) throw IOException("Файл слишком мал для IMG-архива")
            val header = ByteArray(HEADER_SIZE)
            raf.seek(0)
            raf.readFully(header)
            if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) {
                throw IOException("Это не IMG VER2 (нет сигнатуры VER2). Старый формат VER1 (.dir + .img) не поддерживается")
            }
            val count = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (count < 0 || dirBytes(count) > raf.length()) throw IOException("Повреждён каталог IMG: $count записей")
            val dir = ByteArray(ENTRY_SIZE * count)
            raf.readFully(dir)
            val bb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
            return List(count) {
                val offset = bb.int.toLong() and 0xFFFFFFFFL
                val streaming = bb.short.toInt() and 0xFFFF
                val archive = bb.short.toInt() and 0xFFFF
                val nameBytes = ByteArray(NAME_LEN).also { bb.get(it) }
                val end = nameBytes.indexOf(0).let { if (it < 0) NAME_LEN else it }
                ImgEntry(String(nameBytes, 0, end, Charsets.ISO_8859_1), offset, if (streaming != 0) streaming else archive)
            }
        }

        private fun writeDirectory(raf: RandomAccessFile, entries: List<ImgEntry>) {
            val bb = ByteBuffer.allocate(dirBytes(entries.size).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(MAGIC)
            bb.putInt(entries.size)
            for (e in entries) {
                bb.putInt(e.offsetSectors.toInt())
                bb.putShort(e.sizeSectors.toShort())
                bb.putShort(0)
                val name = ByteArray(NAME_LEN)
                val src = e.name.toByteArray(Charsets.ISO_8859_1)
                System.arraycopy(src, 0, name, 0, minOf(src.size, NAME_LEN - 1))
                bb.put(name)
            }
            raf.seek(0)
            raf.write(bb.array())
            val minLen = dataStartSector(entries.size) * SECTOR
            if (raf.length() < minLen) raf.setLength(minLen)
        }
    }
}
