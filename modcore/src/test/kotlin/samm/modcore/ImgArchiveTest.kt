package samm.modcore

import samm.modcore.img.ImgArchive
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImgArchiveTest {
    private val dir = Files.createTempDirectory("img").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    /** Сравнить содержимое без паддинга до сектора. */
    private fun assertData(expected: ByteArray, actual: ByteArray) {
        assertTrue(actual.size >= expected.size)
        assertContentEquals(expected, actual.copyOf(expected.size))
        assertTrue(actual.drop(expected.size).all { it == 0.toByte() })
    }

    @Test
    fun createAddReadReopen() {
        val f = File(dir, "gta3.img")
        val a = bytes(5000, 1)
        val b = bytes(100, 2)
        ImgArchive.create(f).use { img ->
            img.put("infernus.dff", a)
            img.put("infernus.txd", b)
        }
        ImgArchive.open(f, readOnly = true).use { img ->
            assertEquals(listOf("infernus.dff", "infernus.txd"), img.entries.map { it.name })
            assertEquals(3, img.find("INFERNUS.DFF")!!.sizeSectors)
            assertData(a, img.read("infernus.dff"))
            assertData(b, img.read("infernus.txd"))
        }
        assertEquals(0, f.length() % ImgArchive.SECTOR)
    }

    @Test
    fun replaceSmallerInPlaceAndBiggerAtEnd() {
        val f = File(dir, "a.img")
        ImgArchive.create(f).use { img ->
            img.put("one.dff", bytes(4096, 1))
            img.put("two.dff", bytes(4096, 2))
        }
        val lenBefore = f.length()
        val small = bytes(1000, 3)
        ImgArchive.open(f).use { it.put("one.dff", small) }
        assertEquals(lenBefore, f.length())
        val big = bytes(10_000, 4)
        ImgArchive.open(f).use { it.put("two.dff", big) }
        ImgArchive.open(f, readOnly = true).use { img ->
            assertData(small, img.read("one.dff"))
            assertData(big, img.read("two.dff"))
            assertTrue(img.wastedBytes() > 0)
        }
    }

    @Test
    fun directoryGrowsByMovingFirstFiles() {
        val f = File(dir, "grow.img")
        val data = (0 until 200).associate { "file$it.txd" to bytes(100 + it * 37, it) }
        ImgArchive.create(f).use { img ->
            // Первый файл сразу за каталогом из одной записи — потом каталог его «наедет».
            data.forEach { (name, d) -> img.put(name, d) }
        }
        ImgArchive.open(f, readOnly = true).use { img ->
            assertEquals(200, img.entries.size)
            val dirEnd = 8 + 32 * 200
            assertTrue(img.entries.all { it.offsetBytes >= dirEnd })
            data.forEach { (name, d) -> assertData(d, img.read(name)) }
            // Пересечений данных нет
            val sorted = img.entries.sortedBy { it.offsetSectors }
            sorted.zipWithNext().forEach { (x, y) -> assertTrue(x.offsetSectors + x.sizeSectors <= y.offsetSectors) }
        }
    }

    @Test
    fun deleteAndRebuild() {
        val f = File(dir, "d.img")
        val keep = bytes(3000, 7)
        ImgArchive.create(f).use { img ->
            img.put("keep.dff", keep)
            img.put("drop.dff", bytes(50_000, 8))
        }
        ImgArchive.open(f).use { img ->
            assertTrue(img.delete("DROP.DFF"))
            assertFalse("drop.dff" in img)
        }
        val out = File(dir, "rebuilt.img")
        ImgArchive.open(f, readOnly = true).use { it.rebuild(out) }
        assertTrue(out.length() < f.length())
        ImgArchive.open(out, readOnly = true).use { img ->
            assertEquals(listOf("keep.dff"), img.entries.map { it.name })
            assertData(keep, img.read("keep.dff"))
            assertEquals(0L, img.wastedBytes())
        }
    }

    @Test
    fun rejectsBadNamesAndFormats() {
        val f = File(dir, "n.img")
        ImgArchive.create(f).use { img ->
            assertFailsWith<IllegalArgumentException> { img.put("a_very_long_name_over_23.dff", ByteArray(1)) }
            assertFailsWith<IllegalArgumentException> { img.put("машина.dff", ByteArray(1)) }
        }
        val notImg = File(dir, "x.img").apply { writeBytes(ByteArray(4096)) }
        assertFailsWith<java.io.IOException> { ImgArchive.open(notImg) }
    }
}
