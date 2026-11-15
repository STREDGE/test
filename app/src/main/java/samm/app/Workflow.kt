package samm.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import samm.patcher.ApkPatcher
import samm.patcher.DexPatcher
import samm.patcher.PatchMode
import samm.patcher.PatchOptions
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

/** Длинные операции: выгрузка libGTASA.so, сборка пропатченной игры. */
class Workflow(private val ctx: Context, private val log: (String) -> Unit) {

    private val work get() = File(ctx.cacheDir, "patch")

    /** Сохраняет libGTASA.so в «Загрузки». Возвращает путь для показа пользователю. */
    fun exportGameLib(game: GameInstall): String {
        val abis = listOfNotNull(game.primaryAbi) + Game.deviceAbis + listOf("arm64-v8a", "armeabi-v7a")
        val gameApks = ApkPatcher.inspect(game.apks)
        val abi = abis.distinct().firstOrNull { it in gameApks.abis }
            ?: throw IllegalStateException("В APK игры не найдена libGTASA.so (архитектуры: ${gameApks.abis})")
        val name = "libGTASA_${game.versionName}_$abi.so"
        log("Нашёл lib/$abi/libGTASA.so, сохраняю как $name…")
        val sha = writeDownload(name, "application/octet-stream") { out ->
            ApkPatcher.extractEntry(game.apks, "lib/$abi/libGTASA.so", out) ?: error("libGTASA.so пропала")
        }
        val info = buildString {
            appendLine("package=${game.packageName}")
            appendLine("versionName=${game.versionName}")
            appendLine("versionCode=${game.versionCode}")
            appendLine("abi=$abi")
            appendLine("abis=${gameApks.abis.joinToString(",")}")
            appendLine("sha256=$sha")
            appendLine("apks=${game.apks.joinToString(",") { it.name }}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("deviceAbis=${Game.deviceAbis.joinToString(",")}")
        }
        writeDownload("libGTASA_${game.versionName}_$abi.txt", "text/plain") { it.write(info.toByteArray()); "" }
        log("Готово. SHA-256: $sha")
        return "Загрузки/$name"
    }

    /**
     * Собирает и подписывает пропатченную игру.
     * @return подписанные APK, готовые к установке
     */
    fun buildPatched(game: GameInstall, mode: PatchMode): List<File> {
        work.deleteRecursively()
        val inDir = File(work, "in").apply { mkdirs() }
        val unsignedDir = File(work, "unsigned")
        val outDir = File(work, "signed").apply { mkdirs() }

        log("Копирую APK игры (${game.apks.size} шт.)…")
        val copies = game.apks.map { src ->
            val name = if (src.name == "base.apk" || game.apks.indexOf(src) == 0) "base.apk" else src.name
            File(inDir, name).also { src.copyTo(it, overwrite = true) }
        }

        log("Готовлю мост из APK менеджера…")
        val ownDexes = ZipFile(ctx.applicationInfo.sourceDir).use { z ->
            z.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) }.map { z.getInputStream(it).readBytes() }.toList()
        }
        val bridgeDex = DexPatcher.extractClasses(ownDexes)

        val payload = if (mode == PatchMode.AML) loadPayload() else emptyMap()
        if (mode == PatchMode.AML && payload.isEmpty()) throw IllegalStateException("В этой сборке менеджера нет AML (payload пуст)")

        log("Патчу (${mode.name})…")
        val result = ApkPatcher.patch(
            copies, unsignedDir,
            PatchOptions(
                mode = mode,
                bridgeDex = bridgeDex,
                bridgeConfig = mapOf(
                    "manager" to ctx.packageName,
                    "managerCert" to Signer.managerCertSha256(ctx),
                    "managerVersion" to BuildConfigCompat.versionName(ctx),
                ),
                nativePayload = payload,
            ),
        )
        result.notes.forEach(log)

        val minSdk = ApkPatcher.inspect(copies).base.manifest.minSdk ?: 21
        log("Подписываю…")
        val signed = result.unsignedApks.map { u ->
            File(outDir, u.name).also { Signer.sign(u, it, minSdk) }
        }
        inDir.deleteRecursively()
        unsignedDir.deleteRecursively()
        log("Собрано: ${signed.joinToString { "${it.name} (${it.length() / 1024 / 1024} МБ)" }}")
        return signed
    }

    /** assets/payload/<abi>/*.so из APK менеджера. */
    private fun loadPayload(): Map<String, Map<String, ByteArray>> {
        val am = ctx.assets
        val abis = am.list("payload")?.toList().orEmpty()
        return abis.associateWith { abi ->
            am.list("payload/$abi").orEmpty().filter { it.endsWith(".so") }.associateWith { so -> am.open("payload/$abi/$so").use { it.readBytes() } }
        }.filterValues { it.isNotEmpty() }
    }

    private fun writeDownload(name: String, mime: String, body: (OutputStream) -> String): String {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать файл в Загрузках")
            val r = resolver.openOutputStream(uri)!!.use(body)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return r
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        return File(dir, name).outputStream().use(body)
    }
}

object BuildConfigCompat {
    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
