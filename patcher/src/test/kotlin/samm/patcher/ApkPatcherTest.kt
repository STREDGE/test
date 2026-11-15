package samm.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApkPatcherTest {
    private val dir = Files.createTempDirectory("patch").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun smali(name: String, vararg sources: String): ByteArray {
        val srcDir = File(dir, "smali-$name").apply { mkdirs() }
        val files = sources.mapIndexed { i, s -> File(srcDir, "c$i.smali").apply { writeText(s.trimIndent()) }.path }
        val out = File(dir, "$name.dex")
        val opts = SmaliOptions().apply { outputDexFile = out.path; apiLevel = 21 }
        check(Smali.assemble(opts, files)) { "smali failed" }
        return out.readBytes()
    }

    private val gameActivity = """
        .class public Lcom/rockstargames/gtasa/GTASA;
        .super Landroid/app/Activity;
        .method static constructor <clinit>()V
            .registers 1
            const-string v0, "ImmEmulatorJ"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            const-string v0, "GTASA"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
            return-void
        .end method
        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Landroid/app/Activity;-><init>()V
            return-void
        .end method
    """

    private val bridge = """
        .class public Lsamm/bridge/Bridge;
        .super Ljava/lang/Object;
        .method public static afterGameLibs()V
            .registers 0
            return-void
        .end method
    """

    private fun manifest(split: String? = null): ByteArray {
        val m = AndroidManifestBlock()
        m.packageName = "com.rockstargames.gtasa"
        m.versionCode = 311
        m.versionName = "2.11.311"
        m.setMinSdkVersion(26)
        if (split != null) {
            m.setSplit(split, true)
        } else {
            m.getOrCreateApplicationElement()
            m.getOrCreateMainActivity("com.rockstargames.gtasa.GTASA")
        }
        m.refreshFull()
        return ByteArrayOutputStream().also { m.writeBytes(it) }.toByteArray()
    }

    private fun zip(file: File, entries: Map<String, Pair<ByteArray, Boolean>>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, v) in entries) {
                val (data, stored) = v
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
    }

    private fun makeGame(): List<File> {
        val base = File(dir, "base.apk")
        zip(
            base,
            mapOf(
                "AndroidManifest.xml" to (manifest() to false),
                "classes.dex" to (smali("game", gameActivity) to false),
                "resources.arsc" to (ByteArray(16) to true),
                "META-INF/CERT.RSA" to (ByteArray(3) to false),
                "META-INF/CERT.SF" to (ByteArray(3) to false),
                "META-INF/MANIFEST.MF" to (ByteArray(3) to false),
            ),
        )
        val split = File(dir, "split_config.arm64_v8a.apk")
        zip(
            split,
            mapOf(
                "AndroidManifest.xml" to (manifest(split = "config.arm64_v8a") to false),
                "lib/arm64-v8a/libGTASA.so" to ("ELF-GTASA".toByteArray() to true),
                "lib/arm64-v8a/libImmEmulatorJ.so" to ("ELF-IMM".toByteArray() to true),
            ),
        )
        return listOf(base, split)
    }

    private fun entries(f: File): Map<String, ZipEntry> = java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().associateBy { it.name } }

    private fun read(f: File, name: String): ByteArray = java.util.zip.ZipFile(f).use { z -> z.getInputStream(z.getEntry(name)).readBytes() }

    @Test
    fun inspectFindsSplitsAndLibs() {
        val game = ApkPatcher.inspect(makeGame())
        assertEquals("base", game.base.label)
        assertEquals("2.11.311", game.base.manifest.versionName)
        assertEquals("com.rockstargames.gtasa.GTASA", game.base.manifest.mainActivity)
        assertEquals(setOf("arm64-v8a"), game.abis)
        assertEquals("config.arm64_v8a", game.partWithLib("arm64-v8a")!!.label)
        assertEquals(true, game.partWithLib("arm64-v8a")!!.gameLibs["arm64-v8a"])
        val hook = ApkPatcher.describeHook(makeGame())
        assertTrue("Lcom/rockstargames/gtasa/GTASA;-><clinit>" in hook && "AFTER_LOAD_LIBRARY" in hook, hook)
    }

    @Test
    fun extractGameLib() {
        val out = ByteArrayOutputStream()
        val sha = ApkPatcher.extractEntry(makeGame(), "lib/arm64-v8a/libGTASA.so", out)
        assertNotNull(sha)
        assertContentEquals("ELF-GTASA".toByteArray(), out.toByteArray())
    }

    @Test
    fun amlPatchProducesExpectedApks() {
        val apks = makeGame()
        val bridgeDex = DexPatcher.extractClasses(listOf(smali("bridge", bridge)))
        val result = ApkPatcher.patch(
            apks,
            File(dir, "out"),
            PatchOptions(
                mode = PatchMode.AML,
                bridgeDex = bridgeDex,
                bridgeConfig = mapOf("manager" to "samm.app", "token" to "секрет"),
                nativePayload = mapOf("arm64-v8a" to mapOf("libAML.so" to "ELF-AML".toByteArray()), "armeabi-v7a" to mapOf("libAML.so" to ByteArray(1))),
            ),
        )
        assertEquals(2, result.unsignedApks.size)
        val (base, split) = result.unsignedApks

        // Старая подпись выкинута, ресурсы остались STORED.
        val be = entries(base)
        assertTrue(be.keys.none { it.startsWith("META-INF/") })
        assertEquals(ZipEntry.STORED, be.getValue("resources.arsc").method)
        assertTrue("classes2.dex" in be)

        // Манифест: провайдер моста.
        val m = AndroidManifestBlock.load(read(base, "AndroidManifest.xml").inputStream())
        val xml = m.toString() + m.applicationElement.toString()
        assertTrue(ManifestPatcher.BRIDGE_PROVIDER in collectStrings(m), xml)
        // Повторный патч манифеста ничего не дублирует.
        val twice = ManifestPatcher.addBridgeProvider(read(base, "AndroidManifest.xml"))
        assertEquals(1, collectStrings(AndroidManifestBlock.load(twice.inputStream())).count { it == ManifestPatcher.BRIDGE_PROVIDER })

        // Dex: вызов моста сразу после loadLibrary("GTASA").
        val dex = DexPatcher.load(read(base, "classes.dex"))
        val clinit = dex.classes.first { it.type == "Lcom/rockstargames/gtasa/GTASA;" }.methods.first { it.name == "<clinit>" }
        val calls = clinit.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }
            .map { ((it as ReferenceInstruction).reference as MethodReference).let { r -> r.definingClass + "->" + r.name } }
        assertEquals(
            listOf("Ljava/lang/System;->loadLibrary", "Ljava/lang/System;->loadLibrary", "Lsamm/bridge/Bridge;->afterGameLibs"),
            calls,
        )
        assertTrue(DexPatcher.load(read(base, "classes2.dex")).classes.any { it.type == DexPatcher.BRIDGE_CLASS })

        // Конфиг моста.
        val props = java.util.Properties().apply { load(read(base, ApkPatcher.BRIDGE_CONFIG_ASSET).inputStream()) }
        assertEquals("AML", props.getProperty("mode"))
        assertEquals("секрет", props.getProperty("token"))

        // AML рядом с libGTASA.so, тоже STORED.
        val se = entries(split)
        assertEquals(ZipEntry.STORED, se.getValue("lib/arm64-v8a/libAML.so").method)
        assertContentEquals("ELF-AML".toByteArray(), read(split, "lib/arm64-v8a/libAML.so"))
        assertTrue(result.notes.any { "AFTER_LOAD_LIBRARY" in it })

        // Второй патч уже пропатченной игры — понятная ошибка.
        val again = assertFailsWith<PatchException> {
            ApkPatcher.patch(result.unsignedApks, File(dir, "out2"), PatchOptions(PatchMode.TRIAL, bridgeDex, emptyMap()))
        }
        assertTrue("уже" in again.message!!)
    }

    @Test
    fun trialPatchDoesNotTouchLibs() {
        val bridgeDex = DexPatcher.extractClasses(listOf(smali("bridge", bridge)))
        val result = ApkPatcher.patch(makeGame(), File(dir, "trial"), PatchOptions(PatchMode.TRIAL, bridgeDex, mapOf("manager" to "samm.app")))
        val split = result.unsignedApks[1]
        assertEquals(setOf("AndroidManifest.xml", "lib/arm64-v8a/libGTASA.so", "lib/arm64-v8a/libImmEmulatorJ.so"), entries(split).keys)
        ZipFile.builder().setFile(split).get().use { z -> assertEquals(ZipEntry.STORED, z.getEntry("lib/arm64-v8a/libGTASA.so").method) }
    }

    @Test
    fun onCreateFallbackWhenNoLoadLibrary() {
        val act = """
            .class public Lcom/x/Main;
            .super Landroid/app/Activity;
            .method protected onCreate(Landroid/os/Bundle;)V
                .registers 2
                invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
                return-void
            .end method
        """
        val r = DexPatcher.patch(mapOf("classes.dex" to smali("fallback", act)), "com.x.Main")
        assertEquals(DexPatcher.HookKind.MAIN_ACTIVITY_ON_CREATE, r.kind)
        val m = DexPatcher.load(r.patchedDex).classes.first().methods.first { it.name == "onCreate" }
        val first = m.implementation!!.instructions.first()
        assertEquals("afterGameLibs", ((first as ReferenceInstruction).reference as MethodReference).name)
    }

    @Test
    fun dexNaming() {
        assertEquals("classes2.dex", DexPatcher.nextDexName(listOf("classes.dex")))
        assertEquals("classes4.dex", DexPatcher.nextDexName(listOf("classes.dex", "classes2.dex", "classes3.dex")))
    }

    private fun collectStrings(m: AndroidManifestBlock): List<String> {
        val out = ArrayList<String>()
        @Suppress("UNCHECKED_CAST")
        val it = m.applicationElement.recursiveAttributes() as Iterator<com.reandroid.arsc.chunk.xml.ResXmlAttribute>
        it.forEach { a -> a.valueAsString?.let(out::add) }
        return out
    }
}
