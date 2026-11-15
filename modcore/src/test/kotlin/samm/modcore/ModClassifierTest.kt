package samm.modcore

import samm.modcore.mods.ModArchive
import samm.modcore.mods.ModClassifier
import samm.modcore.mods.ModTarget
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ModClassifierTest {

    private fun dest(paths: List<String>) = ModClassifier.classify(paths).files.associate { it.archivePath to (it.target to it.destination) }

    @Test
    fun cleoScriptsWithWrapperFolder() {
        val r = dest(listOf("Super Mod v2/cleo/supermod.csa", "Super Mod v2/cleo/supermod.fxt", "Super Mod v2/readme.txt", "Super Mod v2/screen.jpg"))
        assertEquals(ModTarget.CLEO to "CLEO/supermod.csa", r["Super Mod v2/cleo/supermod.csa"])
        assertEquals(ModTarget.CLEO to "CLEO/supermod.fxt", r["Super Mod v2/cleo/supermod.fxt"])
        assertEquals(ModTarget.IGNORE, r["Super Mod v2/readme.txt"]!!.first)
        assertEquals(ModTarget.IGNORE, r["Super Mod v2/screen.jpg"]!!.first)
    }

    @Test
    fun bareScriptsAndCleoSubfolders() {
        val r = dest(listOf("Speedometer.csi", "cleo/speedo/config.ini"))
        assertEquals(ModTarget.CLEO to "CLEO/Speedometer.csi", r["Speedometer.csi"])
        assertEquals(ModTarget.CLEO to "CLEO/speedo/config.ini", r["cleo/speedo/config.ini"])
    }

    @Test
    fun amlModWithConfig() {
        val r = dest(listOf("mods/libSkyGFX.so", "configs/SkyGFX.ini", "libFLA.so"))
        assertEquals(ModTarget.AML_MOD to "mods/libSkyGFX.so", r["mods/libSkyGFX.so"])
        assertEquals(ModTarget.AML_CONFIG to "configs/SkyGFX.ini", r["configs/SkyGFX.ini"])
        assertEquals(ModTarget.AML_MOD to "mods/libFLA.so", r["libFLA.so"])
    }

    @Test
    fun carForImg() {
        val r = dest(listOf("Lamborghini/infernus.dff", "Lamborghini/infernus.txd", "Lamborghini/Установка.txt", "Lamborghini/texdb/gta3/gta3.txt"))
        assertEquals(ModTarget.IMG to "infernus.dff", r["Lamborghini/infernus.dff"])
        assertEquals(ModTarget.IMG to "infernus.txd", r["Lamborghini/infernus.txd"])
        assertEquals(ModTarget.IGNORE, r["Lamborghini/Установка.txt"]!!.first)
        assertEquals(ModTarget.GAME_FILE to "files/texdb/gta3/gta3.txt", r["Lamborghini/texdb/gta3/gta3.txt"])
    }

    @Test
    fun dataFilesAndJunk() {
        val plan = ModClassifier.classify(listOf("handling.cfg", "data/vehicles.ide", "__MACOSX/x", "weird.bin"))
        val r = plan.files.associate { it.archivePath to (it.target to it.destination) }
        assertEquals(ModTarget.GAME_FILE to "files/data/handling.cfg", r["handling.cfg"])
        assertEquals(ModTarget.GAME_FILE to "files/data/vehicles.ide", r["data/vehicles.ide"])
        assertEquals(ModTarget.UNKNOWN, r["weird.bin"]!!.first)
        assertEquals(3, plan.files.size)
    }

    @Test
    fun readsZipArchive() {
        val f = Files.createTempFile("mod", ".zip").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("cleo/test.csa")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            z.putNextEntry(ZipEntry("mods/")); z.closeEntry()
            z.putNextEntry(ZipEntry("mods/libX.so")); z.write(byteArrayOf(4)); z.closeEntry()
        }
        assertEquals(ModArchive.Kind.ZIP, ModArchive.detect(f))
        assertEquals(listOf("cleo/test.csa", "mods/libX.so"), ModArchive.list(f))
        val sizes = HashMap<String, Int>()
        ModArchive.forEach(f) { p, s -> sizes[p] = s.readBytes().size }
        assertEquals(mapOf("cleo/test.csa" to 3, "mods/libX.so" to 1), sizes)
        f.delete()
    }

    @Test
    fun unusedFileIsDetected() {
        assertEquals(ModArchive.Kind.UNKNOWN, ModArchive.detect(File.createTempFile("unknown", ".bin").apply { writeText("hello"); deleteOnExit() }))
    }
}
