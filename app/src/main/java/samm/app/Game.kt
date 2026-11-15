package samm.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import samm.modcore.mods.GameLayout
import samm.patcher.ManifestPatcher
import java.io.File

/** Установленная GTA SA. */
data class GameInstall(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val apks: List<File>,
    val patched: Boolean,
    val primaryAbi: String?,
)

object Game {
    const val PACKAGE = GameLayout.PACKAGE

    fun find(ctx: Context): GameInstall? {
        val pm = ctx.packageManager
        val pi = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(PACKAGE, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val ai: ApplicationInfo = pi.applicationInfo ?: return null
        val apks = buildList {
            add(File(ai.sourceDir))
            ai.splitSourceDirs?.forEach { add(File(it)) }
        }
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        return GameInstall(PACKAGE, pi.versionName, code, apks, isPatched(ctx), primaryAbi(ai))
    }

    fun isPatched(ctx: Context): Boolean =
        ctx.packageManager.resolveContentProvider(ManifestPatcher.bridgeAuthority(PACKAGE), 0) != null

    @Suppress("DiscouragedPrivateApi")
    private fun primaryAbi(ai: ApplicationInfo): String? = try {
        ApplicationInfo::class.java.getField("primaryCpuAbi").get(ai) as String?
    } catch (_: Throwable) {
        null
    }

    /** ABI, которые поддерживает телефон, в порядке предпочтения. */
    val deviceAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()
}

/** Клиент моста внутри пропатченной игры. */
class BridgeClient(private val ctx: Context) {
    private val authority = ManifestPatcher.bridgeAuthority(Game.PACKAGE)
    private val base = android.net.Uri.parse("content://$authority")

    fun available(): Boolean = Game.isPatched(ctx)

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        val b = ctx.contentResolver.call(base, method, arg, extras) ?: throw IllegalStateException("Мост не ответил")
        b.getString("error")?.let { throw IllegalStateException(it) }
        return b
    }

    data class Status(
        val startedAt: Long?,
        val heartbeatAt: Long?,
        val now: Long,
        val aml: String?,
        val amlError: String?,
        val crash: String?,
        val raw: String?,
    )

    fun status(): Status {
        val b = call("status")
        val props = java.util.Properties()
        b.getString("status")?.let { props.load(it.reader()) }
        return Status(
            startedAt = props.getProperty("startedAt")?.toLongOrNull(),
            heartbeatAt = b.getString("heartbeat")?.trim()?.toLongOrNull(),
            now = b.getLong("now"),
            aml = props.getProperty("aml"),
            amlError = props.getProperty("amlError"),
            crash = b.getString("crash"),
            raw = b.getString("status"),
        )
    }

    data class Entry(val name: String, val size: Long, val dir: Boolean, val modified: Long)

    fun list(path: String): List<Entry> {
        val b = call("list", path)
        val names = b.getStringArrayList("names") ?: return emptyList()
        val sizes = b.getLongArray("sizes")!!
        val dirs = b.getBooleanArray("dirs")!!
        val times = b.getLongArray("times")!!
        return names.indices.map { Entry(names[it], sizes[it], dirs[it], times[it]) }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
    }

    fun delete(path: String) = call("delete", path).getBoolean("ok")
    fun mkdirs(path: String) = call("mkdirs", path).getBoolean("ok")
    fun rename(from: String, to: String) = call("rename", from, Bundle().apply { putString("to", to) }).getBoolean("ok")

    fun write(path: String, input: java.io.InputStream) {
        ctx.contentResolver.openOutputStream(fileUri(path), "wt")!!.use { input.copyTo(it) }
    }

    fun read(path: String): java.io.InputStream = ctx.contentResolver.openInputStream(fileUri(path))!!

    /** Прямой доступ к файлу (для IMG-редактора — нужен произвольный доступ). */
    fun openFd(path: String, mode: String) = ctx.contentResolver.openFileDescriptor(fileUri(path), mode)!!

    private fun fileUri(path: String) = base.buildUpon().appendPath("f").apply { path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()
}
