package samm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Файлы OBB игры (Android/obb/<пакет>) удаляются вместе с игрой.
 * С разрешением «Доступ ко всем файлам» Android пускает в Android/obb (но не в Android/data),
 * поэтому перед удалением оригинала копируем OBB к себе, а после установки — возвращаем.
 */
object Obb {
    @Suppress("DEPRECATION")
    private val storage get() = Environment.getExternalStorageDirectory()
    val gameDir: File get() = File(storage, "Android/obb/${Game.PACKAGE}")
    val backupDir: File get() = File(storage, "SAMM/backup/obb")

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun requestAllFilesAccess(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Список OBB игры или null, если папку не прочитать. */
    fun list(): List<File>? = gameDir.listFiles()?.filter { it.isFile }

    fun backup(log: (String) -> Unit): Int {
        val files = list() ?: return 0
        backupDir.mkdirs()
        var n = 0
        for (f in files) {
            val dst = File(backupDir, f.name)
            if (dst.length() == f.length()) continue
            log("Копирую ${f.name} (${f.length() / 1024 / 1024} МБ)…")
            f.copyTo(dst, overwrite = true)
            n++
        }
        return files.size
    }

    fun restore(log: (String) -> Unit): Int {
        val files = backupDir.listFiles()?.filter { it.isFile && it.name.endsWith(".obb") } ?: return 0
        if (files.isEmpty()) return 0
        gameDir.mkdirs()
        for (f in files) {
            val dst = File(gameDir, f.name)
            if (dst.length() == f.length()) continue
            log("Возвращаю ${f.name}…")
            f.copyTo(dst, overwrite = true)
        }
        return files.size
    }
}
