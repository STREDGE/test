package samm.patcher

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry

/** Один APK установленной игры (base или split). */
data class ApkPart(
    val file: File,
    val manifest: ManifestInfo,
    /** ABI → хранится ли libGTASA.so несжатой (STORED). */
    val gameLibs: Map<String, Boolean>,
) {
    val isBase: Boolean get() = manifest.splitName == null
    val label: String get() = manifest.splitName ?: "base"
}

data class GameApks(val parts: List<ApkPart>) {
    val base: ApkPart get() = parts.first { it.isBase }
    val abis: Set<String> get() = parts.flatMap { it.gameLibs.keys }.toSet()
    fun partWithLib(abi: String): ApkPart? = parts.firstOrNull { abi in it.gameLibs }
}

enum class PatchMode {
    /** Только мост (проверка запуска после переподписи), без AML. */
    TRIAL,
    /** Мост + AML (+ наш CLEO и другие .so из payload). */
    AML,
}

class PatchOptions(
    val mode: PatchMode,
    /** dex с классами samm.bridge.* (см. [DexPatcher.extractClasses]). */
    val bridgeDex: ByteArray,
    /** Настройки моста: пакет менеджера, отпечаток его сертификата, токен. */
    val bridgeConfig: Map<String, String>,
    /** ABI → (имя .so → содержимое), кладётся в lib/<abi>/. Для TRIAL не используется. */
    val nativePayload: Map<String, Map<String, ByteArray>> = emptyMap(),
    val gameLib: String = "GTASA",
)

data class PatchResult(
    val unsignedApks: List<File>,
    val hook: String,
    val notes: List<String>,
)

object ApkPatcher {
    const val BRIDGE_CONFIG_ASSET = "assets/samm/bridge.properties"

    fun inspect(apks: List<File>, gameLib: String = "GTASA"): GameApks {
        val parts = apks.map { f ->
            ZipFile.builder().setFile(f).get().use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: throw PatchException("${f.name}: нет AndroidManifest.xml")
                val manifest = ManifestPatcher.read(zip.getInputStream(manifestEntry).readBytes())
                val libs = zip.entries.asSequence()
                    .mapNotNull { e -> LIB_RE.matchEntire(e.name)?.takeIf { it.groupValues[2] == "lib$gameLib.so" }?.let { it.groupValues[1] to (e.method == ZipEntry.STORED) } }
                    .toMap()
                ApkPart(f, manifest, libs)
            }
        }
        if (parts.count { it.isBase } != 1) throw PatchException("Нужен ровно один base.apk, найдено ${parts.count { it.isBase }}")
        return GameApks(parts)
    }

    /** Отчёт о точке входа для AML (без изменения APK). */
    fun describeHook(apks: List<File>, gameLib: String = "GTASA"): String {
        val game = inspect(apks, gameLib)
        ZipFile.builder().setFile(game.base.file).get().use { zip ->
            val dexes = zip.entries.asSequence().map { it.name }.filter { DEX_RE.matches(it) }.toList()
                .associateWith { zip.getInputStream(zip.getEntry(it)).readBytes() }
            return "mainActivity=${game.base.manifest.mainActivity}; hook=" + DexPatcher.describeHook(dexes, game.base.manifest.mainActivity, gameLib)
        }
    }

    /** Вытащить файл из APK игры (например, lib/arm64-v8a/libGTASA.so). Возвращает SHA-256 или null, если нет. */
    fun extractEntry(apks: List<File>, entryName: String, out: OutputStream): String? {
        for (f in apks) {
            ZipFile.builder().setFile(f).get().use { zip ->
                val e = zip.getEntry(entryName) ?: return@use
                val md = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(e).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        }
        return null
    }

    /**
     * Патчит APK игры. Результат — неподписанные APK в [outDir] с теми же именами.
     * Подписывает их потом приложение (apksig).
     */
    fun patch(apks: List<File>, outDir: File, options: PatchOptions): PatchResult {
        val game = inspect(apks, options.gameLib)
        val notes = ArrayList<String>()
        outDir.mkdirs()

        if (options.mode == PatchMode.AML) {
            if (game.abis.isEmpty()) throw PatchException("В APK не найдена lib${options.gameLib}.so")
            val covered = game.abis.filter { it in options.nativePayload }
            if (covered.isEmpty()) throw PatchException("Нет AML для архитектур игры: ${game.abis}")
            (game.abis - covered.toSet()).forEach { notes += "Для $it нет AML — эта архитектура останется без модов" }
        }

        // --- base.apk: манифест + dex + конфиг моста ---
        val base = game.base
        val baseReplace = HashMap<String, ByteArray>()
        val baseAdd = HashMap<String, ZipPayload>()
        val hook: DexPatcher.Result
        ZipFile.builder().setFile(base.file).get().use { zip ->
            val names = zip.entries.asSequence().map { it.name }.toList()
            baseReplace["AndroidManifest.xml"] = ManifestPatcher.addBridgeProvider(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
            val dexes = names.filter { DEX_RE.matches(it) }.associateWith { zip.getInputStream(zip.getEntry(it)).readBytes() }
            hook = DexPatcher.patch(dexes, base.manifest.mainActivity, options.gameLib)
            baseReplace[hook.dexName] = hook.patchedDex
            baseAdd[DexPatcher.nextDexName(dexes.keys)] = ZipPayload(options.bridgeDex, stored = false)
            if (BRIDGE_CONFIG_ASSET in names) throw PatchException("Игра уже содержит $BRIDGE_CONFIG_ASSET")
            baseAdd[BRIDGE_CONFIG_ASSET] = ZipPayload(propertiesBytes(options.bridgeConfig + ("mode" to options.mode.name)), stored = false)
        }
        notes += "Точка входа: ${hook.className}.${hook.methodName} (${hook.kind})"

        // --- нативные библиотеки: туда же, где лежит libGTASA.so ---
        val libAdds = HashMap<File, MutableMap<String, ZipPayload>>()
        if (options.mode == PatchMode.AML) {
            for (abi in game.abis) {
                val payload = options.nativePayload[abi] ?: continue
                val part = game.partWithLib(abi)!!
                val stored = part.gameLibs.getValue(abi)
                val target = libAdds.getOrPut(part.file) { HashMap() }
                payload.forEach { (soName, bytes) -> target["lib/$abi/$soName"] = ZipPayload(bytes, stored) }
            }
        }

        val outputs = game.parts.map { part ->
            val out = File(outDir, part.file.name)
            val add = HashMap<String, ZipPayload>()
            libAdds[part.file]?.let(add::putAll)
            if (part.isBase) {
                add.putAll(baseAdd)
                ZipRewriter.rewrite(part.file, out, replace = baseReplace, add = add)
            } else {
                ZipRewriter.rewrite(part.file, out, add = add)
            }
            out
        }
        return PatchResult(outputs, "${hook.className}.${hook.methodName}", notes)
    }

    private fun propertiesBytes(map: Map<String, String>): ByteArray =
        map.toSortedMap().entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${escape(v)}" }.toByteArray(Charsets.ISO_8859_1)

    private fun escape(v: String) = buildString {
        for (c in v) when {
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c.code > 0x7E || c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    private val LIB_RE = Regex("lib/([^/]+)/([^/]+)")
    private val DEX_RE = Regex("classes\\d*\\.dex")
}
